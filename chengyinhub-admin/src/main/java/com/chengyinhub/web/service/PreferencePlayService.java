package com.chengyinhub.web.service;

import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayerTopicTag;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerTopicTagService;
import com.chengyinhub.business.service.support.PreferenceResultEvaluator;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** validationMethod=6 的求值、幂等持久化、标签和完局聚合业务。 */
@Service
public class PreferencePlayService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PreferenceResultEvaluator evaluator = new PreferenceResultEvaluator();

    @Autowired private com.chengyinhub.business.mapper.PlayAttemptLogMapper attemptMapper;
    @Autowired private IPlayerTopicTagService tagService;
    @Autowired private ICmsTopicNodeService nodeService;
    @Autowired private ICmsMemberTemplateService templateService;
    @Autowired private ICmsTopicService topicService;
    @Autowired private ICmsRegistrationService registrationService;
    @Autowired private IPerkLedgerService perkLedgerService;

    public Map<String, Object> load(Long memberId, Long topicId, Long nodeId, CmsMemberTemplate template) {
        ObjectNode config = evaluator.validate(template.getPreferenceJson());
        List<PlayerTopicTag> inherited = tagService.confirmedForConsumer(memberId, topicId,
                evaluator.consumedTagCodes(template.getPreferenceJson()));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("nodeId", nodeId);
        data.put("steps", config.path("steps"));
        if (config.has("tiebreak")) data.put("tiebreak", config.get("tiebreak"));
        data.put("inheritedTags", inherited);
        return data;
    }

    @DeadlockRetry
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String, Object> submit(Long memberId, Long topicId, Long actId, Long nodeId,
                                      CmsMemberTemplate template, PreferenceSubmitRequest request,
                                      Function<String, Map<String, Object>> progressFunction) {
        return submit(memberId, topicId, actId, nodeId, template, request, "default", progressFunction);
    }

    @DeadlockRetry
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Map<String, Object> submit(Long memberId, Long topicId, Long actId, Long nodeId,
                                      CmsMemberTemplate template, PreferenceSubmitRequest request,
                                      String originalPlayInstance,
                                      Function<String, Map<String, Object>> progressFunction) {
        if (StringUtils.isBlank(originalPlayInstance)) throw new IllegalArgumentException("原局身份缺失");
        String idempotencyKey = "preference:" + memberId + ":" + topicId + ":" + actId + ":" + nodeId;
        // 保留旧 default 身份；新局不能复用旧局的完成投影，也不改写历史键。
        if (!"default".equals(originalPlayInstance)) {
            idempotencyKey = "preference:run:" + com.chengyinhub.business.service.support.CanonicalDigest
                    .sha256Hex(idempotencyKey + "\u001f" + originalPlayInstance);
        }
        PlayAttemptLog existing = attemptMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) return existingData(existing, memberId, topicId, nodeId, template);

        PreferenceResultEvaluator.Evaluation evaluation;
        if (StringUtils.isNotBlank(request.getReuseTagCode())) {
            List<PlayerTopicTag> confirmed = tagService.confirmedForConsumer(memberId, topicId,
                    evaluator.consumedTagCodes(template.getPreferenceJson()));
            PlayerTopicTag inherited = null;
            for (PlayerTopicTag tag : confirmed) {
                if (request.getReuseTagCode().equals(tag.getTagCode())) { inherited = tag; break; }
            }
            if (inherited == null) throw new IllegalArgumentException("该标签未确认，请现场完成替代题");
            evaluation = evaluator.evaluateInherited(template.getPreferenceJson(),
                    inherited.getTagCode(), inherited.getTagValue());
        } else {
            evaluation = evaluator.evaluate(template.getPreferenceJson(), request.getChoices());
        }
        if (evaluation.isNeedsTiebreak()) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("needsTiebreak", true);
            data.put("tiebreak", evaluation.getTiebreak());
            data.put("choices", evaluation.getChoices());
            return data;
        }

        Map<String, Object> stored = new LinkedHashMap<String, Object>();
        stored.put("evaluation", evaluation);
        stored.put("choices", request.getChoices());
        stored.put("nodeId", nodeId);
        stored.put("progress", progressFunction.apply(evaluation.getResultCode()));
        Set<String> availableValues = resultCodes(template.getPreferenceJson());
        if (StringUtils.isNotBlank(evaluation.getTagCode())) stored.put("availableTagValues", availableValues);

        PlayAttemptLog attempt = new PlayAttemptLog();
        attempt.setMemberId(memberId);
        attempt.setTopicId(topicId);
        attempt.setNodeId(nodeId);
        attempt.setActivityId(actId);
        attempt.setAttemptType(ValidationMethod.PREFERENCE_GROUP);
        attempt.setResult(1);
        attempt.setResultJson(write(stored));
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setCreatedAt(new Date());
        try {
            attemptMapper.insertPlayAttemptLog(attempt);
        } catch (DuplicateKeyException e) {
            existing = attemptMapper.selectByIdempotencyKey(idempotencyKey);
            if (existing == null) throw e;
            return existingData(existing, memberId, topicId, nodeId, template);
        }

        PlayerTopicTag pendingTag = null;
        if (StringUtils.isNotBlank(evaluation.getTagCode())) {
            pendingTag = tagService.upsertPending(memberId, topicId,
                    evaluation.getTagCode(), evaluation.getTagValue(), nodeId);
        }
        stored.put("pendingTag", pendingTag);
        stored.put("tagDisclosure", disclosure(template.getPreferenceJson()));
        stored.put("firstTime", true);
        return stored;
    }

    public PlayerTopicTag correctPending(Long memberId, Long tagId, String tagValue) {
        PlayerTopicTag tag = tagService.ownedById(memberId, tagId);
        if (tag == null || tag.getSourceNodeId() == null) throw new IllegalArgumentException("标签不存在");
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(tag.getSourceNodeId());
        if (node == null || node.getTemplateId() == null || !tag.getTopicId().equals(node.getTopicId())) {
            throw new IllegalArgumentException("标签来源节点不存在");
        }
        CmsMemberTemplate template = templateService.selectCmsMemberTemplateById(node.getTemplateId());
        if (template == null || !Integer.valueOf(ValidationMethod.PREFERENCE_GROUP)
                .equals(template.getValidationMethod())) {
            throw new IllegalArgumentException("标签来源模板不存在");
        }
        ObjectNode config = evaluator.validate(template.getPreferenceJson());
        if (!tag.getTagCode().equals(config.path("tagOutput").path("code").asText())) {
            throw new IllegalArgumentException("标签来源合同不匹配");
        }
        return tagService.correctPending(memberId, tagId, tagValue, resultCodes(template.getPreferenceJson()));
    }

    public Map<String, Object> operatingSystem(Long memberId, Long topicId) {
        if (topicId == null || topicService.selectCmsTopicById(topicId) == null) {
            throw new IllegalArgumentException("主题不存在");
        }
        List<PlayAttemptLog> attempts = attemptMapper.selectPreferenceResults(memberId, topicId);
        // 一局没做过偏好玩法就没有可生成的内容:回空结果(页面据此不出这一屏),不在每次通关时报错(实走 B15)
        if (attempts == null || attempts.isEmpty()) return new LinkedHashMap<String, Object>();
        if (!completedByMember(memberId, topicId, attempts)) {
            throw new IllegalArgumentException("完成主题后才能生成新生活操作系统");
        }
        List<Object> resultCards = new ArrayList<Object>();
        List<Map<String, Object>> anchors = new ArrayList<Map<String, Object>>();
        List<String> actions7Days = new ArrayList<String>();
        List<String> actions30Days = new ArrayList<String>();
        Set<Long> anchorNodeIds = new HashSet<Long>();
        if (attempts != null) for (PlayAttemptLog attempt : attempts) {
            try {
                JsonNode result = JSON.readTree(attempt.getResultJson());
                resultCards.add(result);
                String nextStep = result.path("evaluation").path("nextStep").asText("");
                int days = result.path("evaluation").path("nextStepDays").asInt(0);
                if (StringUtils.isNotBlank(nextStep)) {
                    if (days == 30) actions30Days.add(nextStep); else if (days == 7) actions7Days.add(nextStep);
                }
            } catch (Exception ignored) { /* 单张历史坏卡不拖垮其余已确认事实。 */ }
            addAnchor(topicId, attempt, anchorNodeIds, anchors);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("topicId", topicId);
        data.put("edition", "启动版");
        data.put("tags", tagService.confirmedForOwner(memberId, topicId));
        data.put("resultCards", resultCards);
        data.put("mapAnchors", anchors);
        data.put("mapRadiusMeters", 3000);
        data.put("actions7Days", actions7Days);
        data.put("actions30Days", actions30Days);
        return data;
    }

    private boolean completedByMember(Long memberId, Long topicId, List<PlayAttemptLog> attempts) {
        Set<Long> activityIds = new HashSet<Long>();
        if (attempts != null) for (PlayAttemptLog attempt : attempts) {
            if (attempt.getActivityId() != null && attempt.getActivityId() > 0L) {
                activityIds.add(attempt.getActivityId());
            }
        }
        if (activityIds.isEmpty()) return false;
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(2L);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> rows = registrationService.selectCmsRegistrationList(probe);
        if (rows == null) return false;
        for (CmsRegistration row : rows) {
            if (activityIds.contains(row.getOwnerId())
                    && Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(row.getPurchaseKind())
                    && perkLedgerService.isExploreCompleted(row.getId(), topicId)) return true;
        }
        return false;
    }

    private void addAnchor(Long topicId, PlayAttemptLog attempt, Set<Long> seen,
                           List<Map<String, Object>> anchors) {
        if (attempt.getNodeId() == null || !seen.add(attempt.getNodeId())) return;
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(attempt.getNodeId());
        if (node == null || !topicId.equals(node.getTopicId())
                || StringUtils.isBlank(node.getLongitude()) || StringUtils.isBlank(node.getLatitude())) return;
        Map<String, Object> anchor = new LinkedHashMap<String, Object>();
        anchor.put("nodeId", node.getId());
        anchor.put("name", node.getName());
        anchor.put("address", node.getAddress());
        anchor.put("longitude", node.getLongitude());
        anchor.put("latitude", node.getLatitude());
        anchors.add(anchor);
    }

    private Map<String, Object> existingData(PlayAttemptLog existing, Long memberId, Long topicId,
                                             Long nodeId, CmsMemberTemplate template) {
        Map<String, Object> data;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.convertValue(JSON.readTree(existing.getResultJson()), Map.class);
            data = new LinkedHashMap<String, Object>(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("已存偏好结果不可解析", e);
        }
        List<PlayerTopicTag> pending = tagService.pendingForNode(memberId, topicId, nodeId);
        data.put("pendingTag", pending == null || pending.isEmpty() ? null : pending.get(0));
        data.put("tagDisclosure", disclosure(template.getPreferenceJson()));
        data.put("firstTime", false);
        return data;
    }

    private JsonNode disclosure(String preferenceJson) {
        JsonNode output = evaluator.validate(preferenceJson).get("tagOutput");
        return output == null ? NullNode.getInstance() : output;
    }

    private Set<String> resultCodes(String preferenceJson) {
        ObjectNode config = evaluator.validate(preferenceJson);
        Set<String> values = new java.util.LinkedHashSet<String>();
        Iterator<String> names = config.path("results").fieldNames();
        while (names.hasNext()) values.add(names.next());
        return values;
    }

    private String write(Map<String, Object> value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("偏好题结果序列化失败", e); }
    }
}
