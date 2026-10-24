package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.CompletedRouteFactMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Date;

/**
 * 登录会员完成列表/节点进度的原来源。memberId 必须取自调用入口的已认证主体。
 * 仅供候选发现；不授予主题读取、奖励、报名、核销或结算权限。
 */
@Service
public class CompletedRouteFactReader {
    private final CompletedRouteFactMapper mapper;

    public CompletedRouteFactReader(CompletedRouteFactMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<TopicRouteSession> findCurrentCompletedRoutes(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("完成事实查询必须指定已登录会员");
        }
        return mapper.selectCurrentCompletedRoutes(memberId);
    }

    /**
     * 本人非经济进度展示的有界批量读口。调用方仍须执行原主题/活动可读性校验。
     * 每项可指定原instance；null仅表示与旧PG读取一致的当前局，不扩大为历史任意局。
     * 仅解释LEGACY_PROGRESS；缺PG不能推出Game/Generic未完成，更不能据此派奖或核销。
     */
    @Transactional(readOnly = true)
    public Map<String, NodeProgressFact> findNodeProgressPage(Long authenticatedMemberId,
                                                             List<NodeProgressRequest> requests) {
        if (authenticatedMemberId == null || authenticatedMemberId <= 0) {
            throw new IllegalArgumentException("进度事实查询必须指定已登录会员");
        }
        if (requests == null || requests.size() > 100) {
            throw new IllegalArgumentException("进度事实页必须提供且最多100项");
        }
        Map<String, NodeProgressRequest> inputs = new LinkedHashMap<>();
        for (NodeProgressRequest request : new ArrayList<>(requests)) {
            if (request == null || request.inputId == null || request.inputId.isBlank()
                    || request.inputId.length() > 80 || inputs.containsKey(request.inputId)
                    || !authenticatedMemberId.equals(request.memberId)
                    || request.activityId == null || request.activityId < 0
                    || request.topicId == null || request.topicId <= 0
                    || request.nodeId == null || request.nodeId <= 0
                    || (request.playInstanceId != null
                        && (request.playInstanceId.isBlank() || request.playInstanceId.length() > 64))) {
                throw new IllegalArgumentException("进度事实页身份或scope不完整");
            }
            inputs.put(request.inputId, request);
        }
        if (inputs.isEmpty()) return Collections.emptyMap();
        List<NodeProgressRow> rows = mapper.selectNodeProgressPage(authenticatedMemberId,
                List.copyOf(inputs.values()));
        if (rows == null) throw new IllegalStateException("进度事实页缺少查询结果");
        Map<String, NodeProgressFact> found = new LinkedHashMap<>();
        for (NodeProgressRow row : rows) {
            NodeProgressRequest request = row == null ? null : inputs.get(row.getInputId());
            if (request == null || found.containsKey(row.getInputId())
                    || !request.memberId.equals(row.getRequestedMemberId())
                    || !request.activityId.equals(row.getRequestedActivityId())
                    || !request.topicId.equals(row.getRequestedTopicId())
                    || !request.nodeId.equals(row.getRequestedNodeId())
                    || row.getResolvedPlayInstanceId() == null || row.getResolvedPlayInstanceId().isBlank()
                    || (request.playInstanceId != null
                        && !request.playInstanceId.equals(row.getResolvedPlayInstanceId()))) {
                throw new IllegalStateException("进度事实页返回了冲突或越域身份");
            }
            if (row.getId() != null && (!request.memberId.equals(row.getMemberId())
                    || !request.activityId.equals(row.getActivityId()) || !request.topicId.equals(row.getTopicId())
                    || !request.nodeId.equals(row.getNodeId())
                    || !row.getResolvedPlayInstanceId().equals(row.getPlayInstanceId())
                    || !Integer.valueOf(0).equals(row.getDelFlag()))) {
                throw new IllegalStateException("进度来源与请求scope不一致");
            }
            if (row.getId() == null && (row.getMemberId() != null || row.getStatus() != null
                    || row.getCheckinTime() != null || row.getPicUrl() != null)) {
                throw new IllegalStateException("进度来源不完整");
            }
            found.put(request.inputId, new NodeProgressFact(request, row));
        }
        if (!found.keySet().equals(inputs.keySet())) {
            throw new IllegalStateException("进度事实页遗漏输入身份");
        }
        Map<String, NodeProgressFact> ordered = new LinkedHashMap<>();
        inputs.keySet().forEach(id -> ordered.put(id, found.get(id)));
        return Collections.unmodifiableMap(ordered);
    }

    public enum Observation { OBSERVED, NOT_OBSERVED, UNKNOWN }

