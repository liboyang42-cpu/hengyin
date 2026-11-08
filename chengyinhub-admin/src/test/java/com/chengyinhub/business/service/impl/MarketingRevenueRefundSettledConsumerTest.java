package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationMapper;
import com.chengyinhub.business.service.IMarketingRevenuePostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 收入分配退款 consumer 的失败语义：只有"本域确无分配快照"才允许 no-op。
 *
 * <p>这条被 root 明确纠正过：已确认相关（有分配快照）却拿到坏事实时
 * {@code log + return} 会把该冲销的冲销吞掉 ⇒ 静默漏账。必须抛错让整笔退款回调回滚重投。
 */
class MarketingRevenueRefundSettledConsumerTest {

    private static final long REGISTRATION_ID = 900L;
    private static final long REFUND_APPLICATION_ID = 41L;

    private IMarketingRevenuePostingService postingService;
    private MarketingRevenueAllocationMapper allocationMapper;
    private MarketingRevenueRefundSettledConsumer consumer;

    @BeforeEach
    void setUp() {
        postingService = mock(IMarketingRevenuePostingService.class);
        allocationMapper = mock(MarketingRevenueAllocationMapper.class);
        consumer = new MarketingRevenueRefundSettledConsumer(postingService, allocationMapper);
    }

    @Test
    void unrelatedOrderWithNoAccrualIntentIsTheOnlyAllowedNoOp() {
        when(allocationMapper.selectByOrder(any(), anyLong())).thenReturn(null);
        when(postingService.hasAccrualTask(anyLong())).thenReturn(false);

        assertDoesNotThrow(() -> consumer.onRefundSettled(fact("CNY", 500L)));

        verify(postingService, never()).enqueueReversal(anyLong(), anyLong());
    }

    @Test
    void refundArrivingBeforeTheAccrualIsStillEnqueuedSoItCannotBeSilentlyDropped() {
        // 退款先到：应计还卡在费用未核(FEE_BASIS_MISSING)的持久重试里，分配快照尚未产生。
        // 只要该单存在应计任务，就是"本域相关"，必须入队冲销并由队列以 ALLOCATION_NOT_FOUND 重试。
        when(allocationMapper.selectByOrder(any(), anyLong())).thenReturn(null);
        when(postingService.hasAccrualTask(REGISTRATION_ID)).thenReturn(true);

        consumer.onRefundSettled(fact("CNY", 500L));

        verify(postingService).enqueueReversal(eq(REFUND_APPLICATION_ID), eq(REGISTRATION_ID));
    }

    @Test
    void allocationPresentWithAccrualTaskStillEnqueuesOnce() {
        when(allocationMapper.selectByOrder(any(), anyLong())).thenReturn(allocation());
        when(postingService.hasAccrualTask(REGISTRATION_ID)).thenReturn(true);

        consumer.onRefundSettled(fact("CNY", 500L));

        verify(postingService, times(1)).enqueueReversal(eq(REFUND_APPLICATION_ID), eq(REGISTRATION_ID));
    }

    @Test
    void relatedOrderWithForeignCurrencyFailsLoudlyInsteadOfSilentlySkipping() {
        when(allocationMapper.selectByOrder(any(), anyLong())).thenReturn(allocation());

        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(fact("USD", 500L)));

        verify(postingService, never()).enqueueReversal(anyLong(), anyLong());
    }

    @Test
    void relatedOrderWithNonPositiveRefundFailsLoudly() {
        when(allocationMapper.selectByOrder(any(), anyLong())).thenReturn(allocation());

        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(fact("CNY", 0L)));

        verify(postingService, never()).enqueueReversal(anyLong(), anyLong());
    }

    @Test
    void relatedOrderWithUsableFactEnqueuesReversalInTheSameTransaction() {
        when(allocationMapper.selectByOrder(any(), anyLong())).thenReturn(allocation());

        consumer.onRefundSettled(fact("CNY", 500L));

        verify(postingService).enqueueReversal(eq(REFUND_APPLICATION_ID), eq(REGISTRATION_ID));
    }

    @Test
    void missingIdentifiersAreIgnoredWithoutTouchingTheDomain() {
        assertDoesNotThrow(() -> consumer.onRefundSettled(null));
        verifyNoInteractions(allocationMapper, postingService);
    }

    // ------------------------------------------------------------------ helpers

    private RefundSettledFact fact(String currency, long refundedFen) {
        return new RefundSettledFact(REGISTRATION_ID, REFUND_APPLICATION_ID, "RF900", "WXREF1",
                refundedFen, 500L, currency, 77L, "a".repeat(64));
    }

    private MarketingRevenueAllocation allocation() {
        MarketingRevenueAllocation allocation = new MarketingRevenueAllocation();
        allocation.setOrderType(Integer.valueOf(MarketingRevenueAllocation.ORDER_REGISTRATION));
        allocation.setOrderId(REGISTRATION_ID);
        return allocation;
    }
}
