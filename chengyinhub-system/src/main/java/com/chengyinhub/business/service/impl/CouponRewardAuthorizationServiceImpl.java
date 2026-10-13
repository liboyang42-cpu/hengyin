package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.ISmsCouponService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CouponRewardAuthorizationServiceImpl implements ICouponRewardAuthorizationService
{
    @Autowired
    private ISmsCouponService smsCouponService;

    @Override
    public void assertOwnedRewardCoupon(Long couponId, Long memberId)
    {
        if (couponId == null || couponId <= 0) return;
        SmsCoupon coupon = smsCouponService.selectSmsCouponById(couponId);
        if (coupon == null || (coupon.getDelFlag() != null && coupon.getDelFlag() == 2)
                || coupon.getMemberId() == null || !coupon.getMemberId().equals(memberId))
        {
            throw new IllegalArgumentException("优惠券不存在或无权作为奖励使用");
        }
    }
}
