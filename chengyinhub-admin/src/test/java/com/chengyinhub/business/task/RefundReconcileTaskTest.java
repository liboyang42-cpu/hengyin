package com.chengyinhub.business.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundReconcileTaskTest {

    @Test
    void reconcileRetriesCommittedButUndispatchedRefundAndPollsAcceptedRefund() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        IRefundPayoutService payoutService = mock(IRefundPayoutService.class);
        IWechatPayService wechatPayService = mock(IWechatPayService.class);
        AlarmService alarmService = mock(AlarmService.class);

        RefundApplication dispatchPending = new RefundApplication();
        dispatchPending.setId(7L);
        dispatchPending.setStatus(1);
        dispatchPending.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);

        RefundApplication processing = new RefundApplication();
        processing.setId(8L);
        processing.setStatus(1);
        processing.setPayoutStatus(RefundApplication.PAYOUT_PROCESSING);
        processing.setRefundNo("RF8");

        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(100L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), eq(0L), anyLong(), eq(200))).thenReturn(Arrays.asList(dispatchPending, processing));
        when(mapper.countManualPayoutBacklog()).thenReturn(0);

        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", payoutService);
        ReflectionTestUtils.setField(task, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.reconcile();

        verify(payoutService).payoutAfterReview(7L);
        verify(wechatPayService).refreshPayoutFromWx("RF8");
    }

    @Test
    void stuckDispatchBacklogRaisesItsOwnAlarm() {
        // M2:卡在 5/6 的退款单同样是"钱没退",却不在 (2,3) 的人工队列里 —— 只数人工积压等于对它们零告警。
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        AlarmService alarmService = mock(AlarmService.class);

        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(100L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), eq(0L), anyLong(), eq(200))).thenReturn(Collections.emptyList());
        when(mapper.countManualPayoutBacklog()).thenReturn(0);
        when(mapper.countStuckPayoutDispatch(anyInt())).thenReturn(3);

        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.reconcile();

        verify(alarmService).alarmThrottled(eq("monitor.refund.dispatch_stuck"), eq("退款派发卡滞"), contains("3"));
    }

    @Test
    void stuckCountIsTakenBeforeRetryRefreshesUpdateTime() {
        // H-B:payoutAfterReview 的 claim/release/markPayoutResult 都会把 update_time 刷成 now(),
        // 先重试再数"stale ≥ N 分钟"必然数到 0 —— 卡滞告警对注册类退款近似恒哑。
        // 用状态化 stub 复刻真库时序:重试一旦发生,卡滞计数立即归 0(等价于 update_time 被刷新)。
        // 旧顺序(先重试后计数)下本用例必红;修后(先计数后重试)必须照样告警。
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        IRefundPayoutService payoutService = mock(IRefundPayoutService.class);

        RefundApplication stuck = new RefundApplication();
        stuck.setId(9L);
        stuck.setStatus(1);
        stuck.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);

        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(100L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), eq(0L), anyLong(), eq(200))).thenReturn(Collections.singletonList(stuck));
        when(mapper.countManualPayoutBacklog()).thenReturn(0);
        AtomicBoolean retried = new AtomicBoolean(false);
        doAnswer(invocation -> {
            retried.set(true);
            return null;
        }).when(payoutService).payoutAfterReview(9L);
        when(mapper.countStuckPayoutDispatch(anyInt())).thenAnswer(invocation -> retried.get() ? 0 : 1);

        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", payoutService);
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.reconcile();

        verify(payoutService).payoutAfterReview(9L);
        verify(alarmService).alarmThrottled(eq("monitor.refund.dispatch_stuck"), eq("退款派发卡滞"), contains("退款单=1"));
    }

    @Test
    void noAlarmWhenNothingIsStuck() {
        // 负控配对:计数为 0 时不许有装饰性告警,否则告警变噪音就没人看了。
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        AlarmService alarmService = mock(AlarmService.class);

        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(100L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), eq(0L), anyLong(), eq(200))).thenReturn(Collections.emptyList());
        when(mapper.countManualPayoutBacklog()).thenReturn(0);
        when(mapper.countStuckPayoutDispatch(anyInt())).thenReturn(0);

        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.reconcile();

        verify(alarmService, never()).alarm(anyString(), anyString());
        verify(alarmService, never()).alarmThrottled(anyString(), anyString(), anyString());
    }

    @Test
    void manualBacklogAlarmDeliveryFailureIsLoggedWithThrowable() {
        // P2-19:告警投递失败原本被 catch (Exception ignore) 吞掉，守门告警自身失明。
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        AlarmService alarmService = mock(AlarmService.class);

        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(100L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), eq(0L), anyLong(), eq(200))).thenReturn(Collections.emptyList());
        when(mapper.countManualPayoutBacklog()).thenReturn(4);
        when(mapper.countStuckPayoutDispatch(anyInt())).thenReturn(0);
        doThrow(new IllegalStateException("backlog alarm failed"))
                .when(alarmService).alarmThrottled(eq("monitor.refund.manual"), eq("退款待人工积压"), anyString());

        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        ListAppender<ILoggingEvent> logs = attachAppender();
        try {
            task.reconcile();
        } finally {
            detachAppender(logs);
        }

        verify(alarmService).alarmThrottled(eq("monitor.refund.manual"), eq("退款待人工积压"), contains("4"));
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("退款待人工积压巡检失败")
                        && event.getThrowableProxy() != null
                        && "backlog alarm failed".equals(event.getThrowableProxy().getMessage())),
                "待人工积压告警投递失败必须以 ERROR 记录并带原始异常");
    }

    @Test
    void stuckDispatchAlarmDeliveryFailureIsLoggedWithThrowable() {
        // P2-19:卡滞告警投递失败同样不能静默。
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        AlarmService alarmService = mock(AlarmService.class);

        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(100L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), eq(0L), anyLong(), eq(200))).thenReturn(Collections.emptyList());
        when(mapper.countManualPayoutBacklog()).thenReturn(0);
        when(mapper.countStuckPayoutDispatch(anyInt())).thenReturn(2);
        doThrow(new IllegalStateException("stuck alarm failed"))
                .when(alarmService).alarmThrottled(eq("monitor.refund.dispatch_stuck"), eq("退款派发卡滞"), anyString());

        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        ListAppender<ILoggingEvent> logs = attachAppender();
        try {
            task.reconcile();
        } finally {
            detachAppender(logs);
        }

        verify(alarmService).alarmThrottled(eq("monitor.refund.dispatch_stuck"), eq("退款派发卡滞"), contains("退款单=2"));
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("退款派发卡滞告警发送失败")
                        && event.getThrowableProxy() != null
                        && "stuck alarm failed".equals(event.getThrowableProxy().getMessage())),
                "卡滞告警投递失败必须以 ERROR 记录并带原始异常");
    }

    @Test
    void depositReconcileFailureDoesNotSuppressRegistrationRefunds() {
        com.chengyinhub.business.service.ICoopInviteService deposits = mock(com.chengyinhub.business.service.ICoopInviteService.class);
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        IRefundPayoutService payout = mock(IRefundPayoutService.class);
        RefundApplication pending = new RefundApplication();
        pending.setId(7L); pending.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);
        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(100L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), eq(0L), anyLong(), eq(200))).thenReturn(Collections.singletonList(pending));
        doThrow(new IllegalStateException("deposit queue unavailable")).when(deposits).reconcileDepositRefunds();
        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task,"coopInviteService",deposits);
        ReflectionTestUtils.setField(task,"refundApplicationMapper",mapper);
        ReflectionTestUtils.setField(task,"refundPayoutService",payout);
        ReflectionTestUtils.setField(task,"wechatPayService",mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task,"alarmService",mock(AlarmService.class));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, task::reconcile);
        verify(deposits).reconcileDepositRefunds(); verify(payout).payoutAfterReview(7L);
    }

    @Test
    void permanentlyFailingFirstPageDoesNotStarveLaterPagesOrNextRound() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        IRefundPayoutService payout = mock(IRefundPayoutService.class);
        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(1001L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), anyLong(), eq(1001L), eq(200)))
                .thenAnswer(invocation -> {
                    long after = (Long) invocation.getArgument(1);
                    java.util.List<RefundApplication> page = new java.util.ArrayList<>();
                    for (long id = after + 1; id <= Math.min(after + 200, 1001); id++) {
                        RefundApplication r = new RefundApplication();
                        r.setId(id); r.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);
                        page.add(r);
                    }
                    return page;
                });
        doThrow(new IllegalStateException("permanent failure")).when(payout).payoutAfterReview(1L);
        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", payout);
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task, "alarmService", mock(AlarmService.class));
        task.reconcile();
        verify(payout).payoutAfterReview(1000L);
        verify(payout, never()).payoutAfterReview(1001L);
        task.reconcile();
        verify(payout).payoutAfterReview(1001L);
        verify(payout, org.mockito.Mockito.times(1)).payoutAfterReview(1L);
        verify(mapper, org.mockito.Mockito.times(2)).selectStalePayoutScanUpperBound(30);
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(RefundReconcileTask.class)).addAppender(logs);
        return logs;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(RefundReconcileTask.class)).detachAppender(logs);
    }

    @Test
    void stuckDispatchCountFailureIsLoggedAndAlarmedEvenWhenAlarmDeliveryFails() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(mapper.countStuckPayoutDispatch(anyInt())).thenThrow(new IllegalStateException("count failed"));
        when(mapper.selectStalePayoutScanUpperBound(anyInt())).thenReturn(100L);
        when(mapper.selectStalePayoutProcessingAfter(anyInt(), eq(0L), anyLong(), eq(200))).thenReturn(Collections.emptyList());
        when(mapper.countManualPayoutBacklog()).thenReturn(0);
        doThrow(new IllegalStateException("alarm failed"))
                .when(alarmService).alarmThrottled(eq("monitor.refund.dispatch_count_failed"), eq("退款派发卡滞计数失败"), anyString());

        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        Logger logger = (Logger) LoggerFactory.getLogger(RefundReconcileTask.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            task.reconcile();
        } finally {
            logger.detachAppender(logs);
        }

        verify(alarmService).alarmThrottled(eq("monitor.refund.dispatch_count_failed"), eq("退款派发卡滞计数失败"), anyString());
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("退款派发卡滞计数失败")
                        && event.getThrowableProxy() != null
                        && "count failed".equals(event.getThrowableProxy().getMessage())),
                "计数异常必须先被 ERROR 记录；告警服务自身异常不能覆盖原始计数异常");
    }

    @Test
    void inspectionAlarmsCarryRefundIdSamplesAndSampleFailureKeepsCountAlarm() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(mapper.selectStalePayoutProcessing(anyInt())).thenReturn(Collections.emptyList());
        when(mapper.countManualPayoutBacklog()).thenReturn(2);
        when(mapper.sampleManualPayoutBacklogIds()).thenReturn(Arrays.asList(11L, 12L));
        when(mapper.countStuckPayoutDispatch(anyInt())).thenReturn(1);
        when(mapper.sampleStuckPayoutDispatchIds(anyInt())).thenThrow(new IllegalStateException("sample failed"));

        RefundReconcileTask task = new RefundReconcileTask();
        ReflectionTestUtils.setField(task, "coopInviteService", mock(com.chengyinhub.business.service.ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.reconcile();

        verify(alarmService).alarmThrottled(eq("monitor.refund.manual"), eq("退款待人工积压"), contains("refundIds=[11,12]"));
        verify(alarmService).alarmThrottled(eq("monitor.refund.dispatch_stuck"), eq("退款派发卡滞"), contains("refundIds=UNKNOWN"));
        verify(alarmService, never()).alarm(anyString(), anyString());
    }
}
