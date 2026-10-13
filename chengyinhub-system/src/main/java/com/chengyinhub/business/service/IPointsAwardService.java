package com.chengyinhub.business.service;

/**
 * 统一积分发放（按事件类型读 cms_points_setting 配额，幂等发放）
 *
 * @author cy
 * @date 2026-06-19
 */
public interface IPointsAwardService
{
    Long EVENT_PUBLISHER_SETTLEMENT = 15L;
    String PUBLISHER_SETTLEMENT_KEY_PREFIX = "publisher-settlement:topic:";

    /**
     * 按事件类型给会员发放积分。
     * - 额度来源：cms_points_setting（type=1 积分规则 + 对应 eventType）的 pointsNum / value。
     * - 幂等：同 (memberId, eventType, eventId) 已有流水则不重复发放。
     *
     * @param memberId  会员ID
     * @param eventType 事件类型（10报名成功 11到店核销 12节点打卡 13通关 等）
     * @param eventId   业务对象ID（活动ID/报名ID/节点ID 等，用于幂等去重）
     * @param reason    流水备注
     * @return 实发积分；0 表示未发放（无规则/规则为0/已发过）
     */
    int award(Long memberId, Long eventType, Long eventId, String reason);

    /**
     * 主题完成现金结算后，给发布者/活动设计者这一统一资格发一次积分。
     * 幂等对象固定为 topicId，规则版本只进流水快照、不进入幂等键。
     *
     * @return 实发积分；同一主题已发过返回 0
     * @throws RuntimeException 规则、会员或积分写入异常时 fail closed
     */
    int awardPublisherSettlement(Long memberId, Long topicId);
}
