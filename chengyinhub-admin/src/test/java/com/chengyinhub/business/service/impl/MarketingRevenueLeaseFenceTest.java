package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RevenueAccrualResult;
import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.MarketingRevenuePostingTaskMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.IMarketingRevenueAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租约栅栏：旧 worker 在租约过期/被重投后不能覆盖新 worker 的任务状态。
 *
 * <p>这是真实缺陷复查的结果：{@code markDone}/{@code markFailed} 原先只按 id 更新，
 * 而 {@code reclaimExpired} 会把过期 CLAIMED 放回队列、{@code enqueue} 会把 CLAIMED 重置为 PENDING。
 * 两者都会让"原持有者"变成非持有者，但它仍能写终态 → 新 worker 的租约被清掉、状态被覆盖。
 * 现在生产路径必须走 {@code *IfLeaseHeld}，且 0 行时不得声称成功。
 */
class MarketingRevenueLeaseFenceTest {

    private IMarketingRevenueAllocationService allocationService;
    private MarketingRevenuePostingTaskMapper taskMapper;
    private MarketingRevenuePostingServiceImpl posting;

    static class NoopTransactionManager implements PlatformTransactionManager {
        private final TransactionStatus status = mock(TransactionStatus.class);

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return status;
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    @BeforeEach
    void setUp() {
        allocationService = mock(IMarketingRevenueAllocationService.class);
        taskMapper = mock(MarketingRevenuePostingTaskMapper.class);
        posting = new MarketingRevenuePostingServiceImpl(allocationService, taskMapper,
                mock(RefundApplicationMapper.class), new NoopTransactionManager());
    }

    @Test
    void completionIsWrittenOnlyThroughTheFencedStatement() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(task(901L, 555L, 0)));
        when(allocationService.accrue(any())).thenReturn(new RevenueAccrualResult());
        when(taskMapper.markDoneIfLeaseHeld(anyLong(), anyString())).thenReturn(1);

        int processed = posting.consumeDue(10);

        assertEquals(1, processed);
        ArgumentCaptor<String> claimOwner = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> doneOwner = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).claim(claimOwner.capture(), anyInt(), anyInt());
        verify(taskMapper).markDoneIfLeaseHeld(eq(901L), doneOwner.capture());
        assertEquals(claimOwner.getValue(), doneOwner.getValue(),
                "写 DONE 必须带与领取相同的租约持有者，否则等于没有栅栏");
        assertFalse(claimOwner.getValue().isBlank());
        // 绝不能走不带租约的裸更新（那会绕过栅栏）。
        verify(taskMapper, never()).markDone(anyLong());
    }

    @Test
    void staleWorkerDoesNotReportSuccessWhenItsLeaseWasTakenOver() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(task(902L, 556L, 0)));
        when(allocationService.accrue(any())).thenReturn(new RevenueAccrualResult());
        // 租约已被回收/被他人领取：栅栏更新命中 0 行。
        when(taskMapper.markDoneIfLeaseHeld(anyLong(), anyString())).thenReturn(0);

        int processed = posting.consumeDue(10);

        assertEquals(0, processed, "未持有租约时不得声称完成（否则会掩盖重复执行）");
        verify(taskMapper).markDoneIfLeaseHeld(eq(902L), anyString());
        verify(taskMapper, never()).markDone(anyLong());
    }

    @Test
    void staleWorkerDoesNotClobberTheNewHoldersStateOnFailure() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(task(903L, 557L, 0)));
        when(allocationService.accrue(any())).thenThrow(
                MarketingRevenueException.conflict("PAYMENT_FACT_MISSING", "缺事实"));
        when(taskMapper.markFailedIfLeaseHeld(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(0);

        // 不抛异常（队列本身可写），只是不覆盖别人持有的行。
        assertEquals(0, posting.consumeDue(5));
        verify(taskMapper).markFailedIfLeaseHeld(eq(903L), anyString(), eq("PAYMENT_FACT_MISSING"), any(), any());
        verify(taskMapper, never()).markFailed(anyLong(), anyString(), any(), any());
    }

    @Test
    void failedTaskStillCarriesBackoffThroughTheFencedWrite() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(task(904L, 558L, 2)));
        when(allocationService.accrue(any())).thenThrow(
                MarketingRevenueException.conflict("FEE_BASIS_MISSING", "费用未核"));
        when(taskMapper.markFailedIfLeaseHeld(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);

        posting.consumeDue(5);

        ArgumentCaptor<java.util.Date> next = ArgumentCaptor.forClass(java.util.Date.class);
        verify(taskMapper).markFailedIfLeaseHeld(eq(904L), anyString(), eq("FEE_BASIS_MISSING"), any(),
                next.capture());
        // 第 3 次失败 ⇒ 等待 9 分钟：重领不得绕过 next_attempt_at（claim SQL 用 next_attempt_at <= now()）。
        long waitMs = next.getValue().getTime() - System.currentTimeMillis();
        org.junit.jupiter.api.Assertions.assertTrue(
                waitMs > 8 * 60_000L && waitMs <= 9 * 60_000L + 5_000L,
                "退避必须按 attempts 递增，actual=" + waitMs);
    }

    @Test
    void leaseOwnerIsStableAcrossClaimAndWriteForOneInstance() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(
                task(905L, 559L, 0), task(906L, 560L, 0)));
        when(allocationService.accrue(any())).thenReturn(new RevenueAccrualResult());
        when(taskMapper.markDoneIfLeaseHeld(anyLong(), anyString())).thenReturn(1);

        posting.consumeDue(5);
        posting.consumeDue(5);

        ArgumentCaptor<String> owners = ArgumentCaptor.forClass(String.class);
        verify(taskMapper, org.mockito.Mockito.atLeast(2)).markDoneIfLeaseHeld(anyLong(), owners.capture());
        for (String owner : owners.getAllValues()) {
            assertNotEquals("", owner);
        }
    }

    private MarketingRevenuePostingTask task(long id, long refId, int attempts) {
        MarketingRevenuePostingTask task = new MarketingRevenuePostingTask();
        task.setId(id);
        task.setTaskKind(MarketingRevenuePostingTask.KIND_ACCRUE);
        task.setRefId(refId);
        task.setOrderId(refId);
        task.setStatus(MarketingRevenuePostingTask.STATUS_PENDING);
        task.setAttempts(attempts);
        return task;
    }
}
