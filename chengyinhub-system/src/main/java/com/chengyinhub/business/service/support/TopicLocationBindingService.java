package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.TopicLocationBindingCommand;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.util.TopicEditWindow;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 目标城市的地点绑定写/读。
 *
 * <p>只改 {@code cms_topic.content_contract_json}；坐标与地址仍以 {@link CmsTopicNode} 为唯一真源。
 *
 * <p><b>写闸复用现有作者编辑口径，不另立一套</b>：
 * <ol>
 *   <li>归属：登录主体 →（scope=MERCHANT 时）{@code PROJECT_MANAGE} 的 Owner → 必须等于 {@code cms_topic.member_id}；</li>
 *   <li>编辑窗口：{@link TopicEditWindow#resolve} 必须是 {@link TopicEditWindow.Scope#FULL}
 *       （草稿/待审/驳回）。{@code WHITELIST}（已过审未开始）与 {@code SELLING_FROZEN} 一律拒 ——
 *       绑定结构属于内容结构，不是已售卖主题的文案白名单可改项；</li>
 *   <li>父行 fence：{@link TopicContentWriteGuard#lockTopicContent}，与章节/节点/路线写者同一把锁。</li>
 * </ol>
 *
 * <p>服务端只做结构确认（节点属于本主题、有地址/坐标、城市一致），
 * {@code verificationStatus} 恒为 {@code BOUND_UNVERIFIED} —— 本仓没有现场核验服务，不能伪造“已核验”。
 */
@Service
public class TopicLocationBindingService {

    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    @Autowired
    private TopicContentWriteGuard contentWriteGuard;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bind(TopicLocationBindingCommand command, Long ownerMemberId,
                                    Long principalMemberId) {
        if (command == null || command.getTopicId() == null || command.getTopicId() <= 0L) {
            throw new ServiceException("缺少主题ID", 400);
        }
        Long topicId = command.getTopicId();
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        requireOwnedEditable(topic, ownerMemberId);
        // 父行 fence 是硬前提:没有它就等于绕过 revision 基线。装配缺失时 fail-closed,不降级成无锁写。
        if (contentWriteGuard == null) {
            throw new IllegalStateException("地点绑定缺少内容写闸装配，拒绝无锁写入");
        }
        contentWriteGuard.lockTopicContent(topicId);
        topic = cmsTopicMapper.selectCmsTopicById(topicId);
        requireOwnedEditable(topic, ownerMemberId);

        String slotKey = command.getSlotKey();
        if (findSlot(TopicLocationContract.view(topic.getContentContractJson()), slotKey) == null) {
            throw new ServiceException("地点槽不存在", 404);
        }
        if (command.getNodeId() != null) {
            requireBindableNode(topicId, command.getNodeId(), slotKey, topic.getContentContractJson());
            for (Long fallback : command.getApprovedFallbackNodeIds()) {
                if (fallback == null) continue;
                if (fallback.equals(command.getNodeId())) throw new ServiceException("备用点不能与主地点相同", 400);
                requireNodeInTopic(topicId, fallback, "备用地点");
            }
        }

        String updated = TopicLocationContract.applyBinding(topic.getContentContractJson(), slotKey,
                command.getNodeId(), command.getApprovedFallbackNodeIds(), command.getOpeningHours(),
                command.getObservationNote(), command.getReferenceMediaIds(), command.getExpectedRevision(),
                topic.getCityCode(), principalMemberId, DateUtils.getNowDate());

        // 唯一写口：owner 在 SQL 里再判一次（不依赖上面那次先读）。0 行 = 主题已变/不归此人。
        int rows = cmsTopicMapper.updateTopicLocationContract(topicId, ownerMemberId, updated,
                "binding:" + principalMemberId, DateUtils.getNowDate());
        if (rows != 1) {
            throw new ServiceException("主题不存在或无权修改该主题的地点绑定", 403);
        }
        CmsTopic readBack = cmsTopicMapper.selectCmsTopicById(topicId);
        if (readBack == null
                || !TopicLocationContract.same(updated, readBack.getContentContractJson())) {
            throw new IllegalStateException("地点绑定回读不一致");
        }
        return view(readBack);
    }

    /**
     * qx-c1 #5(a) §23.1:作者填写的地点国家 {@code contentContract.placeCountryCode}。
     * 与地点绑定同一写口(owner+编辑窗口+父行 fence+SQL owner 条件+回读);ISO 3166-1 alpha-2,
     * 空串 = 清除(回到未填写);从不默认、不从定位推断。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setPlaceCountry(Long topicId, String placeCountryCode, Long ownerMemberId,
                                               Long principalMemberId) {
        if (topicId == null || topicId <= 0L) throw new ServiceException("缺少主题ID", 400);
        String code;
        String raw = placeCountryCode == null ? "" : placeCountryCode.trim();
        if (!raw.isEmpty() && !raw.matches("[A-Z]{2}")) {
            throw new ServiceException("国家码须为两位大写字母（ISO 3166-1），或留空", 400);
        }
        try {
            code = TopicLocationContract.isoCountry(placeCountryCode, TopicLocationContract.KEY_PLACE_COUNTRY_CODE);
        } catch (IllegalArgumentException invalid) {
            throw new ServiceException("国家码须为两位大写字母（ISO 3166-1），或留空", 400);
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        requireOwnedEditable(topic, ownerMemberId);
        if (contentWriteGuard == null) {
            throw new IllegalStateException("地点合同缺少内容写闸装配，拒绝无锁写入");
        }
        contentWriteGuard.lockTopicContent(topicId);
        topic = cmsTopicMapper.selectCmsTopicById(topicId);
        requireOwnedEditable(topic, ownerMemberId);
        ObjectNode contract = StringUtils.isBlank(topic.getContentContractJson())
                ? TopicLocationContract.empty() : TopicLocationContract.parse(topic.getContentContractJson());
        if (code == null) contract.remove(TopicLocationContract.KEY_PLACE_COUNTRY_CODE);
        else contract.put(TopicLocationContract.KEY_PLACE_COUNTRY_CODE, code);
        String updated = contract.toString();
        int rows = cmsTopicMapper.updateTopicLocationContract(topicId, ownerMemberId, updated,
                "place-country:" + principalMemberId, DateUtils.getNowDate());
        if (rows != 1) throw new ServiceException("主题不存在或无权修改该主题的地点合同", 403);
        CmsTopic readBack = cmsTopicMapper.selectCmsTopicById(topicId);
        if (readBack == null || !TopicLocationContract.same(updated, readBack.getContentContractJson())
                || !java.util.Objects.equals(code, TopicLocationContract.placeCountryCode(readBack.getContentContractJson()))) {
            throw new IllegalStateException("地点合同回读不一致");
        }
        return view(readBack);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(Long topicId, Long ownerMemberId) {
        if (topicId == null || topicId <= 0L) throw new ServiceException("缺少主题ID", 400);
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        requireOwned(topic, ownerMemberId);
        return view(topic);
    }

    private Map<String, Object> view(CmsTopic topic) {
        ObjectNode contract = TopicLocationContract.view(topic.getContentContractJson());
        Map<String, Object> summary = TopicLocationContract.summary(topic.getContentContractJson());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("topicId", String.valueOf(topic.getId()));
        data.put("cityCode", topic.getCityCode());
        data.put("contract", contract);
        data.put("bindingIssues", TopicLocationContract.bindingIssues(
                topic.getContentContractJson(), topic.getCityCode()));
        data.put("sourceTemplateTopicId", summary.get("sourceTemplateTopicId"));
        data.put("sourceConfigVersion", summary.get("sourceConfigVersion"));
        data.put("sourceContentRevisionId", summary.get("sourceContentRevisionId"));
        data.put("adoption", contract.path("adoption").isMissingNode() ? null : contract.path("adoption"));
        data.put("placeCountryCode", TopicLocationContract.placeCountryCode(topic.getContentContractJson()));
        return data;
    }

    private void requireOwned(CmsTopic topic, Long ownerMemberId) {
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() == 2)) {
            throw new ServiceException("主题不存在", 404);
        }
        if (ownerMemberId == null || ownerMemberId <= 0L || !Objects.equals(topic.getMemberId(), ownerMemberId)) {
            throw new ServiceException("无权操作该主题的地点绑定", 403);
        }
    }

    /** 归属 + 编辑窗口(FULL)。绑定是内容结构，已过审未开始也是拒，不是文案白名单。 */
    private void requireOwnedEditable(CmsTopic topic, Long ownerMemberId) {
        requireOwned(topic, ownerMemberId);
        TopicEditWindow.Scope scope;
        try {
            scope = TopicEditWindow.resolve(topic, cmsTopicMapper.selectTopicIsDraft(topic.getId()), new Date());
        } catch (IllegalStateException e) {
            throw new ServiceException(e.getMessage(), 403);
        }
        if (scope == TopicEditWindow.Scope.SELLING_FROZEN) {
            throw new ServiceException("主题已进入售卖，地点绑定结构不能修改", 403);
        }
        if (scope != TopicEditWindow.Scope.FULL) {
            throw new ServiceException("主题已过审，地点结构只能在草稿/待审/驳回时修改", 403);
        }
    }

    private ObjectNode findSlot(ObjectNode contract, String slotKey) {
        if (StringUtils.isBlank(slotKey)) throw new ServiceException("缺少地点槽标识", 400);
        for (JsonNode slot : contract.path("locationSlots")) {
            if (slotKey.equals(slot.path("slotKey").asText())) return (ObjectNode) slot;
        }
        return null;
    }

    private void requireBindableNode(Long topicId, Long nodeId, String slotKey, String contractJson) {
        CmsTopicNode node = requireNodeInTopic(topicId, nodeId, "地点");
        ObjectNode slot = findSlot(TopicLocationContract.view(contractJson), slotKey);
        String purpose = slot == null ? "" : slot.path("purpose").asText("");
        if ("SPACE".equals(purpose) || "EVIDENCE".equals(purpose)) {
            boolean realPlace = StringUtils.isNotBlank(node.getAddress())
                    || (StringUtils.isNotBlank(node.getLongitude()) && StringUtils.isNotBlank(node.getLatitude()));
            if (!realPlace) {
                throw new ServiceException("该地点槽需要真实地点，所选节点没有地址或坐标", 400);
            }
        }
    }

    private CmsTopicNode requireNodeInTopic(Long topicId, Long nodeId, String field) {
        CmsTopicNode node = nodeId == null ? null : cmsTopicNodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !Objects.equals(node.getTopicId(), topicId)) {
            throw new ServiceException(field + "必须来自本主题", 400);
        }
        return node;
    }

    /** 供发布检查复用的只读缺口查询；不写库、不创建第二套 readiness。 */
    public List<Map<String, Object>> bindingIssues(Long topicId) {
        if (topicId == null || topicId <= 0L) return new ArrayList<Map<String, Object>>();
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null) return new ArrayList<Map<String, Object>>();
        return TopicLocationContract.bindingIssues(topic.getContentContractJson(), topic.getCityCode());
    }

    /** 采用/回读用的纯函数入口（不触库）。 */
    public static Set<String> pendingRebindSlots(String contractJson) {
        Set<String> keys = new LinkedHashSet<String>();
        for (JsonNode binding : TopicLocationContract.view(contractJson).path("locationBindings")) {
            if (TopicLocationContract.STATUS_REBIND_REQUIRED.equals(
                    binding.path("verificationStatus").asText())) {
                keys.add(binding.path("slotKey").asText());
            }
        }
        return keys;
    }
}
