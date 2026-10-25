package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class CoopDepositPaymentSettlementTest {
    private static final String BUSINESS = "DEP89T100";
    private static final String ATTEMPT = "WXP-original-deposit";
    private static final BigDecimal AMOUNT = new BigDecimal("50.00");
    private CmsTopicServiceImpl service;
    private CoopInvite invite;
    private CoopInviteMapper mapper;
    private ICoopInviteService invites;
    private IWechatPayService pay;

    @BeforeEach
    void setUp() {
        service = new CmsTopicServiceImpl();
        mapper = mock(CoopInviteMapper.class);
        invites = mock(ICoopInviteService.class);
        pay = mock(IWechatPayService.class);
        ReflectionTestUtils.setField(service, "coopInviteMapper", mapper);
        ReflectionTestUtils.setField(service, "coopInviteService", invites);
        ReflectionTestUtils.setField(service, "wechatPayService", pay);
        invite = new CoopInvite();
        invite.setId(89L); invite.setTopicId(707L); invite.setStatus(1);
        invite.setDepositStatus(1); invite.setDepositOutTradeNo(BUSINESS); invite.setDepositAmount(AMOUNT);
        when(mapper.selectCoopInviteByIdForUpdate(89L)).thenReturn(invite);
        var topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        var currentTopic = new com.chengyinhub.business.domain.CmsTopic(); currentTopic.setId(707L);
        when(topics.selectCmsTopicByIdForUpdate(707L)).thenReturn(currentTopic);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        // Unit transaction lifecycle only; SQL/row-lock guarantees remain the real MySQL suite's job.
        ReflectionTestUtils.setField(service, "transactionManager",
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    protected Object doGetTransaction() { return new Object(); }
                    protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) { }
                    protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
                    protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
                });
        when(mapper.updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT)).thenReturn(1);
        when(pay.lockPaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        when(pay.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOTPAY", "USERPAYING", "PAYERROR", "REVOKED"})
    void onlyOriginalClosedReadbackAndCurrentAttemptPermitSetoff(String state) {
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(facts(state, ATTEMPT));
        // false 也不是终态；后续原号查单的 CLOSED 才是可抵扣证据。
        when(pay.closePaymentAttempt(BUSINESS, ATTEMPT)).thenReturn(false);
        when(pay.queryOrderStatus(ATTEMPT)).thenReturn(facts("CLOSED", ATTEMPT));

        assertTrue(resolve());

        InOrder order = inOrder(pay, mapper);
        order.verify(pay).queryOrderStatus(BUSINESS);
        order.verify(pay).closePaymentAttempt(BUSINESS, ATTEMPT);
        order.verify(pay).queryOrderStatus(ATTEMPT);
        order.verify(pay).lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        order.verify(mapper).updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT);
        assertEquals(5, invite.getDepositStatus());
        assertEquals(AMOUNT, invite.getDepositAmount());
        verify(pay, never()).closeOrder(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOTPAY", "USERPAYING", "PAYERROR", "REVOKED", "UNKNOWN"})
    void closeAcceptedWithoutClosedReadbackLeavesPaymentPending(String readbackState) {
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR", ATTEMPT));
        when(pay.closePaymentAttempt(BUSINESS, ATTEMPT)).thenReturn(true);
        when(pay.queryOrderStatus(ATTEMPT)).thenReturn(facts(readbackState, ATTEMPT));

        assertThrows(IllegalStateException.class, this::resolve);
        assertPendingWithoutSetoff();
        verify(pay, never()).lockPaymentAttemptForUpdate(anyString(), anyString());
    }

    @Test
    void unknownInitialStateDoesNotCloseOrExcludeLaterSuccess() {
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(facts("UNKNOWN", ATTEMPT), paid(ATTEMPT));
        confirmPaid();

        assertThrows(IllegalStateException.class, this::resolve);
        assertPendingWithoutSetoff();
        assertFalse(resolve());
        verify(invites).markDepositPaid(BUSINESS, 5000L, "wx-deposit-paid", paidReference(ATTEMPT));
        verify(pay, never()).closePaymentAttempt(anyString(), anyString());
        verify(pay).lockPaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        verify(pay, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateSuccessAfterCloseAcceptanceOrExceptionFreezesInsteadOfDeducting(boolean closeThrows) {
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR", ATTEMPT));
        if (closeThrows) when(pay.closePaymentAttempt(BUSINESS, ATTEMPT)).thenThrow(new RuntimeException("ORDERPAID"));
        else when(pay.closePaymentAttempt(BUSINESS, ATTEMPT)).thenReturn(true);
        when(pay.queryOrderStatus(ATTEMPT)).thenReturn(paid(ATTEMPT));
        confirmPaid();

        assertFalse(resolve());

        verify(invites).markDepositPaid(BUSINESS, 5000L, "wx-deposit-paid", paidReference(ATTEMPT));
        // Current scope read precedes typed consumption; terminal readback follows it.
        verify(mapper, times(2)).selectCoopInviteByIdForUpdate(89L);
        verify(mapper, never()).updateDepositIfCurrent(any(), any(), any(), any(), any());
        verify(pay).lockPaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        verify(pay, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
    }

    @Test
    void currentAttemptChangePreservesPendingAndNextAttemptSuccessCanStillBeApplied() {
        String successor = "WXP-successor-deposit";
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(facts("NOTPAY", ATTEMPT), paid(successor));
        when(pay.queryOrderStatus(ATTEMPT)).thenReturn(facts("CLOSED", ATTEMPT));
        when(pay.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(false);
        when(pay.lockPaymentAttemptForUpdate(BUSINESS, successor)).thenReturn(true);
        confirmPaid();

        assertThrows(IllegalStateException.class, this::resolve);
        assertPendingWithoutSetoff();
        assertFalse(resolve());
        verify(invites).markDepositPaid(BUSINESS, 5000L, "wx-deposit-paid", paidReference(successor));
        verify(pay, never()).closePaymentAttempt(anyString(), eq(successor));
        verify(pay).lockPaymentAttemptForUpdate(BUSINESS, successor);
        verify(pay, never()).lockClosablePaymentAttemptForUpdate(BUSINESS, successor);
    }

    @Test
    void lateSuccessWithoutCompleteTypedReferenceCannotReachPaidOrUnpaidMutation() {
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR", ATTEMPT));
        when(pay.closePaymentAttempt(BUSINESS, ATTEMPT)).thenReturn(true);
        for (String missing : new String[]{"verified_fact_id", "verified_fact_hash", "malformed_hash"}) {
            Map<String, String> readback = paid(ATTEMPT);
            if ("malformed_hash".equals(missing)) readback.put("verified_fact_hash", "not-a-fact-hash");
            else readback.remove(missing);
            when(pay.queryOrderStatus(ATTEMPT)).thenReturn(readback);
            IllegalStateException rejected = assertThrows(IllegalStateException.class, this::resolve);
            assertTrue(rejected.getMessage().contains("保证金原支付事实引用缺失"));
            assertPendingWithoutSetoff();
            assertEquals(AMOUNT, invite.getDepositAmount());
        }
        verify(invites, never()).markDepositPaid(anyString(), any(), any(), any(Reference.class));
        verify(pay, never()).lockPaymentAttemptForUpdate(anyString(), anyString());
        verify(pay, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"business", "attempt", "missing"})
    void mismatchedOrMissingOriginalReadbackCannotDeductOrApplyPaidFact(String mismatch) {
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(facts("REVOKED", ATTEMPT));
        Map<String, String> readback = paid(ATTEMPT);
        if ("business".equals(mismatch)) readback.put("out_trade_no", "DEP-another");
        if ("attempt".equals(mismatch)) readback.put("wx_out_trade_no", "WXP-successor");
        if ("missing".equals(mismatch)) readback = null;
        when(pay.queryOrderStatus(ATTEMPT)).thenReturn(readback);

        assertThrows(IllegalStateException.class, this::resolve);
        assertPendingWithoutSetoff();
        verify(invites, never()).markDepositPaid(anyString(), any(), any(), any(Reference.class));
    }

    @Test
    void missingObservedAttemptCannotCloseByResolvingABusinessAlias() {
        Map<String, String> observed = facts("CLOSED", ATTEMPT);
        observed.remove("wx_out_trade_no");
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(observed);

        assertThrows(IllegalStateException.class, this::resolve);
        assertPendingWithoutSetoff();
        verify(pay, never()).closeOrder(anyString());
        verify(pay, never()).closePaymentAttempt(anyString(), anyString());
    }

    @Test
    void alreadyClosedStillLocksTheObservedAttemptBeforePendingToDueCas() {
        when(pay.queryOrderStatus(BUSINESS)).thenReturn(facts("CLOSED", ATTEMPT));
        when(mapper.updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT)).thenReturn(0);

        assertThrows(IllegalStateException.class, this::resolve);
        assertEquals(1, invite.getDepositStatus());
        InOrder order = inOrder(pay, mapper);
        order.verify(pay).lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        order.verify(mapper).updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT);
        verify(pay, never()).closePaymentAttempt(anyString(), anyString());
    }

    private boolean resolve() {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "resolvePendingDepositPaymentForSettlement", invite));
    }

    private void assertPendingWithoutSetoff() {
        assertEquals(1, invite.getDepositStatus());
        verify(mapper, never()).updateDepositIfCurrent(any(), any(), any(), any(), any());
    }

    private void confirmPaid() {
        when(invites.markDepositPaid(eq(BUSINESS), eq(5000L), eq("wx-deposit-paid"), any(Reference.class)))
                .thenAnswer(call -> {
                    assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    invite.setDepositStatus(2); invite.setDepositPaidTransactionId("wx-deposit-paid");
                    return true;
                });
    }

    // Explicit unit evidence identity, not a real gateway/DB verification claim.
    private Reference paidReference(String attempt) {
        WechatVerifiedPaymentFact proof = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "merchant", "wx-original", BUSINESS, attempt, attempt, "wx-deposit-paid", "wx-deposit-paid",
                "JSAPI", "CNY", 5000, 5000, java.time.Instant.parse("2026-10-24T00:00:00Z"),
                "a".repeat(64), "SUCCESS");
        proof.requireValid();
        return new Reference(ATTEMPT.equals(attempt) ? 7L : 8L, proof.factHash());
    }

    private Map<String, String> facts(String state, String attempt) {
        Map<String, String> facts = new HashMap<>();
        facts.put("out_trade_no", BUSINESS); facts.put("wx_out_trade_no", attempt); facts.put("trade_state", state);
        return facts;
    }

    private Map<String, String> paid(String attempt) {
        Map<String, String> facts = facts("SUCCESS", attempt);
        facts.put("currency", "CNY"); facts.put("total", "5000"); facts.put("transaction_id", "wx-deposit-paid");
        Reference reference = paidReference(attempt);
        facts.put("verified_fact_id", Long.toString(reference.id())); facts.put("verified_fact_hash", reference.hash());
        return facts;
    }
}
