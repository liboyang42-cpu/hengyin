package com.chengyinhub.business.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface IAdvancedGameRuntimeService {
    /** Server-only original metadata/config of accessible default node games; pure read, no restart. */
    default com.fasterxml.jackson.databind.JsonNode queryDefaultNodeContents(Long memberId, Long activityId, Long topicId) {
        return null;
    }

    Map<String, Object> start(Long memberId, Long activityId, Long topicId, Long nodeId, Long teamId);

    Map<String, Object> act(Long memberId, Long sessionId, Integer expectedVersion,
                            String idempotencyKey, String action, Map<String, Object> payload);

    /**
     * 收尾:把 {@link #act} 判过时挂在回包上的待铸卡真正铸出来,并把这张卡并进回包。
     *
     * <p>必须在事务之外、{@code act} 返回之后由调用方调用:铸卡要并行等抠图与认物最多 4 秒,
     * 放在事务里就是占着库连接等(Spring 要等提交回调跑完才归还连接)。没有待铸卡时原样返回。
     */
    Map<String, Object> finishPendingCardMint(Map<String, Object> view);

    Map<String, Object> state(Long memberId, Long sessionId);

    List<Map<String, Object>> leaderboard(Long memberId, Long activityId, Long topicId, Long nodeId);

    /** 完成节点门禁，并返回终点声明的稳定 outcomeCode；无分支玩法时返回 null。 */
    String completeGate(Long memberId, Long activityId, Long topicId, Long nodeId);

    /**
     * C1 可信边界(选项 A):本节点当前局的完成是否只由设备自报成绩、拍照兜底放行或静态节点码撑着。
     * 为 true 时节点奖励只许探索值与徽章(不发积分、券、掉落)。只读,按 memberId 取本人局。
     */
    default boolean rewardCappedToXpBadge(Long memberId, Long activityId, Long topicId, Long nodeId) {
        return false;
    }

    /**
     * #478-3 主题通关奖同口径封顶:已完成节点里只要有一站按 {@link #rewardCappedToXpBadge} 封顶,
     * 整部主题的通关奖也只发探索值、徽章与通关勋章(不发通关积分、通关券)。只读。
     */
    default boolean anyNodeRewardCappedToXpBadge(Long memberId, Long activityId, Long topicId,
            Collection<Long> completedNodeIds) {
        if (completedNodeIds == null) return false;
        for (Long nodeId : completedNodeIds) {
            if (nodeId != null && rewardCappedToXpBadge(memberId, activityId, topicId, nodeId)) return true;
        }
        return false;
    }

}
