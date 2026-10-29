package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.impl.RegistrationCheckoutServiceImpl;
import com.chengyinhub.business.task.OrderCloseTask;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Spring interceptor/propagation, with a memory-only transaction manager and narrow business ports.
 * This proves ordering and rejection before effects, never MySQL/provider/SDK acceptance. */
class RegistrationCheckoutTransactionBoundaryTest {
    private final Long member = 11L, registrationId = 1L;
    private ICmsRegistrationService registrations;
    private IRegistrationOrderService orders;
    private WechatPaymentOperations gateway;
    private RegistrationCheckoutServiceImpl target;
    private IRegistrationCheckoutService checkout;
    private RecordingTransactions transactions;
    private List<String> events;

    @BeforeEach void fixture() {
        events = new ArrayList<>(); transactions = new RecordingTransactions(events);
        registrations = mock(ICmsRegistrationService.class); orders = mock(IRegistrationOrderService.class);
        gateway = mock(WechatPaymentOperations.class);
        target = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(target, "registrationService", registrations);
        ReflectionTestUtils.setField(target, "registrationOrderService", orders);
        ReflectionTestUtils.setField(target, "wechatPayService", gateway);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        checkout = (IRegistrationCheckoutService) proxy.getProxy();
    }

    @Test void createCannotSuspendAnOuterTransactionBeforeMinorOrderOrSdk() {
        inCallerTransaction(() -> assertThrows(IllegalTransactionStateException.class,
                () -> checkout.createOrReplay(member, new RegistrationRequest())));
        verifyNoInteractions(orders, registrations, gateway);
        assertEquals(0, transactions.suspensions);
    }

    @Test void jsapiRetryCannotSuspendAnOuterTransactionBeforeAnyBusinessCall() {
        inCallerTransaction(() -> assertThrows(IllegalTransactionStateException.class,
                () -> checkout.retryPayment(member, registrationId)));
        verifyNoInteractions(orders, registrations, gateway);
        assertEquals(0, transactions.suspensions);
    }

    @Test void appRetryCannotSuspendAnOuterTransactionBeforeAnyBusinessCall() {
        inCallerTransaction(() -> assertThrows(IllegalTransactionStateException.class,
                () -> checkout.retryPayment(member, registrationId, true)));
        verifyNoInteractions(orders, registrations, gateway);
        assertEquals(0, transactions.suspensions);
    }

    @Test void directSelfInvocationStillRejectsActiveOuterTransactionBeforeEffects() {
        inCallerTransaction(() -> {
            RegistrationCheckoutException failure = assertThrows(RegistrationCheckoutException.class,
                    () -> target.retryPayment(member, registrationId));
            assertEquals(409, failure.getCode());
        });
        verifyNoInteractions(orders, registrations, gateway);
        assertEquals(0, transactions.suspensions);
    }

    @Test void ordinaryJsapiRetryCommitsTheOriginalClaimBeforeSdk() {
        readyAndClaim();
        when(gateway.createOrder(any())).thenAnswer(call -> sdk(call.getArgument(0), "JSAPI"));

        RegistrationCheckoutResult result = checkout.retryPayment(member, registrationId);

        assertEquals(List.of("claim", "commit", "sdk:JSAPI"), events);
        assertEquals("REG-PHASE-1", result.getData().get("registrationNo"));
        verify(gateway, never()).createAppOrder(any());
        verify(registrations, never()).releasePaymentAttempt(anyLong());
        assertEquals(0, transactions.suspensions);
    }

    @Test void ordinaryAppRetryCommitsTheSameOriginalClaimBeforeSdk() {
        readyAndClaim();
        when(gateway.createAppOrder(any())).thenAnswer(call -> sdk(call.getArgument(0), "APP"));

        RegistrationCheckoutResult result = checkout.retryPayment(member, registrationId, true);

        assertEquals(List.of("claim", "commit", "sdk:APP"), events);
        assertEquals("REG-PHASE-1", result.getData().get("registrationNo"));
        verify(gateway, never()).createOrder(any());
        verify(registrations, never()).releasePaymentAttempt(anyLong());
        assertEquals(0, transactions.suspensions);
    }

    @Test void ordinaryCreationCommitsOrderAndClaimBeforeSdkWithoutChangingIdentity() {
        readyAndClaim();
        RegistrationRequest request = new RegistrationRequest();
        when(orders.createOrReplay(member, request)).thenAnswer(call -> new TransactionTemplate(transactions).execute(tx -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            events.add("order");
            return new RegistrationOrderResult(List.of(original()), false);
        }));
        when(gateway.createOrder(any())).thenAnswer(call -> sdk(call.getArgument(0), "JSAPI"));

        RegistrationCheckoutResult result = checkout.createOrReplay(member, request);