    /** Immutable input identity; a null instance means CURRENT, never all instances. */
    public static final class NodeProgressRequest {
        private final String inputId;
        private final Long memberId, activityId, topicId, nodeId;
        private final String playInstanceId;
        public NodeProgressRequest(String inputId, Long memberId, Long activityId, Long topicId,
                                   Long nodeId, String playInstanceId) {
            this.inputId = inputId; this.memberId = memberId; this.activityId = activityId;
            this.topicId = topicId; this.nodeId = nodeId; this.playInstanceId = playInstanceId;
        }
        public String getInputId() { return inputId; }
        public Long getMemberId() { return memberId; }
        public Long getActivityId() { return activityId; }
        public Long getTopicId() { return topicId; }
        public Long getNodeId() { return nodeId; }
        public String getPlayInstanceId() { return playInstanceId; }
    }

    /** Mapper projection: request identity remains present when the PG LEFT JOIN has no source. */
    public static class NodeProgressRow extends PlayerNodeProgress {
        private String inputId, resolvedPlayInstanceId;
        private Long requestedMemberId, requestedActivityId, requestedTopicId, requestedNodeId;
        private Long routeSessionId, frozenConfigVersion;
        public String getInputId() { return inputId; }
        public void setInputId(String v) { inputId = v; }
        public String getResolvedPlayInstanceId() { return resolvedPlayInstanceId; }
        public void setResolvedPlayInstanceId(String v) { resolvedPlayInstanceId = v; }
        public Long getRequestedMemberId() { return requestedMemberId; }
        public void setRequestedMemberId(Long v) { requestedMemberId = v; }
        public Long getRequestedActivityId() { return requestedActivityId; }
        public void setRequestedActivityId(Long v) { requestedActivityId = v; }
        public Long getRequestedTopicId() { return requestedTopicId; }
        public void setRequestedTopicId(Long v) { requestedTopicId = v; }
        public Long getRequestedNodeId() { return requestedNodeId; }
        public void setRequestedNodeId(Long v) { requestedNodeId = v; }
        public Long getRouteSessionId() { return routeSessionId; }
        public void setRouteSessionId(Long v) { routeSessionId = v; }
        public Long getFrozenConfigVersion() { return frozenConfigVersion; }
        public void setFrozenConfigVersion(Long v) { frozenConfigVersion = v; }
    }

    public static final class NodeProgressFact {
        private final NodeProgressRequest request;
        private final String resolvedPlayInstanceId;
        private final Long routeSessionId, frozenConfigVersion;
        private final PlayerNodeProgress source;
        private NodeProgressFact(NodeProgressRequest request, NodeProgressRow row) {
            this.request = request; this.resolvedPlayInstanceId = row.getResolvedPlayInstanceId();
            this.routeSessionId = row.getRouteSessionId(); this.frozenConfigVersion = row.getFrozenConfigVersion();
            this.source = row.getId() == null ? null : copy(row);
        }
        public NodeProgressRequest getRequest() { return request; }
        public String getResolvedPlayInstanceId() { return resolvedPlayInstanceId; }
        public Long getRouteSessionId() { return routeSessionId; }
        public Long getFrozenConfigVersion() { return frozenConfigVersion; }
        public String getSourceKind() { return "LEGACY_PROGRESS"; }
        public String getSubjectKind() { return "MEMBER"; }
        public boolean hasSource() { return source != null; }
        public Observation getArrival() {
            return source == null ? Observation.UNKNOWN : source.getCheckinTime() == null
                    ? Observation.NOT_OBSERVED : Observation.OBSERVED;
        }
        public Observation getNodeCompleted() {
            if (source == null || source.getStatus() == null) return Observation.UNKNOWN;
            return source.getStatus() == 1 ? Observation.OBSERVED : source.getStatus() == 0
                    ? Observation.NOT_OBSERVED : Observation.UNKNOWN;
        }
        /** status1/照片审核/REVEALED都不能替代原验证回执。此来源不证明TASK_VERIFIED。 */
        public Observation getTaskVerified() { return Observation.UNKNOWN; }
        public PlayerNodeProgress getSourceProgress() { return source == null ? null : copy(source); }
    }

    private static PlayerNodeProgress copy(PlayerNodeProgress source) {
        PlayerNodeProgress row = new PlayerNodeProgress();
        row.setId(source.getId()); row.setMemberId(source.getMemberId()); row.setActivityId(source.getActivityId());
        row.setTopicId(source.getTopicId()); row.setNodeId(source.getNodeId()); row.setPlayInstanceId(source.getPlayInstanceId());
        row.setStatus(source.getStatus()); row.setCheckinTime(copyDate(source.getCheckinTime()));
        row.setPicUrl(source.getPicUrl()); row.setReviewStatus(source.getReviewStatus());
        row.setLongitude(source.getLongitude()); row.setLatitude(source.getLatitude());
        row.setPuzzleScore(source.getPuzzleScore()); row.setCompletionMode(source.getCompletionMode());
        row.setDelFlag(source.getDelFlag()); row.setCreateTime(copyDate(source.getCreateTime()));
        return row;
    }
    private static Date copyDate(Date value) { return value == null ? null : new Date(value.getTime()); }
}
