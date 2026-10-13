package com.chengyinhub.business.service;

/** 主题首次通关的优惠券发放。 */
public interface ITopicCompletionCouponRewardService
{
    /** @return true=本次实际发放，false=此前已发过 */
    boolean grantOnce(Long topicId, Long memberId, Long couponId);
}
