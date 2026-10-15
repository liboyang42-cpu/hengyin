package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.impl.MerchantCrmCouponGrantAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantCrmCouponGrantAdapterTest {
    private static final Long OWNER = 9001L;
    private static final Long MERCHANT_A = 11L;
    private static final Long MERCHANT_B = 12L;
    private static final Long MEMBER = 41L;
    private SmsCouponMapper mapper;
    private ISmsCouponHistoryService history;
    private MerchantCrmCouponGrantAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = mock(SmsCouponMapper.class);
        history = mock(ISmsCouponHistoryService.class);
        adapter = new MerchantCrmCouponGrantAdapter();
        ReflectionTestUtils.setField(adapter, "smsCouponMapper", mapper);
        ReflectionTestUtils.setField(adapter, "couponHistoryService", history);
    }

    @Test
    void listAndGrantUseOwnerAndExactMerchantRowTogether() {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(5L);
        coupon.setName("A 店券");
        coupon.setMemberId(OWNER);
        coupon.setMerchantId(MERCHANT_A);
        coupon.setScopeType(1);
        when(mapper.selectOwnedActiveCoupons(OWNER, MERCHANT_A, 50))
                .thenReturn(Collections.singletonList(coupon));
        when(mapper.selectOwnedActiveCoupon(OWNER, MERCHANT_A, 5L)).thenReturn(coupon);
        when(history.grantCouponWithReceipt(MEMBER, coupon)).thenReturn(77L);

        assertEquals(1, adapter.list(OWNER, MERCHANT_A).size());
        assertEquals(77L, adapter.grant(OWNER, MERCHANT_A, MEMBER, 5L));

        verify(mapper).selectOwnedActiveCoupons(OWNER, MERCHANT_A, 50);
        verify(mapper).selectOwnedActiveCoupon(OWNER, MERCHANT_A, 5L);
    }

    @Test
    void nullReceiptIsExplicitNotIssuedFailure() {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(5L);
        when(mapper.selectOwnedActiveCoupon(OWNER, MERCHANT_A, 5L)).thenReturn(coupon);
        when(history.grantCouponWithReceipt(MEMBER, coupon)).thenReturn(null);

        MerchantCrmCouponGrantAdapter.GrantRejectedException error = assertThrows(
                MerchantCrmCouponGrantAdapter.GrantRejectedException.class,
                () -> adapter.grant(OWNER, MERCHANT_A, MEMBER, 5L));

        assertEquals("COUPON_NOT_ISSUED", error.getFailureCode());
        verify(history).grantCouponWithReceipt(MEMBER, coupon);
    }

    @Test
    void exhaustedCouponFilteredBeforeGrantIsStillExplicitNotIssued() {
        when(mapper.selectOwnedActiveCoupon(OWNER, MERCHANT_A, 5L)).thenReturn(null);

        MerchantCrmCouponGrantAdapter.GrantRejectedException error = assertThrows(
                MerchantCrmCouponGrantAdapter.GrantRejectedException.class,
                () -> adapter.grant(OWNER, MERCHANT_A, MEMBER, 5L));

        assertEquals("COUPON_NOT_ISSUED", error.getFailureCode());
        verify(history, never()).grantCouponWithReceipt(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void merchantACannotSelectOrGrantMerchantBCouponUnderSameOwner() {
        when(mapper.selectOwnedActiveCoupon(OWNER, MERCHANT_A, 6L)).thenReturn(null);

        MerchantCrmCouponGrantAdapter.GrantRejectedException error = assertThrows(
                MerchantCrmCouponGrantAdapter.GrantRejectedException.class,
                () -> adapter.grant(OWNER, MERCHANT_A, MEMBER, 6L));

        assertEquals("COUPON_NOT_ISSUED", error.getFailureCode());
        verify(mapper).selectOwnedActiveCoupon(OWNER, MERCHANT_A, 6L);
        verify(mapper, never()).selectOwnedActiveCoupon(OWNER, MERCHANT_B, 6L);
        verify(history, never()).grantCouponWithReceipt(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
