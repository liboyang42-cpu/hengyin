package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Scope and lock-order wiring only. No provider or G reversal acceptance. */
class OuterCancellationTopicFenceTest {
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private CmsRegistrationMapper registrations;
    private ClubMapper clubs;
    private IRefundService refunds;
    private TeamGroupServiceImpl team;
    private CmsRegistration registration;
    private CmsActivity activity;
    private CmsTopic topic;
    private ClubEventOpsMapper ops;
    private ClubEventOpsServiceImpl events;
    private ITeamGroupService delegate;
    private WechatVerifiedPaymentFactService verifiedPaymentFacts;
    private final List<String> phases = new ArrayList<>();
    private UnitTransactions transactions;

    @BeforeEach void fixtures() {
        activities = mock(CmsActivityMapper.class); topics = mock(CmsTopicMapper.class);
        registrations = mock(CmsRegistrationMapper.class); clubs = mock(ClubMapper.class);
        refunds = mock(IRefundService.class);
        verifiedPaymentFacts = mock(WechatVerifiedPaymentFactService.class);
        transactions = new UnitTransactions();
        registration = new CmsRegistration(); registration.setId(1L); registration.setRegistrationNo("REG1");
        registration.setMemberId(11L); registration.setOwnerType(1L); registration.setOwnerId(7L);
        registration.setPaymentStatus(2); registration.setRegistrationStatus(2);
        registration.setPurchaseKind(1); registration.setVerificationStatus(1);
        activity = new CmsActivity(); activity.setId(5L); activity.setMemberId(70L); activity.setTopicId(7L); activity.setDelFlag(0);
        topic = new CmsTopic(); topic.setId(7L); topic.setMemberId(70L);
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(registration);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L))
                .thenAnswer(call -> currentRead("registration", registration));
        when(activities.selectCmsActivityById(5L)).thenReturn(activity);
        when(activities.selectCmsActivityByIdForUpdate(5L)).thenAnswer(call -> currentRead("activity", activity));
        when(activities.updateCmsActivity(any())).thenAnswer(call -> currentRead("activity-write", 1));
        when(registrations.selectCancellationRowsForUpdate(2L, 5L))
                .thenAnswer(call -> currentRead("registrations", List.of()));
        when(topics.selectCmsTopicById(7L)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenAnswer(call -> currentRead("topic", topic));
        TeamGroupServiceImpl target = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target, "refundService", refunds);
        ReflectionTestUtils.setField(target, "cancellationTransactions", transactions);
        ReflectionTestUtils.setField(target, "verifiedPaymentFacts", verifiedPaymentFacts);
        // Exercise the actual REQUIRED/NEVER annotations as well as the production TransactionTemplates.
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        team = (TeamGroupServiceImpl) proxy.getProxy();
        ops = mock(ClubEventOpsMapper.class); delegate = mock(ITeamGroupService.class);
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(70L, 9L, 5L, ClubPermission.ACTIVITY_MANAGE)).thenReturn(
                new ClubAccessContext(70L, 9L, 70L, 5L, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        events = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(), mock(IClubSessionService.class),
                ops, mock(ClubMemberMapper.class), mock(ClubLeadMapper.class), mock(IClubRoleAssignmentService.class), delegate);
        ReflectionTestUtils.setField(events, "cancellationActivityMapper", activities);
        ReflectionTestUtils.setField(events, "cancellationTopicMapper", topics);
        ReflectionTestUtils.setField(events, "cancellationTransactions", transactions);
    }

    @AfterEach void transactionLifecycleIsClosed() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertNull(transactions.current.get());
        assertEquals(transactions.begun, transactions.committed + transactions.rolledBack);
    }

    @Test void selfRefundHoldsOriginalTopicAndRegistrationBeforeVerifiedEligibilityBranch() {
        assertThrows(RuntimeException.class, () -> team.cancelPaidRegistration(1L, 11L));
        var order = inOrder(topics, registrations);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        verifyNoInteractions(refunds);
        assertEquals(List.of("begin:1", "topic:1", "registration:1", "rollback:1"), phases);
    }

    @Test void selfRefundRejectsMemberChangedBeforeTheCurrentRowLockWithoutRefundWrites() {
        CmsRegistration changed = new CmsRegistration(); changed.setId(1L); changed.setRegistrationNo("REG1");
        changed.setMemberId(22L); changed.setOwnerType(1L); changed.setOwnerId(7L);
        doAnswer(call -> currentRead("registration", changed))
                .when(registrations).selectCmsRegistrationByIdForUpdate(1L);
        assertThrows(RuntimeException.class, () -> team.cancelPaidRegistration(1L, 11L));
        verifyNoInteractions(refunds);
        verify(registrations, never()).claimRegistrationRefunded(anyLong(), anyString(), any());
        var order = inOrder(topics, registrations);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        assertEquals(1, transactions.rolledBack);
    }

    @Test void ownerRefundUsesCurrentTopicOwnerInsteadOfTheOlderOrdinaryProjection() {
        CmsTopic current = new CmsTopic(); current.setId(7L); current.setMemberId(80L);
        doAnswer(call -> currentRead("topic", current)).when(topics).selectCmsTopicByIdForUpdate(7L);
        RuntimeException denied = assertThrows(RuntimeException.class, () -> team.cancelPaidRegistrationByOwner(1L, 70L));
        assertTrue(denied.getMessage().contains("无权操作"));
        verify(topics, never()).selectCmsTopicById(anyLong()); verifyNoInteractions(refunds);
        assertEquals(1, transactions.rolledBack);
    }

    @Test void legitimateActivityOwnerCancellationStartsAtTopicAndKeepsOriginalEmptySalesResult() {
        assertEquals(0, team.cancelActivityByOwner(5L, 70L, "reason").getRefundedOrders());
        var order = inOrder(topics, activities);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(activities, atLeastOnce()).selectCmsActivityByIdForUpdate(5L);
        order.verify(activities).updateCmsActivity(any());
        verifyNoInteractions(refunds);
        verifyNoInteractions(verifiedPaymentFacts);
        assertEquals(3, transactions.committed); // capture, observation admission, final application
        assertEquals(0, transactions.rolledBack);
        assertEquals(1, phases.stream().filter(value -> value.startsWith("activity-write:")).count());
        assertTrue(phases.indexOf("commit:1") < phases.indexOf("begin:2"));
        assertTrue(phases.indexOf("commit:2") < phases.indexOf("begin:3"));
        for (int phase = 1; phase <= 3; phase++) {
            int topicLock = phases.indexOf("topic:" + phase);
            int activityLock = phases.indexOf("activity:" + phase);
            int registrationLock = phases.indexOf("registrations:" + phase);
            assertTrue(topicLock >= 0 && topicLock < activityLock && activityLock < registrationLock, phases.toString());
        }
        assertTrue(phases.indexOf("registrations:3") < phases.indexOf("activity-write:3"));
    }

    @Test void transferredClubOwnerIsReadUnderCurrentLockBeforeActivityCancellationWrite() {
        activity.setClubId(9L);
        Club old = new Club(); old.setId(9L); old.setMemberId(70L); old.setDelFlag(0);
        Club current = new Club(); current.setId(9L); current.setMemberId(80L); current.setDelFlag(0);
        when(clubs.selectClubById(9L)).thenReturn(old);
        when(clubs.selectClubByIdForUpdate(9L)).thenAnswer(call -> currentRead("club", current));
        assertThrows(RuntimeException.class, () -> team.cancelActivityByOwner(5L, 70L, "reason"));
        verify(clubs).selectClubByIdForUpdate(9L); verify(clubs, never()).selectClubById(9L);
        verify(activities, never()).updateCmsActivity(any()); verifyNoInteractions(refunds);
        assertEquals(1, transactions.rolledBack);
    }

    @Test void standaloneWrapperTakesTopicBeforeSeriesThenDelegatesExactlyOnce() {
        var captured = new RegistrationCancellationPayments(List.of(
                new RegistrationCancellationPayments.Scope(2L, 5L, 7L, 70L, null, List.of())));
        var observed = new RegistrationCancellationPayments(captured.scopes());
        when(delegate.captureCancellationPayments(2L, 5L)).thenAnswer(call -> currentRead("capture", captured));
        when(delegate.observeCancellationPayments(same(captured))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
            assertNull(transactions.current.get());
            phases.add("observe");
            return observed;
        });
        when(delegate.cancelActivityWithObservedPayments(eq(5L), eq(70L), eq(70L), eq("reason"), eq(false), same(observed)))
                .thenAnswer(call -> currentRead("apply", new ActivityCancelResult(0, 0, 0)));
        assertEquals(0, events.cancelActivityByOwnerWithClubWrapUp(70L, 5L, "reason").getRefundedOrders());
        var order = inOrder(topics, ops, activities, delegate);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(ops).selectSeriesByActivityAnyStatusForUpdate(5L);
        order.verify(activities).selectCmsActivityByIdForUpdate(5L);
        order.verify(delegate).captureCancellationPayments(2L, 5L);
        order.verify(delegate).observeCancellationPayments(same(captured));
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(ops).selectSeriesByActivityAnyStatusForUpdate(5L);
        order.verify(activities).selectCmsActivityByIdForUpdate(5L);
        order.verify(delegate).cancelActivityWithObservedPayments(5L, 70L, 70L, "reason", false, observed);
        verify(delegate, never()).cancelActivityByOwner(anyLong(), anyLong(), anyString());
        verifyNoMoreInteractions(delegate);
        assertEquals(List.of("begin:1", "topic:1", "activity:1", "capture:1", "commit:1", "observe",
                "begin:2", "topic:2", "activity:2", "apply:2", "commit:2"), phases);
    }

    @Test void eventOpsChangedParentFailsBeforeClubLockRolesOrRefundDispatch() {
        activity.setClubId(9L);
        CmsActivity changed = new CmsActivity(); changed.setId(5L); changed.setTopicId(8L); changed.setClubId(9L); changed.setMemberId(70L);
        doAnswer(call -> currentRead("activity", changed)).when(activities).selectCmsActivityByIdForUpdate(5L);
        ClubEventSeries series = new ClubEventSeries(); series.setId(61L); series.setClubId(9L); series.setTopicId(7L);
        when(ops.selectSeriesByActivityAnyStatusForUpdate(5L)).thenReturn(series);
        ClubEventCancellationRequest request = new ClubEventCancellationRequest(); request.setClubId(9L); request.setActivityId(5L);
        request.setRequestId("cancel-1"); request.setReason("reason");
        assertThrows(RuntimeException.class, () -> events.cancelOccurrence(70L, "owner", request));
        var order = inOrder(topics, ops, activities);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(ops).selectSeriesByActivityAnyStatusForUpdate(5L);
        order.verify(activities).selectCmsActivityByIdForUpdate(5L);
        verify(ops, never()).selectClubOwnerForUpdate(anyLong()); verifyNoInteractions(delegate);
        assertEquals(1, transactions.rolledBack);
    }

    @Test void authorizedTopicCannotAcquireOrCancelAnotherTopicsActivity() {
        activity.setClubId(9L);
        assertThrows(RuntimeException.class,
                () -> events.cancelActivityForAuthorizedTopicCancellation(70L, 8L, 9L, 5L, "reason"));
        verify(topics, never()).selectCmsTopicByIdForUpdate(anyLong());
        verifyNoInteractions(ops, delegate);
        assertEquals(1, transactions.rolledBack);
    }

    private <T> T currentRead(String operation, T value) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), operation);
        assertTrue(TransactionSynchronizationManager.isSynchronizationActive(), operation);
        UnitTransaction current = transactions.current.get();
        assertNotNull(current, operation);
        assertTrue(current.active, operation);
        phases.add(operation + ":" + current.id);
        return value;
    }

    /** Resource-free unit lifecycle only, not SQL locking/commit/rollback evidence.
     * Spring owns synchronization and TxActive; tests never set those global flags directly. */
    private final class UnitTransactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<UnitTransaction> current = new ThreadLocal<>();
        private int begun, committed, rolledBack;

        @Override protected Object doGetTransaction() {
            return current.get() == null ? new UnitTransaction() : current.get();
        }
        @Override protected boolean isExistingTransaction(Object transaction) {
            return ((UnitTransaction) transaction).active;
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertNull(current.get());
            UnitTransaction value = (UnitTransaction) transaction;
            value.id = ++begun; value.active = true; current.set(value);
            phases.add("begin:" + value.id);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            currentRead("commit", null); committed++;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            currentRead("rollback", null); rolledBack++;
        }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((UnitTransaction) status.getTransaction()).rollbackOnly = true;
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            ((UnitTransaction) transaction).active = false;
            current.remove();
        }
    }

    private static final class UnitTransaction implements SmartTransactionObject {
        private int id;
        private boolean active, rollbackOnly;
        @Override public boolean isRollbackOnly() { return rollbackOnly; }
        @Override public void flush() { }
    }
}
