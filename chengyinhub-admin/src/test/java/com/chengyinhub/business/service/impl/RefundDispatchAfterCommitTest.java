package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.CancellationPhaseTestSupport;
import com.chengyinhub.business.service.IRefundPayoutService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 2026-11-02 pay-gate:玩家自退后退款单停在 payout_status=5(DISPATCH_PENDING)是代码缺陷,不是 rig 假象。
 *
 * <p>Spring 在 afterCommit 回调里 {@code isActualTransactionActive()} 仍为 true(事务资源要到
 * cleanupAfterCompletion 才解绑)。旧的 {@code dispatchPayoutAfterCommit} 在 afterCommit 里直接调
 * {@code payoutAfterReview},{@code RefundPayoutServiceImpl.doPayout} 第一行守卫
 * 「退款派发必须在原业务事务提交并退出后执行」因此每次都抛,被吞成日志,只能等对账任务补派。
 * 本测试记录派发那一刻线程上是否还挂着事务:旧代码为 true(红),修复后为 false。
 */
class RefundDispatchAfterCommitTest {

    @Test
    void springStillReportsActiveTransactionInsideAfterCommit() {
        PlatformTransactionManager tm = CancellationPhaseTestSupport.memoryTransactions();
        List<Boolean> seen = new ArrayList<>();
        new TransactionTemplate(tm).executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCommit() {
                                seen.add(TransactionSynchronizationManager.isActualTransactionActive());
                            }
                        }));
        assertEquals(List.of(Boolean.TRUE), seen, "这正是旧派发撞上 doPayout 守卫的原因");
    }

    @Test
    void payoutRunsOutsideAnyTransactionAfterCommit() {
        PlatformTransactionManager tm = CancellationPhaseTestSupport.memoryTransactions();
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        IRefundPayoutService payouts = mock(IRefundPayoutService.class);
        List<Boolean> activeAtDispatch = new ArrayList<>();
        doAnswer(call -> {
            activeAtDispatch.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(payouts).payoutAfterReview(anyLong());
        ReflectionTestUtils.setField(service, "refundPayoutService", payouts);
        ReflectionTestUtils.setField(service, "cancellationTransactions", tm);

        new TransactionTemplate(tm).executeWithoutResult(status -> {
            ReflectionTestUtils.invokeMethod(service, "dispatchPayoutAfterCommit", 75L);
            assertTrue(activeAtDispatch.isEmpty(), "提交前不得派发");
        });

        assertEquals(List.of(Boolean.FALSE), activeAtDispatch);
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    @Test
    void rolledBackTransactionNeverDispatches() {
        PlatformTransactionManager tm = CancellationPhaseTestSupport.memoryTransactions();
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        IRefundPayoutService payouts = mock(IRefundPayoutService.class);
        ReflectionTestUtils.setField(service, "refundPayoutService", payouts);
        ReflectionTestUtils.setField(service, "cancellationTransactions", tm);

        new TransactionTemplate(tm).executeWithoutResult(status -> {
            ReflectionTestUtils.invokeMethod(service, "dispatchPayoutAfterCommit", 76L);
            status.setRollbackOnly();
        });

        verify(payouts, never()).payoutAfterReview(anyLong());
    }
}
