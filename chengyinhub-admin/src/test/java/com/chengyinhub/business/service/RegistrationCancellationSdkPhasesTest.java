package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.TeamGroupServiceImpl;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

/** Real Spring transaction interception with memory-only transactions and explicit typed fact doubles.
 * This measures A call ordering/identity/zero local effects, not MySQL or provider verification. */
class RegistrationCancellationSdkPhasesTest {
    private final List<String> events = new ArrayList<>();
    private final RecordingTransactions transactions = new RecordingTransactions(events);
    private final CmsRegistration first = row(1L), second = row(2L);
    private CmsRegistrationMapper rows;
    private CmsActivityMapper activities;
    private WechatPaymentOperations provider;
    private ICmsRegistrationService registration;
    private WechatVerifiedPaymentFactService verified;
    private ITeamGroupService service;

    @BeforeEach void fixture() {
        rows = mock(CmsRegistrationMapper.class); activities = mock(CmsActivityMapper.class);
        provider = mock(WechatPaymentOperations.class); registration = mock(ICmsRegistrationService.class);
        verified = mock(WechatVerifiedPaymentFactService.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(71L); activity.setMemberId(42L);
        when(activities.selectCmsActivityByIdForUpdate(71L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            events.add("activity-lock"); return activity;
        });
        when(rows.selectCancellationRowsForUpdate(2L,71L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            events.add("registration-lock"); return List.of(first,second);
        });
        when(provider.isReady()).thenReturn(true);
        TeamGroupServiceImpl target = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(target,"cancellationTransactions",transactions);
        ReflectionTestUtils.setField(target,"cmsRegistrationMapper",rows);
        ReflectionTestUtils.setField(target,"cmsActivityMapper",activities);
        ReflectionTestUtils.setField(target,"cmsTopicMapper",mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(target,"wechatPayService",provider);
        ReflectionTestUtils.setField(target,"cmsRegistrationService",registration);
        ReflectionTestUtils.setField(target,"verifiedPaymentFacts",verified);
        ReflectionTestUtils.setField(target,"alarmService",mock(AlarmService.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        service = (ITeamGroupService)proxy.getProxy();
    }

    @Test void captureCommitsBeforeAllQueriesAndCurrentAdmissionCommitsBeforeAllCloses() {
        RegistrationCancellationPayments captured = capture();
        when(provider.queryOrderStatus(anyString())).thenAnswer(call -> query(call.getArgument(0),"NOTPAY"));
        when(provider.closePaymentAttempt(anyString(),anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            events.add("close:"+call.getArgument(0)); return true;
        });

        RegistrationCancellationPayments observed = service.observeCancellationPayments(captured);

        assertEquals(List.of("activity-lock","registration-lock","commit","query:REG-1","query:REG-2",
                "activity-lock","registration-lock","commit","close:REG-1","close:REG-2"),events);
        assertEquals(List.of("REG-1","REG-2"),observed.scopes().getFirst().orders().stream()
                .map(order -> order.original().registrationNo()).toList());
        assertTrue(observed.scopes().getFirst().orders().stream().allMatch(order -> order.confirmedClosed()));
        verifyNoInteractions(registration);
        verify(rows,never()).updateCmsRegistration(any());
        assertEquals(0,transactions.suspensions);
    }

    @Test void outerTransactionIsRejectedBeforeReadinessQueryCloseOrFactRead() {
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                assertThrows(IllegalTransactionStateException.class,
                        () -> service.observeCancellationPayments(new RegistrationCancellationPayments(List.of()))));
        verifyNoInteractions(provider,verified,rows,registration);
        assertEquals(0,transactions.suspensions);
    }

    @Test void unknownSecondOrderNeverClosesFirstOrAppliesPayment() {
        RegistrationCancellationPayments captured = capture();
        when(provider.queryOrderStatus("REG-1")).thenAnswer(call -> query("REG-1","NOTPAY"));
        when(provider.queryOrderStatus("REG-2")).thenAnswer(call -> query("REG-2","UNKNOWN"));

        assertThrows(ServiceException.class,() -> service.observeCancellationPayments(captured));

        verify(provider,never()).closePaymentAttempt(anyString(),anyString());
        verifyNoInteractions(registration);
        verify(rows,times(1)).selectCancellationRowsForUpdate(2L,71L);
    }

    @Test void exactSuccessAdmissionDenialHappensBeforeAnyOtherProviderClose() {
        RegistrationCancellationPayments captured = capture();
        Map<String,String> success = success(first);
        when(provider.queryOrderStatus("REG-1")).thenReturn(success);
        when(provider.queryOrderStatus("REG-2")).thenAnswer(call -> query("REG-2","NOTPAY"));
        when(registration.checkConfirmedWechatPaymentWithinTransaction(success)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return "FAIL";
        });

        assertThrows(ServiceException.class,() -> service.observeCancellationPayments(captured));

        verify(registration).checkConfirmedWechatPaymentWithinTransaction(success);
        verify(registration,never()).applyConfirmedWechatPaymentWithinTransaction(any());
        verify(provider,never()).closePaymentAttempt(anyString(),anyString());
        verify(rows,never()).updateCmsRegistration(any());
        assertEquals("REG-1",captured.scopes().getFirst().orders().getFirst().original().registrationNo());
    }

    @Test void knownSuccessOnLocallyUnpaidOrderRejectsBeforeClosingAnotherOriginalAttempt() {
        second.setPaymentStatus(0);
        RegistrationCancellationPayments captured = capture();
        assertEquals(1,captured.scopes().getFirst().orders().size());
        when(provider.queryOrderStatus("REG-1")).thenAnswer(call -> query("REG-1","NOTPAY"));
        when(verified.hasConfirmedExternalSuccess("REG-2")).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return true;
        });
        when(provider.closePaymentAttempt(anyString(),anyString())).thenReturn(true);

        assertThrows(ServiceException.class,() -> service.observeCancellationPayments(captured));

        verify(verified).hasConfirmedExternalSuccess("REG-2");
        verify(provider,never()).queryOrderStatus("REG-2");
        verify(provider,never()).closePaymentAttempt(anyString(),anyString());
        verifyNoInteractions(registration);
        verify(rows,never()).cancelUnpaidIfActive(anyLong(),anyLong(),anyString());
        verify(rows,never()).updateCmsRegistration(any());
        assertEquals(1,first.getPaymentStatus());
        assertEquals(0,second.getPaymentStatus());
        assertEquals("REG-1",first.getRegistrationNo());
        assertEquals("REG-2",second.getRegistrationNo());
    }

