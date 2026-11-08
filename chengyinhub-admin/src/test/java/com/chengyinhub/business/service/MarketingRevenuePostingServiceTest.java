package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RevenueAccrualResult;
import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.MarketingRevenuePostingTaskMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.impl.MarketingRevenuePostingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * 可靠投递契约（root 第二轮纠正）。
 *
 * <p>要证明：
 * 1. 入队必须发生在**调用方事务**里（MANDATORY），不能偷偷开新事务；
 * 2. 消费用独立事务，原事实已提交后才执行；
 * 3. 失败更新任务行（status/attempts/reason/退避），不是只写日志；
 * 4. 已 DONE 的任务不会被重复执行。
 */
class MarketingRevenuePostingServiceTest {

    private IMarketingRevenueAllocationService allocationService;
    private MarketingRevenuePostingTaskMapper taskMapper;
    private RefundApplicationMapper refundMapper;
    private RecordingTransactionManager transactions;
    private MarketingRevenuePostingServiceImpl posting;

    static class RecordingTransactionManager implements PlatformTransactionManager {
        final java.util.List<Integer> propagations = new java.util.ArrayList<>();
        private final TransactionStatus status = mock(TransactionStatus.class);

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
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
        refundMapper = mock(RefundApplicationMapper.class);
        transactions = new RecordingTransactionManager();
        when(taskMapper.markFailedIfLeaseHeld(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);
        posting = new MarketingRevenuePostingServiceImpl(allocationService, taskMapper,
                refundMapper, transactions);
    }

    private MarketingRevenuePostingTask task(long id, int kind, long refId, int attempts) {
        MarketingRevenuePostingTask task = new MarketingRevenuePostingTask();
        task.setId(id);
        task.setTaskKind(kind);
        task.setRefId(refId);
        task.setOrderId(refId);
        task.setStatus(MarketingRevenuePostingTask.STATUS_PENDING);
        task.setAttempts(attempts);
        return task;
    }

    // ------------------------------------------------------------------ 入队

    @Test
    void enqueueAccrualWritesAPendingRowAndReadsItBack() {
        when(taskMapper.selectByKindAndRef(MarketingRevenuePostingTask.KIND_ACCRUE, 555L))
                .thenAnswer(invocation -> task(901L, MarketingRevenuePostingTask.KIND_ACCRUE, 555L, 0));

        Long id = posting.enqueueAccrual(555L);

        assertEquals(Long.valueOf(901L), id);
        ArgumentCaptor<MarketingRevenuePostingTask> captor =
                ArgumentCaptor.forClass(MarketingRevenuePostingTask.class);
        verify(taskMapper).enqueue(captor.capture());
        assertEquals(Integer.valueOf(MarketingRevenuePostingTask.KIND_ACCRUE), captor.getValue().getTaskKind());
        assertEquals(Long.valueOf(555L), captor.getValue().getRefId());
        assertEquals(Integer.valueOf(MarketingRevenuePostingTask.STATUS_PENDING), captor.getValue().getStatus());
    }

    @Test
    void enqueueReversalResolvesTheOrderFromTheRefundRecord() {
        RefundApplication refund = new RefundApplication();
        refund.setId(7001L);
        refund.setSourceType("registration");
        refund.setSourceId(555L);
        when(refundMapper.selectRefundApplicationById(7001L)).thenReturn(refund);
        when(taskMapper.selectByKindAndRef(MarketingRevenuePostingTask.KIND_REVERSE, 7001L))
                .thenAnswer(invocation -> task(902L, MarketingRevenuePostingTask.KIND_REVERSE, 7001L, 0));

        Long id = posting.enqueueReversal(7001L, null);

        assertEquals(Long.valueOf(902L), id);
        ArgumentCaptor<MarketingRevenuePostingTask> captor =
                ArgumentCaptor.forClass(MarketingRevenuePostingTask.class);
        verify(taskMapper).enqueue(captor.capture());
        assertEquals(Long.valueOf(555L), captor.getValue().getOrderId());
    }

