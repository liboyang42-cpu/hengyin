package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * F0575 平台兑换记录(只读)。
 *
 * <p>三类来源各查各的表、各带各的原始业务 ID,绝不按名称合并:
 * 优惠券领取/核销 = sms_coupon_history;积分商城订单 = oms_order(order_type=2,非商业化订单);
 * 礼物消费 = ums_crowdfunding。
 */
public interface PlatformRedemptionRecordMapper
{
    List<Map<String, Object>> selectCouponClaims(@Param("memberId") Long memberId,
                                                 @Param("couponId") Long couponId,
                                                 @Param("useStatus") Integer useStatus);

    List<Map<String, Object>> selectPointsMallOrders(@Param("memberId") Long memberId,
                                                     @Param("orderSn") String orderSn,
                                                     @Param("status") Long status);

    List<Map<String, Object>> selectGiftConsumptions(@Param("memberId") Long memberId,
                                                     @Param("giftId") Long giftId,
                                                     @Param("activityId") Long activityId);
}
