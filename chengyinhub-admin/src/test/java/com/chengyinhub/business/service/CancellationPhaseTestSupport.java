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
        wireReceiptMocks(service);
    }

    /** Unit only: adapt each fixture's explicit scoped row source to the new locking-read port.
     * Never install this on a real Mapper; SQL fixtures retain the actual selectCancellationRowsForUpdate. */
    public static void wireTeamUnit(com.chengyinhub.business.service.impl.TeamGroupServiceImpl service,
                                    com.chengyinhub.business.mapper.CmsRegistrationMapper registrations) {
        wireTeamTransactions(service,memoryTransactions());
        wireReceiptMocks(service);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            var query=new com.chengyinhub.business.domain.CmsRegistration();
            query.setOwnerType(call.getArgument(0));query.setOwnerId(call.getArgument(1));
            return registrations.selectCmsRegistrationList(query);
        }).when(registrations).selectCancellationRowsForUpdate(anyLong(),anyLong());
    }

    /** Explicit in-memory unit adapter ONLY; real SQL Mappers are never replaced.
     * Staged JSON follows the enclosing Spring transaction, including rollback. This is not DB evidence. */
    public static void wireReceiptMocks(Object service) {
        boolean club=service instanceof ClubEventOpsServiceImpl;
        Object activities=ReflectionTestUtils.getField(service,club?"cancellationActivityMapper":"cmsActivityMapper");
        Object topics=ReflectionTestUtils.getField(service,club?"cancellationTopicMapper":"cmsTopicMapper");
        wireReceiptMocks((com.chengyinhub.business.mapper.CmsActivityMapper)activities,
                (com.chengyinhub.business.mapper.CmsTopicMapper)topics);
    }
    public static void wireReceiptMocks(com.chengyinhub.business.mapper.CmsActivityMapper activities,
                                        com.chengyinhub.business.mapper.CmsTopicMapper topics) {
        if(activities!=null && mockingDetails(activities).isMock() && !mockingDetails(activities).isSpy()) {
            UnitReceipts rows=new UnitReceipts();
            doAnswer(call->rows.read(call.getArgument(0))).when(activities).selectCancellationReceiptForUpdate(anyLong());
            doAnswer(call->rows.cas(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3)))
                    .when(activities).compareAndSetCancellationReceipt(anyLong(),anyString(),anyString(),anyBoolean());
        }
        if(topics!=null && mockingDetails(topics).isMock() && !mockingDetails(topics).isSpy()) {
            UnitReceipts rows=new UnitReceipts();
            doReturn(0L).when(topics).selectCancellationEpoch(anyLong());
            doReturn(0L).when(topics).selectCancellationEpochForUpdate(anyLong());
            doAnswer(call->rows.read(call.getArgument(0)+":"+call.getArgument(1))).when(topics).selectCancellationReceiptForUpdate(anyLong(),anyLong());
            doAnswer(call->rows.cas(call.getArgument(0)+":"+call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4)))
                    .when(topics).compareAndSetCancellationReceipt(anyLong(),anyLong(),anyString(),anyString(),anyBoolean());
        }
    }
    private static final class UnitReceipts {
        private final java.util.Map<Object,String> committed=new java.util.HashMap<>();
        @SuppressWarnings("unchecked") private java.util.Map<Object,String> rows(boolean write) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            var staged=(java.util.Map<Object,String>)TransactionSynchronizationManager.getResource(this);
            if(staged==null && write) {
                staged=new java.util.HashMap<>(committed);
                TransactionSynchronizationManager.bindResource(this,staged);
                var changes=staged;
                TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(UnitReceipts.this);
                        if(status==STATUS_COMMITTED) {committed.clear();committed.putAll(changes);}
                    }
                });
            }
            return staged==null?committed:staged;
        }
        String read(Object id) {return rows(false).get(id);}
        int cas(Object id,String hash,String next,boolean first) {
            var rows=rows(true);String old=rows.get(id);
            if(first?old!=null:old==null) return 0;
            if(!first) {
                var original=com.alibaba.fastjson2.JSON.parseObject(old);
                if(!"PENDING".equals(original.getString("state")) || !hash.equals(original.getString("requestHash"))) return 0;
            }
            var supplied=com.alibaba.fastjson2.JSON.parseObject(next);
            if(!hash.equals(supplied.getString("requestHash"))) return 0;
            rows.put(id,next);return 1;
        }
    }
}
