package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.VerificationReview;
import com.chengyinhub.business.mapper.VerificationReviewMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationRiskNotificationTest {
    @Test
    void insertedReviewNotifiesOnlyAfterCommitAndRolledBackReviewNeverNotifies() {
        for (boolean commit : new boolean[]{true, false}) {
            VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
            when(mapper.insertIgnore(any())).thenAnswer(call -> { ((VerificationReview) call.getArgument(0)).setId(31L); return 1; });
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
            AlarmService alarms = mock(AlarmService.class);
            when(alarms.deliver(anyString(), anyString())).thenReturn(AlarmService.AlarmDelivery.SUCCESS);
            VerificationRiskService risk = new VerificationRiskService(new BigDecimal("0.90"), 8, 5);
            ReflectionTestUtils.setField(risk, "reviewMapper", mapper); ReflectionTestUtils.setField(risk, "alarmService", alarms);
            ReflectionTestUtils.setField(risk, "committedBusinessNotice",
                    new CommittedBusinessNotice(mock(INotifyService.class), alarms, manager, executor));
            TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertTrue(risk.evaluateAndQueue(7L, 99L, 10, 9, 8, 1));
                verifyNoInteractions(alarms, executor, manager);
                TransactionSynchronization sync = TransactionSynchronizationManager.getSynchronizations().get(0);
                if (commit) {
                    sync.afterCommit();
                    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
                    verify(executor).schedule(task.capture(), eq(10L), eq(TimeUnit.MILLISECONDS)); task.getValue().run();
                    verify(alarms).deliver("核销异常待人工复核", "reviewId=31 risk=HIGH_RATE_AND_BURST");
                    verify(manager).commit(any());
                } else {
                    sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK); verifyNoInteractions(alarms, executor, manager);
                }
            } finally {
                TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
    }

    @Test
    void duplicateOrOrdinaryReviewDoesNotScheduleAnotherNotification() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
        CommittedBusinessNotice notices = mock(CommittedBusinessNotice.class);
        VerificationRiskService risk = new VerificationRiskService(new BigDecimal("0.90"), 8, 5);
        ReflectionTestUtils.setField(risk, "reviewMapper", mapper); ReflectionTestUtils.setField(risk, "committedBusinessNotice", notices);
        assertFalse(risk.evaluateAndQueue(7L, 99L, 10, 9, 8, 1));
        assertFalse(risk.evaluateAndQueue(7L, 99L, 10, 6, 1, 1));
        verifyNoInteractions(notices);
    }
}
