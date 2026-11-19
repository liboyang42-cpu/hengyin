package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.route.TopicRouteState;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** LINEAR 与 BRANCH_GRAPH 共用的节点准入 seam。 */
public class NodeAccessPolicy {

    public Decision evaluate(String routeMode, CmsTopicNode requested, List<CmsTopicNode> topicNodes,
                             Set<Long> completedNodeIds, TopicRouteState routeState) {
        if (requested == null || requested.getId() == null) return Decision.deny("节点不存在");
        boolean belongsToTopic = false;
        if (topicNodes != null) for (CmsTopicNode node : topicNodes) {
            if (node != null && requested.getId().equals(node.getId())
                    && requested.getTopicId() != null && requested.getTopicId().equals(node.getTopicId())) {
                belongsToTopic = true;
                break;
            }
        }
        if (!belongsToTopic) return Decision.deny("节点不属于当前主题");
        Set<Long> completed = completedNodeIds == null ? Collections.<Long>emptySet() : completedNodeIds;
        if (TopicRouteState.BRANCH_GRAPH.equals(routeMode)) {
            if (routeState == null || routeState.getNodeStates() == null) {
                return Decision.deny("路线状态不可用");
            }
            String state = routeState.getNodeStates().get(requested.getId());
            if (TopicRouteState.PLAYABLE.equals(state)) return Decision.allow();
            return Decision.deny(TopicRouteState.HIDDEN.equals(state) ? "该地点尚未发现" : "该地点尚未解锁");
        }

        // topicNodes 已按游玩顺序(章节 sort_id → 节点 sort_id → id)排好:排在本站之前的站都须已完成。
        // 不单比节点 sort_id —— 跨章调过顺序的站会因此被前一章还没走到的站卡住。
        if (topicNodes != null) for (CmsTopicNode node : topicNodes) {
            if (node == null || node.getId() == null) continue;
            if (requested.getId().equals(node.getId())) break;
            if (!completed.contains(node.getId())) return Decision.deny("请先完成上一站");
        }
        if (requested.getUnlockAfterNodeId() != null && requested.getUnlockAfterNodeId() > 0L
                && !completed.contains(requested.getUnlockAfterNodeId())) {
            return Decision.deny("本关尚未解锁,先完成前置关卡");
        }
        return Decision.allow();
    }

    public static final class Decision {
        private final boolean allowed;
        private final String reason;
        private Decision(boolean allowed, String reason) { this.allowed = allowed; this.reason = reason; }
        public static Decision allow() { return new Decision(true, null); }
        public static Decision deny(String reason) { return new Decision(false, reason); }
        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }
}
