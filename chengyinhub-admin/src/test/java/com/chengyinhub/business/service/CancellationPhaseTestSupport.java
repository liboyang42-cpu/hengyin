package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Wiring for existing wrapper tests with a mocked TeamGroup boundary; no provider fact or SQL claim. */
final class CancellationPhaseTestSupport {
    private CancellationPhaseTestSupport() { }
    static PlatformTransactionManager memoryTransactions() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected boolean isExistingTransaction(Object transaction) {
                return TransactionSynchronizationManager.isActualTransactionActive();
            }
            @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
            @Override protected Object doSuspend(Object transaction) {
                throw new AssertionError("cancellation must not suspend a lock-owning caller");
            }
        };
    }
    static RegistrationCancellationPayments stubEmptyObservation(ITeamGroupService group, Long activityId) {
        var captured = new RegistrationCancellationPayments(List.of(
                new RegistrationCancellationPayments.Scope(2L,activityId,null,null,null,List.of())));
        when(group.captureCancellationPayments(2L,activityId)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return captured;
        });
        when(group.observeCancellationPayments(captured)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return captured;
        });
        return captured;
    }
    static void wireTransactions(ClubEventOpsServiceImpl service,PlatformTransactionManager transactions) {
        ReflectionTestUtils.setField(service,"cancellationTransactions",transactions);
    }
}
