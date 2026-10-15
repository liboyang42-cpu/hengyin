package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommittedBusinessNoticeTest {
    @Test
    void deliveryWaitsForCommitThenUsesAnIndependentTransaction() {
        INotifyService notify = mock(INotifyService.class);
        AlarmService alarm = mock(AlarmService.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(notify.notifyOfficialBroadcast(101L, "结果", "说明")).thenReturn(true);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        CommittedBusinessNotice notices = new CommittedBusinessNotice(notify, alarm, manager, executor);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            notices.sendAfterCommit(101L, "结果", "说明", "join-review", 201L);
            verifyNoInteractions(notify, manager);
            TransactionSynchronization callback = TransactionSynchronizationManager.getSynchronizations().get(0);
            callback.afterCommit();
            verifyNoInteractions(notify, manager);
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).schedule(task.capture(), eq(10L), eq(TimeUnit.MILLISECONDS));
            task.getValue().run();
            verify(manager).getTransaction(argThat(definition ->
                    definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
            verify(manager).commit(any());
            verify(notify, times(1)).notifyOfficialBroadcast(101L, "结果", "说明");
            verifyNoInteractions(alarm);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void rejectedDispatchAlarmsAndNeverAcquiresAnotherDatabaseConnection() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        doThrow(new RejectedExecutionException("fixture executor closed"))
                .when(executor).schedule(any(Runnable.class), anyLong(), any());
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        AlarmService alarm = mock(AlarmService.class);
        CommittedBusinessNotice notices = new CommittedBusinessNotice(mock(INotifyService.class), alarm, manager, executor);
        assertDoesNotThrow(() -> notices.dispatchInNewTransaction("withdraw", 4L, () -> true));
        verifyNoInteractions(manager);
        verify(alarm).alarm(eq("业务通知未送达"), anyString());
    }

    @Test
    void falseOrThrowingDeliveryAlarmsWithoutThrowingAtCommittedBusiness() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AlarmService alarm = mock(AlarmService.class);
        CommittedBusinessNotice notices = new CommittedBusinessNotice(mock(INotifyService.class), alarm, manager,
                mock(ScheduledExecutorService.class));
        assertFalse(notices.deliverInNewTransaction("withdraw", 1L, () -> false));
        assertDoesNotThrow(() -> notices.deliverInNewTransaction("withdraw", 2L,
                () -> { throw new IllegalStateException("fixture failure"); }));
        assertFalse(notices.deliverInNewTransaction("withdraw", 3L, () -> null));
        verify(alarm, times(3)).alarm(eq("业务通知未送达"), anyString());
        verify(manager).rollback(any());
    }
}
