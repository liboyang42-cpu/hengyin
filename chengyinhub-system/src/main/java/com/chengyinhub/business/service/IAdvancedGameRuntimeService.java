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

    Map<String, Object> start(Long memberId, Long activityId, Long topicId, Long nodeId, Long teamId,
            com.chengyinhub.business.service.support.ContentCapabilityRequirements.Declaration declaration);

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

    /** 本人这一站的玩法已过(与会话视图 readyForBase 同一判定)。只读;为 true 时到站收尾不再要现场定位证明。 */
    default boolean solvedForArrival(Long memberId, Long activityId, Long topicId, Long nodeId) {
        return false;
    }

    /** 当前局的拍照/扫码玩法本身已有可信证明，可替代旧拍照/扫码收尾。 */
    default boolean hasStationGameProof(Long memberId, Long activityId, Long topicId, Long nodeId) {
        return false;
    }

    /**
     * 跳过本题(BE-16 L5 / M.7「跳过记 0 分」):本题记 0 分、只留辅助完成痕迹,
     * 不产生答对、到达、扫码、照片、核销或支付任何证明。
     *
     * <p>只有作者在冻结阶梯里配了 {@code SKIP} 层、且该节点不是现实凭证类(扫码/拍照/计步/相册/竞猜)
     * 时才允许。同一 {@code requestId} 重发只回放,不重复落账;本局已结束或已跳过不重复结算。
     *
     * @return 本局会话视图(客户端据此展示「本题记 0 分·辅助完成」),调用方随后按既有完成链
     *         写 0 分与 {@code completion_mode=SKIPPED};本方法不自己写节点进度
     */
    Map<String, Object> skip(Long memberId, Long activityId, Long topicId, Long nodeId, String requestId);

    /**
     * 该节点本局是否已按「跳过·辅助完成」收尾。完成链({@code advanceProgress})据此把
     * 分数固定为 0、完成方式写 {@code SKIPPED}、奖励降为探索值+徽章。只读。
     */
    default boolean skippedAssistedCompletion(Long memberId, Long activityId, Long topicId, Long nodeId) {
        return false;
    }

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

    /**
     * P-23:本人这一站的传感器段是不是被跳过的(读不出数,0 分走完)。完成链据此把进度记成
     * completionMode=SKIPPED、解谜分 0。只读。
     */
    default boolean sensorStationSkipped(Long memberId, Long activityId, Long topicId, Long nodeId) {
        return false;
    }

    /**
     * P-23 带领场队长现场放行:给 memberId 在 nodeId 这一站的传感器段记一笔放行。
     * 放行不等于完成 —— 仍由成员本人在读取失败够次数后点「跳过这一站」走同一条跳过完成口。
     * 调用方须先确认 leaderMemberId 是本场当前队长、memberId 是本场成员。
     */
    default void releaseSensorStation(Long leaderMemberId, Long memberId, Long activityId, Long topicId, Long nodeId) {
        throw new UnsupportedOperationException("不支持现场放行");
    }

    /**
     * P-23:本场这些成员里,谁卡在传感器站上(读取失败够次数、未完成、未被放行)。
     * 返回 memberId → nodeId。给队长的队伍进度用,只读。
     */
    default Map<Long, Long> pendingSensorSkips(Long activityId, Long topicId, Collection<Long> memberIds) {
        return java.util.Collections.emptyMap();
    }

}
