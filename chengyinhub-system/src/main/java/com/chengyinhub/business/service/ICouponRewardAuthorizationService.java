package com.chengyinhub.business.service;

/** 奖励配置中的优惠券必须属于当前发布者，防止跨账号引用任意券。 */
public interface ICouponRewardAuthorizationService
{
    /** couponId 为空或不大于 0 表示不发券；其余情况校验未删除且归属 memberId。 */
    void assertOwnedRewardCoupon(Long couponId, Long memberId);
}