    @Test void knownSuccessBehindNonSuccessObservationRejectsWholeBatchBeforeFirstProviderClose() {
        RegistrationCancellationPayments captured = capture();
        when(provider.queryOrderStatus(anyString())).thenAnswer(call -> query(call.getArgument(0),"NOTPAY"));
        when(verified.hasConfirmedExternalSuccess("REG-2")).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return true;
        });
        when(provider.closePaymentAttempt(anyString(),anyString())).thenReturn(true);

        assertThrows(ServiceException.class,() -> service.observeCancellationPayments(captured));

        verify(provider).queryOrderStatus("REG-1");
        verify(provider).queryOrderStatus("REG-2");
        verify(verified).hasConfirmedExternalSuccess("REG-2");
        verify(provider,never()).closePaymentAttempt(anyString(),anyString());
        verifyNoInteractions(registration);
        verify(rows,never()).cancelUnpaidIfActive(anyLong(),anyLong(),anyString());
        verify(rows,never()).updateCmsRegistration(any());
        assertEquals(1,first.getPaymentStatus());
        assertEquals(1,second.getPaymentStatus());
        assertEquals(List.of("REG-1","REG-2"),captured.scopes().getFirst().orders().stream()
                .map(order -> order.original().registrationNo()).toList());
    }

    @Test void changedOriginalIdentityAfterQueryCannotReachProviderClose() {
        RegistrationCancellationPayments captured = capture();
        when(provider.queryOrderStatus("REG-1")).thenAnswer(call -> {
            first.setMemberId(999L); return query("REG-1","NOTPAY");
        });
        when(provider.queryOrderStatus("REG-2")).thenAnswer(call -> query("REG-2","NOTPAY"));

        assertThrows(ServiceException.class,() -> service.observeCancellationPayments(captured));

        verify(provider,never()).closePaymentAttempt(anyString(),anyString());
        verifyNoInteractions(registration);
        assertEquals(11L,captured.scopes().getFirst().orders().getFirst().original().memberId());
    }

    @Test void newlyAppearedPayingOrderRequiresFreshObservationBeforeAnyClose() {
        RegistrationCancellationPayments captured = capture();
        when(provider.queryOrderStatus(anyString())).thenAnswer(call -> query(call.getArgument(0),"NOTPAY"));
        doReturn(List.of(first,second,row(3L))).when(rows).selectCancellationRowsForUpdate(2L,71L);

        assertThrows(ServiceException.class,() -> service.observeCancellationPayments(captured));

        verify(provider,never()).closePaymentAttempt(anyString(),anyString());
        verifyNoInteractions(registration);
    }

    @Test void closeUnknownPreservesOriginalAliasAndNeverAppliesLocalRelease() {
        RegistrationCancellationPayments captured = capture();
        when(provider.queryOrderStatus(anyString())).thenAnswer(call -> query(call.getArgument(0),"NOTPAY"));
        when(provider.closePaymentAttempt("REG-1","WX-REG-1")).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("fixture transport outcome unknown");
        });

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.observeCancellationPayments(captured));

        assertEquals("CANCELLATION_PROVIDER_CLOSE_UNKNOWN",failure.getDetailMessage());
        verify(provider).closePaymentAttempt("REG-1","WX-REG-1");
        verify(provider,never()).closePaymentAttempt("REG-2","WX-REG-2");
        verifyNoInteractions(registration);
        verify(rows,never()).updateCmsRegistration(any());
        assertEquals(1,first.getPaymentStatus());
        assertEquals("REG-1",first.getRegistrationNo());
    }

    private RegistrationCancellationPayments capture() {
        return new TransactionTemplate(transactions).execute(tx -> service.captureCancellationPayments(2L,71L));
    }
    private Map<String,String> query(String original,String state) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        events.add("query:"+original);
        return Map.of("out_trade_no",original,"wx_out_trade_no","WX-"+original,"trade_state",state);
    }
    private Map<String,String> success(CmsRegistration row) {
        var fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"mch","app",
                row.getRegistrationNo(),"WX-"+row.getRegistrationNo(),"WX-"+row.getRegistrationNo(),
                "TX-1","TX-1","JSAPI","CNY",1000,1000,Instant.parse("2026-10-24T00:00:00Z"),
                "a".repeat(64),"SUCCESS");
        var reference = new WechatVerifiedPaymentFact.Reference(41L,fact.factHash());
        when(verified.requireVerified(reference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(reference,fact));
        Map<String,String> facts = new HashMap<>();
        facts.put("trade_state","SUCCESS"); facts.put("out_trade_no",fact.businessOutTradeNo());
        facts.put("wx_out_trade_no",fact.wxOutTradeNo()); facts.put("transaction_id",fact.transactionId());
        facts.put("mchid",fact.mchid()); facts.put("appid",fact.appid()); facts.put("trade_type",fact.tradeType());
        facts.put("currency",fact.currency()); facts.put("total","1000");
        facts.put("verified_fact_id","41"); facts.put("verified_fact_hash",reference.hash());
        return facts;
    }
    private static CmsRegistration row(Long id) {
        CmsRegistration row = new CmsRegistration();
        row.setId(id); row.setRegistrationNo("REG-"+id); row.setOwnerType(2L); row.setOwnerId(71L);
        row.setMemberId(11L); row.setTicketId(31L); row.setOrderNum(1L);
        row.setWechatPaymentAmount(new BigDecimal("10.00")); row.setPaymentStatus(1); row.setRegistrationStatus(1);
        return row;
    }
    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        private final List<String> events;
        private int suspensions;
        private RecordingTransactions(List<String> events) { this.events=events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
        @Override protected Object doSuspend(Object transaction) {
            suspensions++; throw new AssertionError("cancellation cannot suspend a lock-owning caller");
        }
    }
}
