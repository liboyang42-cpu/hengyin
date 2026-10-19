package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
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
    private TopicCompletionCouponHistoryResolving receiptProtocol;

    @BeforeEach
    void setUp()
    {
        service = new TopicCompletionCouponRewardServiceImpl();
        rewardMapper = mock(CmsTopicCompletionCouponRewardMapper.class);
        couponHistoryService = mock(ISmsCouponHistoryService.class);
        // Unit-only legacy-schema result; real metadata/transaction behavior is covered by the MyBatis fixture.
        receiptProtocol = mock(TopicCompletionCouponHistoryResolving.class);
        when(receiptProtocol.protocolInstalled()).thenReturn(false);
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
        when(couponHistoryService.grantCouponById(8L, 5L)).thenReturn(true);

        assertTrue(service.grantOnce(21L, 8L, 5L));
        verify(couponHistoryService).grantCouponById(8L, 5L);
    }

    @Test
    void duplicateCompletionDoesNotIssueAgain()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(0);

        assertFalse(service.grantOnce(21L, 8L, 5L));
        verify(couponHistoryService, never()).grantCouponById(8L, 5L);
    }

    @Test
    void couponIssueFailureRaisesToRollbackIdempotencySlot()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(1);
        when(couponHistoryService.grantCouponById(8L, 5L)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.grantOnce(21L, 8L, 5L));
    }

    @Test
    void disabledCouponSkipsIssueWithoutFailingCompletionOrClaimingSlot()
    {
        // C-30:奖励券被平台手动失效 → 不发券、不抛(通关/终章核销+应付台账不回滚)、不占领取标记
        when(couponHistoryService.isCouponNotIssuable(5L)).thenReturn(true);

        assertFalse(service.grantOnce(21L, 8L, 5L));
        verify(rewardMapper, never()).insertIgnore(21L, 8L, 5L);
        verify(couponHistoryService, never()).grantCouponById(8L, 5L);
    }
}
