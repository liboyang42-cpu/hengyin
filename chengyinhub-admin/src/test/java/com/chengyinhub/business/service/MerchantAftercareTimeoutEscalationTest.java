package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.MerchantAftercareTimeoutMapper;
import com.chengyinhub.business.service.impl.MerchantAftercareServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拍板 2026-09-16 #12:售后服务 24 小时商家未回应 → 自动转平台客服 + 通知玩家。
 *
 * <p>这里钉的是四件事:窗口是 24 小时(不是别的数)、只通知真正转出去的单、
 * 已转过/并发 loser 不重复通知(幂等负控)、通知旁路失败不回滚转出标记。
 */
class MerchantAftercareTimeoutEscalationTest {

    private MerchantAftercareTimeoutMapper mapper;
    private INotifyService notifyService;
    private MerchantAftercareServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MerchantAftercareTimeoutMapper.class);
        notifyService = mock(INotifyService.class);
        service = new MerchantAftercareServiceImpl();
        ReflectionTestUtils.setField(service, "merchantAftercareTimeoutMapper", mapper);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
    }

    private RefundApplication staleRefund(long id, long memberId, String amount) {
        RefundApplication refund = new RefundApplication();
        refund.setId(id);
        refund.setMemberId(memberId);
        refund.setMerchantId(77L);
        refund.setStatus(0);
        refund.setRefundAmount(new BigDecimal(amount));
        return refund;
    }

    @Test
    void escalatesOnlyRefundsThatWereJustMarkedAndNotifiesEachPlayerOnce() {
        RefundApplication first = staleRefund(41L, 9001L, "88.00");
        RefundApplication second = staleRefund(42L, 9002L, "12.50");
        when(mapper.selectStaleMerchantResponse(any(Date.class))).thenReturn(Arrays.asList(first, second));
        when(mapper.markPlatformTakeover(eq(41L), any(Date.class))).thenReturn(1);
        when(mapper.markPlatformTakeover(eq(42L), any(Date.class))).thenReturn(1);
        when(notifyService.notifyAftercarePlatformTakeover(anyLong(), any())).thenReturn(true);

        int escalated = service.escalateStaleMerchantResponses();

        assertEquals(2, escalated);
        verify(mapper).markPlatformTakeover(eq(41L), any(Date.class));
        verify(mapper).markPlatformTakeover(eq(42L), any(Date.class));
        verify(notifyService).notifyAftercarePlatformTakeover(eq(9001L), eq("88.00"));
        verify(notifyService).notifyAftercarePlatformTakeover(eq(9002L), eq("12.50"));

        // 负控①:窗口必须是 24 小时。把 24 改成 1 或 48,这条捕获的 cutoff 就会越界判红。
        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(mapper).selectStaleMerchantResponse(cutoff.capture());
        long ageMs = System.currentTimeMillis() - cutoff.getValue().getTime();
        long expected = 24L * 60 * 60 * 1000;
        org.junit.jupiter.api.Assertions.assertTrue(Math.abs(ageMs - expected) < 60_000,
                "商家响应窗口应为 24 小时,实测 cutoff 距现在 " + ageMs + "ms");
    }

    @Test
    void alreadyTakenOverOrConcurrentLoserIsNotNotifiedAgain() {
        RefundApplication refund = staleRefund(41L, 9001L, "88.00");
        when(mapper.selectStaleMerchantResponse(any(Date.class))).thenReturn(Collections.singletonList(refund));
        // 条件更新 0 行 = 已被并发 worker 转走 / 状态已变 ⇒ 本轮不得再通知(幂等负控)
        when(mapper.markPlatformTakeover(eq(41L), any(Date.class))).thenReturn(0);

        int escalated = service.escalateStaleMerchantResponses();

        assertEquals(0, escalated);
        verify(notifyService, never()).notifyAftercarePlatformTakeover(anyLong(), any());
    }

    @Test
    void emptyScanReturnsZeroWithoutSideEffects() {
        when(mapper.selectStaleMerchantResponse(any(Date.class))).thenReturn(Collections.emptyList());

        assertEquals(0, service.escalateStaleMerchantResponses());
        verify(notifyService, never()).notifyAftercarePlatformTakeover(anyLong(), any());
    }

    @Test
    void notificationFailureDoesNotUndoTakeoverOrBreakTheBatch() {
        RefundApplication first = staleRefund(41L, 9001L, "88.00");
        RefundApplication second = staleRefund(42L, 9002L, "12.50");
        when(mapper.selectStaleMerchantResponse(any(Date.class))).thenReturn(Arrays.asList(first, second));
        when(mapper.markPlatformTakeover(eq(41L), any(Date.class))).thenReturn(1);
        when(mapper.markPlatformTakeover(eq(42L), any(Date.class))).thenReturn(1);
        when(notifyService.notifyAftercarePlatformTakeover(eq(9001L), any()))
                .thenThrow(new RuntimeException("im 通道炸了"));
        when(notifyService.notifyAftercarePlatformTakeover(eq(9002L), any())).thenReturn(true);

        // 通知是旁路:第一条炸不能把整批打红,第二条仍要发出;已提交的转出标记不能回滚
        assertEquals(2, service.escalateStaleMerchantResponses());
        verify(notifyService).notifyAftercarePlatformTakeover(eq(9002L), eq("12.50"));
    }

    @Test
    void scanFailureRaisesSoTheScheduledTaskCanLogItInsteadOfFailingSilently() {
        when(mapper.selectStaleMerchantResponse(any(Date.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(IllegalStateException.class, () -> service.escalateStaleMerchantResponses());
    }

    @Test
    void refundWithoutPlayerOrAmountStillEscalatesAndSkipsInvalidNotification() {
        RefundApplication refund = staleRefund(41L, 0L, "0");
        when(mapper.selectStaleMerchantResponse(any(Date.class))).thenReturn(Collections.singletonList(refund));
        when(mapper.markPlatformTakeover(eq(41L), any(Date.class))).thenReturn(1);

        assertEquals(1, service.escalateStaleMerchantResponses());
        verify(notifyService, never()).notifyAftercarePlatformTakeover(anyLong(), any());
    }
}
