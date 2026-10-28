package com.chengyinhub.web.service;

import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 把节点完成事实翻译为主题路线推进；客户端 actionId 与服务端完成回执严格分离。 */
@Service
public class TopicRouteCompletionCoordinator {
    private final ITopicRouteRuntimeService routeRuntime;

    @Autowired
    public TopicRouteCompletionCoordinator(ITopicRouteRuntimeService routeRuntime) {
        this.routeRuntime = routeRuntime;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState advance(Long memberId, Long topicId, Long activityId, Long nodeId,
                                   String routeActionId, Integer expectedVersion, String serverReceiptId,
                                   String advancedOutcomeCode, String trustedOutcomeCode) {
        return advanceOriginal(memberId, topicId, activityId, nodeId, null, routeActionId,
                expectedVersion, serverReceiptId, advancedOutcomeCode, trustedOutcomeCode);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TopicRouteState advanceOriginal(Long memberId, Long topicId, Long activityId, Long nodeId,
            Long originalSessionId, String routeActionId, Integer expectedVersion, String serverReceiptId,
            String advancedOutcomeCode, String trustedOutcomeCode) {
        try {
            TopicRouteState state = originalSessionId == null ? routeRuntime.state(memberId, topicId, activityId) : null;
            // 老线性主题没有局,照旧只回读状态;线性旅程局(sessionId 非空)与分支一样推进本局状态。
            if (state != null && !TopicRouteState.BRANCH_GRAPH.equals(state.getRouteMode())
                    && state.getSessionId() == null) return state;
            if (StringUtils.isBlank(routeActionId) || expectedVersion == null) {
                throw new IllegalArgumentException("路线完成请求缺少 routeActionId 或 expectedRouteVersion");
            }
            if (!isTrustedProgressReceipt(serverReceiptId)) {
                throw new IllegalStateException("服务端节点完成回执不存在或不可信");
            }
            String outcomeCode = StringUtils.isNotBlank(advancedOutcomeCode) ? advancedOutcomeCode
                    : StringUtils.isNotBlank(trustedOutcomeCode) ? trustedOutcomeCode : "COMPLETED";
            String triggerType = StringUtils.isNotBlank(advancedOutcomeCode) ? "ADVANCED_RESULT"
                    : StringUtils.isNotBlank(trustedOutcomeCode) ? "PREFERENCE_RESULT" : "CHOICE";
            NodeOutcome outcome = new NodeOutcome(nodeId, outcomeCode, serverReceiptId);
            return originalSessionId == null
                    ? routeRuntime.advance(memberId, topicId, activityId, expectedVersion, routeActionId, triggerType, outcome)
                    : routeRuntime.advanceOriginal(memberId, topicId, activityId, originalSessionId,
                            expectedVersion, routeActionId, triggerType, outcome);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ServiceException(e.getMessage());
        }
    }

    private boolean isTrustedProgressReceipt(String receiptId) {
        if (StringUtils.isBlank(receiptId) || !receiptId.startsWith("player-node-progress:")) return false;
        String progressId = receiptId.substring("player-node-progress:".length());
        if (progressId.isEmpty()) return false;
        for (int i = 0; i < progressId.length(); i++) {
            if (!Character.isDigit(progressId.charAt(i))) return false;
        }
        return true;
    }
}
