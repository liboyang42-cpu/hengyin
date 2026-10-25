package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Committed business results must not be announced before commit or lose the IM write afterward. */
@Component
public class CommittedBusinessNotice {
    private static final Logger log = LoggerFactory.getLogger(CommittedBusinessNotice.class);
    private final INotifyService notifyService;
    private final AlarmService alarmService;
    private final TransactionTemplate independentTransaction;
    private final ScheduledExecutorService existingExecutor;

    public CommittedBusinessNotice(INotifyService notifyService, AlarmService alarmService,
                                   PlatformTransactionManager transactionManager,
                                   @Qualifier("scheduledExecutorService") ScheduledExecutorService existingExecutor) {
        this.notifyService = notifyService;
        this.alarmService = alarmService;
        this.existingExecutor = existingExecutor;
        independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void sendAfterCommit(Long memberId, String title, String text, String eventKey, Long businessId) {
        executeAfterCommit(eventKey, businessId,
                () -> memberId != null && memberId > 0 && notifyService.notifyOfficialBroadcast(memberId, title, text));
    }

    public void executeAfterCommit(String eventKey, Long businessId, Supplier<Boolean> delivery) {
        Runnable action = () -> dispatchInNewTransaction(eventKey, businessId, delivery);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    /** Reuse AsyncManager's existing scheduler after commit; no outer DB connection is held while waiting. */
    public void dispatchInNewTransaction(String eventKey, Long businessId, Supplier<Boolean> delivery) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        try {
            existingExecutor.schedule(() -> { deliverInNewTransaction(eventKey, businessId, delivery); }, 10, TimeUnit.MILLISECONDS);
        } catch (Throwable rejected) {
            reportFailure(eventKey, businessId);
        }
    }

    private void reportFailure(String eventKey, Long businessId) {
        try {
            alarmService.alarm("业务通知未送达", "event=" + eventKey + " businessId=" + businessId);
        } catch (Throwable alarmFailure) {
            log.error("Business notice alarm failed event={} businessId={} type={}",
                    eventKey, businessId, alarmFailure.getClass().getSimpleName());
        }
    }

    /** For the dispatched task or callers that already released their business transaction. */
    public boolean deliverInNewTransaction(String eventKey, Long businessId, Supplier<Boolean> delivery) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        try {
            if (Boolean.TRUE.equals(independentTransaction.execute(status -> delivery.get()))) return true;
        } catch (Throwable failure) {
            log.error("Business notice failed event={} businessId={} type={}",
                    eventKey, businessId, failure.getClass().getSimpleName());
        }
        reportFailure(eventKey, businessId);
        return false;
    }
}