        assertEquals(List.of("order", "commit", "claim", "commit", "sdk:JSAPI"), events);
        assertEquals("REG-PHASE-1", result.getData().get("registrationNo"));
        verify(registrations, never()).releasePaymentAttempt(anyLong());
        assertEquals(0, transactions.suspensions);
    }

    @Test void reconciliationRefusesOuterTransactionBeforeScanningOrCallingProvider() {
        OrderCloseTask task = new OrderCloseTask();
        ReflectionTestUtils.setField(task, "cmsRegistrationService", registrations);
        ReflectionTestUtils.setField(task, "wechatPayService", mock(IWechatPayService.class));
        inCallerTransaction(() -> assertThrows(IllegalStateException.class, task::reconcileExpiredPaying));
        verifyNoInteractions(registrations);
        IWechatPayService provider = (IWechatPayService)ReflectionTestUtils.getField(task, "wechatPayService");
        verifyNoInteractions(provider);
    }

    @Test void zeroRetryCommitsActorBoundCompletionWithoutConsultingOrCallingSdk() {
        CmsRegistration zero = original();
        zero.setPayableAmount(BigDecimal.ZERO); zero.setWechatPaymentAmount(BigDecimal.ZERO);
        zero.setPaymentStatus(2); zero.setRegistrationStatus(2);
        when(registrations.completeZeroAmountPaymentForRetry(registrationId, member)).thenAnswer(call ->
                new TransactionTemplate(transactions).execute(tx -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    events.add("zero-completion"); return List.of(zero);
                }));
        RegistrationCheckoutResult result = checkout.retryPayment(member, registrationId, true);
        assertEquals(List.of("zero-completion", "commit"), events);
        assertEquals("REG-PHASE-1", result.getData().get("registrationNo"));
        assertEquals(List.of(registrationId), result.getData().get("registrationIds"));
        assertEquals(2, result.getData().get("paymentStatus"));
        assertEquals(2, result.getData().get("registrationStatus"));
        assertFalse(result.getData().containsKey("payParams"));
        verifyNoInteractions(gateway, orders);
        verify(registrations, never()).beginPaymentAttempt(anyLong(), anyLong());
        verify(registrations, never()).releasePaymentAttempt(anyLong());
        assertEquals(0, transactions.suspensions);
    }

    @Test void paidSdkNotReadyRejectsBeforeClaimAndPreservesOriginalAttempt() {
        when(registrations.completeZeroAmountPaymentForRetry(registrationId, member)).thenReturn(null);
        when(gateway.isReady()).thenReturn(false);
        RegistrationCheckoutException rejected = assertThrows(RegistrationCheckoutException.class,
                () -> checkout.retryPayment(member, registrationId));
        assertEquals(503, rejected.getCode());
        var order = inOrder(registrations, gateway);
        order.verify(registrations).completeZeroAmountPaymentForRetry(registrationId, member);
        order.verify(gateway).isReady();
        verify(registrations, never()).beginPaymentAttempt(anyLong(), anyLong());
        verify(registrations, never()).releasePaymentAttempt(anyLong());
        verify(gateway, never()).createOrder(any()); verify(gateway, never()).createAppOrder(any());
        assertTrue(events.isEmpty());
        assertEquals(0, transactions.suspensions);
    }

    @Test void restoreDispatchFenceBlocksZeroRetryBeforeReadingOrCompletingAnyOrder() {
        try (var guard = mockStatic(com.chengyinhub.common.config.RestoreDispatchGuard.class)) {
            guard.when(com.chengyinhub.common.config.RestoreDispatchGuard::requireDispatchAllowed)
                    .thenThrow(new com.chengyinhub.common.config.RestoreDispatchGuard.Blocked());
            assertThrows(com.chengyinhub.common.config.RestoreDispatchGuard.Blocked.class,
                    () -> checkout.retryPayment(member, registrationId, true));
            verifyNoInteractions(registrations, orders, gateway);
            assertTrue(events.isEmpty());
            assertEquals(0, transactions.suspensions);
        }
    }

    private void readyAndClaim() {
        // Explicit positive-cash classification seam; the real locked bridge is covered by the MySQL suite.
        when(registrations.completeZeroAmountPaymentForRetry(registrationId, member)).thenReturn(null);
        when(gateway.isReady()).thenReturn(true);
        when(registrations.beginPaymentAttempt(registrationId, member)).thenAnswer(call ->
                new TransactionTemplate(transactions).execute(tx -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    events.add("claim"); return original();
                }));
    }

    private CmsRegistration original() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(registrationId); registration.setMemberId(member); registration.setOwnerType(2L);
        registration.setRegistrationNo("REG-PHASE-1"); registration.setPayableAmount(new BigDecimal("10.00"));
        registration.setWechatPaymentAmount(new BigDecimal("10.00")); registration.setPaymentStatus(1);
        return registration;
    }

    private Map<String, String> sdk(CmsRegistration original, String channel) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(registrationId, original.getId()); assertEquals("REG-PHASE-1", original.getRegistrationNo());
        assertEquals(new BigDecimal("10.00"), original.getWechatPaymentAmount());
        events.add("sdk:" + channel); return Map.of("fixture", channel);
    }

    private void inCallerTransaction(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); action.run();
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        private final List<String> events;
        private int suspensions;
        private RecordingTransactions(List<String> events) { this.events = events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
        @Override protected Object doSuspend(Object transaction) {
            suspensions++; throw new AssertionError("checkout must not suspend a lock-owning caller");
        }
    }
}
