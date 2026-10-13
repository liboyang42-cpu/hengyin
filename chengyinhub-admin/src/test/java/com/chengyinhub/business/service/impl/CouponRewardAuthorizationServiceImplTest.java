package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ISmsCouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponRewardAuthorizationServiceImplTest
{
    private CouponRewardAuthorizationServiceImpl service;
    private ISmsCouponService smsCouponService;

    @BeforeEach
    void setUp()
    {
        service = new CouponRewardAuthorizationServiceImpl();
        smsCouponService = mock(ISmsCouponService.class);
        ReflectionTestUtils.setField(service, "smsCouponService", smsCouponService);
    }

    @Test
    void acceptsOwnActiveCouponAndNoCoupon()
    {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setMemberId(9L);
        coupon.setDelFlag(0);
        when(smsCouponService.selectSmsCouponById(11L)).thenReturn(coupon);

        assertDoesNotThrow(() -> service.assertOwnedRewardCoupon(null, 9L));
        assertDoesNotThrow(() -> service.assertOwnedRewardCoupon(11L, 9L));
    }

    @Test
    void rejectsForeignOrDeletedCoupon()
    {
        SmsCoupon foreignCoupon = new SmsCoupon();
        foreignCoupon.setMemberId(10L);
        foreignCoupon.setDelFlag(0);
        SmsCoupon deletedCoupon = new SmsCoupon();
        deletedCoupon.setMemberId(9L);
        deletedCoupon.setDelFlag(2);
        when(smsCouponService.selectSmsCouponById(12L)).thenReturn(foreignCoupon);
        when(smsCouponService.selectSmsCouponById(13L)).thenReturn(deletedCoupon);

        assertThrows(IllegalArgumentException.class, () -> service.assertOwnedRewardCoupon(12L, 9L));
        assertThrows(IllegalArgumentException.class, () -> service.assertOwnedRewardCoupon(13L, 9L));
    }
}
