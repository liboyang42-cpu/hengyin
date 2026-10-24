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

/** Explicit test wiring only. memoryTransactions/unit row adapters do not prove SQL or SDK facts.
 * SQL callers supply their existing real manager and keep their actual locking Mapper. */
public final class CancellationPhaseTestSupport {
    private CancellationPhaseTestSupport() { }
    public static PlatformTransactionManager memoryTransactions() {
        return memoryTransactions(() -> { });
    }
    public static PlatformTransactionManager memoryTransactions(Runnable beforeCommit) {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected boolean isExistingTransaction(Object transaction) {
                return TransactionSynchronizationManager.isActualTransactionActive();
            }
            @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { beforeCommit.run(); }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
            @Override protected Object doSuspend(Object transaction) {
                throw new AssertionError("cancellation must not suspend a lock-owning caller");
            }
        };
    }
    public static RegistrationCancellationPayments stubEmptyObservation(ITeamGroupService group, Long activityId) {
        return stubEmptyObservation(group,new RegistrationCancellationPayments.Scope(2L,activityId,null,null,null,List.of()));
    }
    public static RegistrationCancellationPayments stubEmptyObservation(ITeamGroupService group,
            RegistrationCancellationPayments.Scope scope) {
        assertEquals(Long.valueOf(2L),scope.ownerType());
        var captured = new RegistrationCancellationPayments(List.of(scope));
        Long activityId=scope.ownerId();
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly()); return captured;
        }).when(group).captureCancellationPayments(2L,activityId);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return captured;
        }).when(group).observeCancellationPayments(captured);
        return captured;
    }
    public static void wireTransactions(ClubEventOpsServiceImpl service,PlatformTransactionManager transactions) {
        ReflectionTestUtils.setField(service,"cancellationTransactions",transactions);
    }
    /** Explicit B port double: these legacy fixtures exercise cancellation, not verified SDK evidence.
     * Absence is modeled only for the no-success pending cases; an unexpected success admission must fail. */
    public static void wireTeamTransactions(com.chengyinhub.business.service.impl.TeamGroupServiceImpl service,
                                           PlatformTransactionManager transactions) {
        ReflectionTestUtils.setField(service,"cancellationTransactions",transactions);
        var facts=mock(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.class);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return false;
        }).when(facts).hasConfirmedExternalSuccess(anyString());
        doThrow(new AssertionError("this cancellation fixture does not synthesize verified provider success"))
                .when(facts).requireVerified(any());
        ReflectionTestUtils.setField(service,"verifiedPaymentFacts",facts);
    }

    /** Unit only: adapt each fixture's explicit scoped row source to the new locking-read port.
     * Never install this on a real Mapper; SQL fixtures retain the actual selectCancellationRowsForUpdate. */
    public static void wireTeamUnit(com.chengyinhub.business.service.impl.TeamGroupServiceImpl service,
                                    com.chengyinhub.business.mapper.CmsRegistrationMapper registrations) {
        wireTeamTransactions(service,memoryTransactions());
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            var query=new com.chengyinhub.business.domain.CmsRegistration();
            query.setOwnerType(call.getArgument(0));query.setOwnerId(call.getArgument(1));
            return registrations.selectCmsRegistrationList(query);
        }).when(registrations).selectCancellationRowsForUpdate(anyLong(),anyLong());
    }
}
