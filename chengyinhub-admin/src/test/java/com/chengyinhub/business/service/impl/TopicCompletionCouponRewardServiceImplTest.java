package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    private com.chengyinhub.business.mapper.SmsCouponMapper couponMapper;
    private com.chengyinhub.business.domain.SmsCoupon coupon;

    @BeforeEach
    void setUp()
    {
        service = new TopicCompletionCouponRewardServiceImpl();
        rewardMapper = mock(CmsTopicCompletionCouponRewardMapper.class);
        couponHistoryService = mock(ISmsCouponHistoryService.class);
        couponMapper = mock(com.chengyinhub.business.mapper.SmsCouponMapper.class);
        coupon = new com.chengyinhub.business.domain.SmsCoupon();
        coupon.setId(5L);
        when(couponMapper.selectSmsCouponById(5L)).thenReturn(coupon);
        ReflectionTestUtils.setField(service, "rewardMapper", rewardMapper);
        ReflectionTestUtils.setField(service, "smsCouponMapper", couponMapper);
        ReflectionTestUtils.setField(service, "smsCouponHistoryService", couponHistoryService);
    }

    @Test
    void grantsCouponOnlyAfterFirstIdempotencySlot()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(1);
        when(couponHistoryService.grantCouponWithReceipt(8L, coupon)).thenReturn(41L);
        when(rewardMapper.bindHistoryId(21L, 8L, 5L, 41L)).thenReturn(1);

        assertTrue(service.grantOnce(21L, 8L, 5L));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(rewardMapper, couponHistoryService);
        order.verify(rewardMapper).insertIgnore(21L, 8L, 5L);
        order.verify(couponHistoryService).grantCouponWithReceipt(8L, coupon);
        order.verify(rewardMapper).bindHistoryId(21L, 8L, 5L, 41L);
        verify(couponHistoryService, never()).grantCouponById(8L, 5L);
    }

    @Test
    void duplicateCompletionDoesNotIssueAgain()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(0);
        assertFalse(service.grantOnce(21L, 8L, 5L));
        verify(couponHistoryService, never()).grantCouponWithReceipt(8L, coupon);
        verify(rewardMapper, never()).bindHistoryId(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void couponIssueFailureRaisesToRollbackIdempotencySlot()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(1);
        for (Long invalid : new Long[] {null, 0L, -1L}) {
            when(couponHistoryService.grantCouponWithReceipt(8L, coupon)).thenReturn(invalid);
            assertThrows(IllegalStateException.class, () -> service.grantOnce(21L, 8L, 5L));
        }
    }

    @Test
    void receiptBindingMustAffectExactlyOneGrant()
    {
        when(rewardMapper.insertIgnore(21L, 8L, 5L)).thenReturn(1);
        when(couponHistoryService.grantCouponWithReceipt(8L, coupon)).thenReturn(41L);
        for (int rows : new int[] {0, 2}) {
            when(rewardMapper.bindHistoryId(21L, 8L, 5L, 41L)).thenReturn(rows);
            assertThrows(IllegalStateException.class, () -> service.grantOnce(21L, 8L, 5L));
        }
    }

    @Test
    void disabledCouponSkipsIssueWithoutFailingCompletionOrClaimingSlot()
    {
        when(couponHistoryService.isCouponNotIssuable(5L)).thenReturn(true);
        assertFalse(service.grantOnce(21L, 8L, 5L));
        verify(rewardMapper, never()).insertIgnore(21L, 8L, 5L);
        verify(couponHistoryService, never()).grantCouponWithReceipt(8L, coupon);
    }
}
