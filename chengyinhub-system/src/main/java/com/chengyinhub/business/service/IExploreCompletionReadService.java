package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ExploreCompletionVO;

/**
 * 批5 · 探店日完局面**只读**服务(图鉴 / 通关奖励到账明细 / J·R90 入口)。
 *
 * <p>纯读:不写任何表、不发任何奖励、不碰资金。奖励发放的唯一入口仍是核销侧
 * {@code TopicCompletionAwardService.grantOnce};本服务只把**已落库的到账事实**读出来给玩家看。
 * 「读面顺手补发」是本仓最贵的一类错误(会绕开幂等闸),这里显式不做。
 */
public interface IExploreCompletionReadService {

    /**
     * @param registrationId 已通过归属校验的报名单
     * @param memberId       当前登录会员(调用方必须已确认 == registration.memberId)
     * @param topicId        该单所属的期;为 null 时返回一个 completed=false 的空壳,不猜期
     * @param activityId     该期的活动壳(registration.ownerId);用于与主题侧的 club 事实互校,
     *                       口径与 {@code CmsRegistrationMapper.freezeTicketSourceOnPayment} 一致
     */
    ExploreCompletionVO read(Long registrationId, Long memberId, Long topicId, Long activityId);
}
