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
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final String RECEIPT_SCOPE = "_serverPreferenceScope";
    /** Server-only completion adapter. Preparation must take team/game barriers before member;
     * apply reuses that exact prepared attempt. Existing receipt reads never call either method. */
    public interface CompletionFunction extends Function<String, Map<String, Object>> {
        com.chengyinhub.business.service.ITopicRouteRuntimeService.NodeAwardPermit prepare();
    }
    private final PreferenceResultEvaluator evaluator = new PreferenceResultEvaluator();

    @Autowired private com.chengyinhub.business.mapper.PlayAttemptLogMapper attemptMapper;
    @Autowired private IPlayerTopicTagService tagService;
    @Autowired private ICmsTopicNodeService nodeService;
    @Autowired private ICmsMemberTemplateService templateService;
    @Autowired private ICmsTopicService topicService;
    @Autowired private ICmsRegistrationService registrationService;
    @Autowired private IPerkLedgerService perkLedgerService;
    @Autowired private com.chengyinhub.business.service.ITopicRouteRuntimeService routeRuntimeService;

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
        if (routeRuntimeService == null) throw new IllegalStateException("偏好本局范围不可读取");
        // This bounded batch interface is read-only even when joining this RC transaction.
        // playInstance() intentionally takes route/team write guards in an active transaction,
        // so it cannot be used for this receipt preflight before Advanced preparation.
        com.chengyinhub.business.domain.TopicRouteSession scope = new com.chengyinhub.business.domain.TopicRouteSession();
        scope.setMemberId(memberId); scope.setTopicId(topicId); scope.setActivityId(actId);
        String scopeKey = com.chengyinhub.business.service.ITopicRouteRuntimeService.sessionKey(memberId, topicId, actId);
        Map<String, String> instances = routeRuntimeService.playInstances(java.util.Collections.singletonList(scope));
        String instance = instances == null ? null : instances.get(scopeKey);
        if (instances == null || instances.size() != 1 || instance == null || instance.trim().isEmpty())
            throw new IllegalStateException("偏好本局范围不可读取");
        String idempotencyKey = preferenceKey(memberId, topicId, actId, nodeId, instance);
        PlayAttemptLog existing = attemptMapper.selectByIdempotencyKey(idempotencyKey);
        // Preserve legitimate old default receipts without re-running a now-retired/incomplete
        // advanced game. existingData still verifies owner, scope, origin metadata and currentness.
        if (existing != null) return existingData(existing, memberId, topicId, actId, nodeId, instance, idempotencyKey, template);


        boolean reuseConfirmedTag = StringUtils.isNotBlank(request.getReuseTagCode());
        // Choices are immutable request data and can produce a read-only tiebreak response.
        // Confirmed tags are mutable shared facts: never cache them across game/member waits.
        PreferenceResultEvaluator.Evaluation evaluation = reuseConfirmedTag ? null
                : evaluator.evaluate(template.getPreferenceJson(), request.getChoices());
        if (evaluation != null && evaluation.isNeedsTiebreak()) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("needsTiebreak", true);
            data.put("tiebreak", evaluation.getTiebreak());
            data.put("choices", evaluation.getChoices());
            // Pure evaluation may overlap a final submit/replay on another device. Refresh the
            // exact read-only scope (also clears the mapper cache) and prefer a committed receipt.
            Map<String, String> refreshed = routeRuntimeService.playInstances(java.util.Collections.singletonList(scope));
            if (refreshed == null || refreshed.size() != 1 || !instance.equals(refreshed.get(scopeKey)))
                throw new IllegalStateException("偏好本局范围已更新");
            existing = attemptMapper.selectByIdempotencyKey(idempotencyKey);
            if (existing != null) return existingData(existing, memberId, topicId, actId, nodeId, instance, idempotencyKey, template);
            routeRuntimeService.assertCurrentPlayInstance(memberId, topicId, actId, instance);
            return data;
        }

        // Only a final preference result is completion intent. A tiebreak response above is
        // read-only and must not require or complete an advanced game, nor acquire the award barrier.
        if (!(progressFunction instanceof CompletionFunction))
            throw new IllegalStateException("偏好完成准备器不可用");
        com.chengyinhub.business.service.ITopicRouteRuntimeService.NodeAwardPermit prepared =
                ((CompletionFunction) progressFunction).prepare();
        if (prepared == null || !prepared.matches(memberId, topicId, actId, nodeId, instance))
            throw new IllegalStateException("偏好完成准备范围不正确");
        // Preparation already holds team -> route -> game -> member. Recheck the service's own
        // actual RC barrier/permit; its uncached current query also flushes a preflight null receipt.
        com.chengyinhub.business.service.ITopicRouteRuntimeService.NodeAwardPermit permit =
                routeRuntimeService.beginOriginalNodeAward(memberId, topicId, actId, nodeId, instance);
        if (permit == null || !permit.matches(memberId, topicId, actId, nodeId, instance)
                || prepared.isEligible() != permit.isEligible())
            throw new IllegalStateException("偏好本局范围不可读取");
        existing = attemptMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) return existingData(existing, memberId, topicId, actId, nodeId, instance, idempotencyKey, template);

        if (reuseConfirmedTag) {
            // Preserve the original late-read boundary. The actual RC/member check above flushes
            // MyBatis cache; a revoke committed during preparation must be visible here.
            // Existing tag writers do not share this member lock; this is not a new global
            // atomic revoke/consume protocol and does not change their persistence semantics.
            List<PlayerTopicTag> confirmed = tagService.confirmedForConsumer(memberId, topicId,
                    evaluator.consumedTagCodes(template.getPreferenceJson()));
            PlayerTopicTag inherited = null;
            for (PlayerTopicTag tag : confirmed) {
                if (request.getReuseTagCode().equals(tag.getTagCode())) { inherited = tag; break; }
            }
            if (inherited == null) throw new IllegalArgumentException("该标签未确认，请现场完成替代题");
            evaluation = evaluator.evaluateInherited(template.getPreferenceJson(),
                    inherited.getTagCode(), inherited.getTagValue());
        }

        Map<String, Object> stored = new LinkedHashMap<String, Object>();
        stored.put("evaluation", evaluation);
        stored.put("choices", request.getChoices());
        stored.put("nodeId", nodeId);
        stored.put(RECEIPT_SCOPE, receiptScope(memberId, topicId, actId, nodeId, instance, idempotencyKey));
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
            if (attemptMapper.insertPlayAttemptLog(attempt) != 1) throw new IllegalStateException("偏好结果保存失败");
        } catch (DuplicateKeyException e) {
            existing = attemptMapper.selectByIdempotencyKey(idempotencyKey);
            if (existing == null) throw e;
            return existingData(existing, memberId, topicId, actId, nodeId, instance, idempotencyKey, template);
        }

        PlayerTopicTag pendingTag = null;
        if (StringUtils.isNotBlank(evaluation.getTagCode())) {
            pendingTag = tagService.upsertPending(memberId, topicId,
                    evaluation.getTagCode(), evaluation.getTagValue(), nodeId);
        }
        stored.put("pendingTag", pendingTag);
        stored.put("tagDisclosure", disclosure(template.getPreferenceJson()));
        stored.put("firstTime", true);
        routeRuntimeService.assertCurrentPlayInstance(memberId, topicId, actId, instance);
        stored.remove(RECEIPT_SCOPE);
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
            Long actId, Long nodeId, String instance, String key, CmsMemberTemplate template) {
        if (!java.util.Objects.equals(memberId, existing.getMemberId())
                || !java.util.Objects.equals(topicId, existing.getTopicId())
                || !java.util.Objects.equals(actId, existing.getActivityId())
                || !java.util.Objects.equals(nodeId, existing.getNodeId())
                || !Integer.valueOf(ValidationMethod.PREFERENCE_GROUP).equals(existing.getAttemptType())
                || !Integer.valueOf(1).equals(existing.getResult())
                || !key.equals(existing.getIdempotencyKey())) throw new IllegalStateException("偏好结果范围不可读取");
        Map<String, Object> data;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.convertValue(JSON.readTree(existing.getResultJson()), Map.class);
            data = new LinkedHashMap<String, Object>(parsed);
            JsonNode storedScope = JSON.valueToTree(data.get(RECEIPT_SCOPE));
            if ((data.containsKey(RECEIPT_SCOPE) || !"default".equals(instance))
                    && !JSON.readTree(JSON.writeValueAsString(receiptScope(memberId, topicId, actId, nodeId, instance, key))).equals(storedScope))
                throw new IllegalStateException("偏好结果本局证据不可读取");
            data.remove(RECEIPT_SCOPE);
        } catch (Exception e) {
            throw new IllegalStateException("已存偏好结果不可解析", e);
        }
        List<PlayerTopicTag> pending = tagService.pendingForNode(memberId, topicId, nodeId);
        data.put("pendingTag", pending == null || pending.isEmpty() ? null : pending.get(0));
        data.put("tagDisclosure", disclosure(template.getPreferenceJson()));
        data.put("firstTime", false);
        routeRuntimeService.assertCurrentPlayInstance(memberId, topicId, actId, instance);
        return data;
    }

    private String preferenceKey(Long member, Long topic, Long activity, Long node, String instance) {
        if ("default".equals(instance)) return "preference:" + member + ":" + topic + ":" + activity + ":" + node;
        try {
            String canonical = JSON.writeValueAsString(java.util.Arrays.asList("preference-receipt-v1", member, topic, activity, node, instance));
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("pref-v1:");
            // Bounded to 64 characters, within the existing VARCHAR(128) receipt column.
            for (int i = 0; i < 28; i++) key.append(String.format("%02x", hash[i] & 0xff));
            return key.toString();
        } catch (Exception impossible) { throw new IllegalStateException("偏好本局标识不可生成", impossible); }
    }

    private Map<String,Object> receiptScope(Long member, Long topic, Long activity, Long node, String instance, String key) {
        Map<String,Object> scope = new LinkedHashMap<>();scope.put("schemaVersion",1);
        scope.put("memberId",member);scope.put("topicId",topic);scope.put("activityId",activity);
        scope.put("nodeId",node);scope.put("playInstanceId",instance);scope.put("idempotencyKey",key);return scope;
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
