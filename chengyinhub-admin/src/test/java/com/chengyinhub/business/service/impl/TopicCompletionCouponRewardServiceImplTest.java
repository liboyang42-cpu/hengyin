package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.coupon.TopicCompletionCouponHistoryResolving;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicCompletionCouponRewardServiceImplTest
{
    private TopicCompletionCouponRewardServiceImpl service;
    private CmsTopicCompletionCouponRewardMapper rewardMapper;
    private ISmsCouponHistoryService couponHistoryService;
<<<<<<< port
    private SmsCoupon coupon;
=======
    private TopicCompletionCouponHistoryResolving receiptProtocol;
>>>>>>> questify

    @BeforeEach
    void setUp()
    {
        service = new TopicCompletionCouponRewardServiceImpl();
        rewardMapper = mock(CmsTopicCompletionCouponRewardMapper.class);
        couponHistoryService = mock(ISmsCouponHistoryService.class);
<<<<<<< port
        SmsCouponMapper coupons = mock(SmsCouponMapper.class);
        coupon = new SmsCoupon(); coupon.setId(5L);
        when(coupons.selectSmsCouponById(5L)).thenReturn(coupon);
        ReflectionTestUtils.setField(service, "smsCouponMapper", coupons);
=======
        // Unit-only legacy-schema result; real metadata/transaction behavior is covered by the MyBatis fixture.
        receiptProtocol = mock(TopicCompletionCouponHistoryResolving.class);
        when(receiptProtocol.protocolInstalled()).thenReturn(false);
>>>>>>> questify
        ReflectionTestUtils.setField(service, "rewardMapper", rewardMapper);
        ReflectionTestUtils.setField(service, "smsCouponHistoryService", couponHistoryService);
        ReflectionTestUtils.setField(service, "receiptProtocol", receiptProtocol);
    }

    @Test
    void missingProtocolGuardFailsBeforeClaimingSlotOrIssuingCoupon()
    {
        ReflectionTestUtils.setField(service, "receiptProtocol", null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.grantOnce(21L, 8L, 5L));
        assertEquals("Completion receipt protocol guard is unavailable", failure.getMessage());
        verify(rewardMapper, never()).insertIgnore(21L, 8L, 5L);
        verify(couponHistoryService, never()).grantCouponById(8L, 5L);
    }

    @Test
    void grantsCouponOnlyAfterFirstIdempotencySlot()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(1);
        when(couponHistoryService.grantCouponWithReceipt(8L, coupon)).thenReturn(41L);
        when(rewardMapper.bindHistoryId(21L,8L,5L,41L)).thenReturn(1);

        assertTrue(service.grantOnce(21L, 8L, 5L));
        verify(couponHistoryService).grantCouponWithReceipt(8L, coupon);
        verify(rewardMapper).bindHistoryId(21L,8L,5L,41L);
    }

    @Test
    void duplicateCompletionDoesNotIssueAgain()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(0);

        assertFalse(service.grantOnce(21L, 8L, 5L));
        verify(couponHistoryService, never()).grantCouponWithReceipt(8L, coupon);
    }

    @Test
    void couponIssueFailureRaisesToRollbackIdempotencySlot()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(1);
        when(couponHistoryService.grantCouponWithReceipt(8L, coupon)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.grantOnce(21L, 8L, 5L));
    }

    @Test
    void disabledCouponSkipsIssueWithoutFailingCompletionOrClaimingSlot()
    {
        // C-30:奖励券被平台手动失效 → 不发券、不抛(通关/终章核销+应付台账不回滚)、不占领取标记
        when(couponHistoryService.isCouponNotIssuable(5L)).thenReturn(true);

        assertFalse(service.grantOnce(21L, 8L, 5L));
        verify(rewardMapper, never()).insertIgnore(21L, 8L, 5L);
        verify(couponHistoryService, never()).grantCouponWithReceipt(8L, coupon);
    }
}