    @Test
    void enqueueReversalRejectsARefundWithoutRegistration() {
        RefundApplication coupon = new RefundApplication();
        coupon.setId(7002L);
        coupon.setSourceType("coupon");
        when(refundMapper.selectRefundApplicationById(7002L)).thenReturn(coupon);

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> posting.enqueueReversal(7002L, null));

        assertEquals("ORDER_REQUIRED", error.getReasonCode());
        verify(taskMapper, never()).enqueue(any());
    }

    @Test
    void enqueueRequiresAnExistingCallerTransaction() {
        // MANDATORY：没有调用方事务就不能入队 —— 否则任务会和支付事实脱钩。
        for (String name : new String[]{"enqueueAccrual", "enqueueReversal"}) {
            var method = java.util.Arrays.stream(MarketingRevenuePostingServiceImpl.class.getMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " 方法缺失"));
            var annotation = method.getAnnotation(
                    org.springframework.transaction.annotation.Transactional.class);
            assertNotNull(annotation, name + " 必须声明 @Transactional");
            assertEquals(org.springframework.transaction.annotation.Propagation.MANDATORY,
                    annotation.propagation(), name + " 必须使用 MANDATORY，不能自开事务");
        }
    }

    // ------------------------------------------------------------------ 消费

    @Test
    void consumeRunsInItsOwnTransactionAfterTheOriginalCommit() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(
                task(901L, MarketingRevenuePostingTask.KIND_ACCRUE, 555L, 0)));
        RevenueAccrualResult result = new RevenueAccrualResult();
        result.setAllocationId(88L);
        when(allocationService.accrue(any())).thenReturn(result);
        when(taskMapper.markDoneIfLeaseHeld(anyLong(), anyString())).thenReturn(1);

        int processed = posting.consumeDue(10);

        assertEquals(1, processed);
        // 领取 + 执行 + 标记完成，每次都在 REQUIRES_NEW 里，绝不加入调用方事务。
        for (int propagation : transactions.propagations) {
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, propagation);
        }
        verify(taskMapper).markDoneIfLeaseHeld(eq(901L), anyString());
    }

    @Test
    void missingFactLeavesTheTaskRetryableWithReasonAndBackoff() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(
                task(901L, MarketingRevenuePostingTask.KIND_ACCRUE, 555L, 0)));
        when(allocationService.accrue(any())).thenThrow(
                MarketingRevenueException.conflict("PAYMENT_FACT_MISSING", "缺少已验证支付事实"));

        posting.consumeDue(10);

        ArgumentCaptor<Date> next = ArgumentCaptor.forClass(Date.class);
        verify(taskMapper).markFailedIfLeaseHeld(eq(901L), anyString(), eq("PAYMENT_FACT_MISSING"), any(), next.capture());
        assertTrue(next.getValue().after(new Date(0)), "必须安排下次重试时间，而不是丢弃任务");
        verify(taskMapper, never()).markDoneIfLeaseHeld(anyLong(), anyString());
    }

    @Test
    void ruleNotFoundIsRetryableNotFatal() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(
                task(903L, MarketingRevenuePostingTask.KIND_ACCRUE, 556L, 2)));
        when(allocationService.accrue(any())).thenThrow(
                MarketingRevenueException.notFound("RULE_NOT_FOUND", "没有已发布规则"));

        posting.consumeDue(5);

        verify(taskMapper).markFailedIfLeaseHeld(eq(903L), anyString(), eq("RULE_NOT_FOUND"), any(), any());
    }

    @Test
    void unexpectedFailureIsAlsoRecordedWithReason() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(
                task(904L, MarketingRevenuePostingTask.KIND_ACCRUE, 557L, 0)));
        when(allocationService.accrue(any())).thenThrow(new IllegalStateException("分录写入失败"));

        posting.consumeDue(5);

        verify(taskMapper).markFailedIfLeaseHeld(eq(904L), anyString(), eq("POSTING_FAILED"), any(), any());
    }

    @Test
    void reverseTaskCallsReversalNotAccrual() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(
                task(905L, MarketingRevenuePostingTask.KIND_REVERSE, 7001L, 0)));
        when(allocationService.reverse(any())).thenReturn(new com.chengyinhub.business.domain.dto.RevenueReversalResult());
        when(taskMapper.markDoneIfLeaseHeld(anyLong(), anyString())).thenReturn(1);

        posting.consumeDue(5);

        verify(allocationService).reverse(any());
        verify(allocationService, never()).accrue(any());
        verify(taskMapper).markDoneIfLeaseHeld(eq(905L), anyString());
    }

    @Test
    void refundFactMissingKeepsReverseTaskRetryable() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(
                task(906L, MarketingRevenuePostingTask.KIND_REVERSE, 7002L, 0)));
        when(allocationService.reverse(any())).thenThrow(
                MarketingRevenueException.conflict("REFUND_FACT_MISSING", "缺少通道退款事实"));

        posting.consumeDue(5);

        verify(taskMapper).markFailedIfLeaseHeld(eq(906L), anyString(), eq("REFUND_FACT_MISSING"), any(), any());
        verify(taskMapper, never()).markDoneIfLeaseHeld(anyLong(), anyString());
    }

    @Test
    void zeroOrNegativeLimitDoesNothing() {
        assertEquals(0, posting.consumeDue(0));
        assertEquals(0, posting.consumeDue(-3));
        verify(taskMapper, never()).claim(anyString(), anyInt(), anyInt());
    }

    @Test
    void claimUsesALeaseSoACrashedWorkerCannotStrandTheTask() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of());

        posting.consumeDue(7);

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> leaseSeconds = ArgumentCaptor.forClass(Integer.class);
        verify(taskMapper).claim(owner.capture(), leaseSeconds.capture(), eq(7));
        assertTrue(owner.getValue() != null && !owner.getValue().isBlank(), "必须写入领取者标识");
        assertTrue(leaseSeconds.getValue() > 0, "必须写入正数租约时长，过期后才能被回收");
    }

    @Test
    void expiredLeasesAreReclaimedBackToPending() {
        when(taskMapper.reclaimExpired()).thenReturn(2);

        assertEquals(2, posting.reclaimExpiredLeases());
        verify(taskMapper).reclaimExpired();
    }

    @Test
    void reclaimFailureSkipsTheRoundInsteadOfBlowingUp() {
        when(taskMapper.reclaimExpired()).thenThrow(new IllegalStateException("db down"));

        assertEquals(0, posting.reclaimExpiredLeases());
    }

    @Test
    void claimFailureSkipsTheRoundWithoutBlowingUp() {
        when(taskMapper.claim(anyString(), anyInt(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertEquals(0, posting.consumeDue(5));
    }

    @Test
    void rethrowingOnlyHappensWhenTheQueueItselfCannotRecordStatus() {
        when(taskMapper.selectClaimed(anyString())).thenReturn(List.of(
                task(907L, MarketingRevenuePostingTask.KIND_ACCRUE, 558L, 0)));
        when(allocationService.accrue(any())).thenThrow(
                MarketingRevenueException.conflict("PAYMENT_FACT_MISSING", "缺事实"));
        when(taskMapper.markFailedIfLeaseHeld(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("队列不可写"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> posting.consumeDue(5));
        assertTrue(error.getMessage().contains("状态无法落库"),
                "任务状态写不进去时必须响亮失败，否则会无限重复处理同一行");
    }

    @Test
    void tasksOfRejectsMissingOrder() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> posting.tasksOf(null));
        assertEquals("ORDER_REQUIRED", error.getReasonCode());
    }
}
