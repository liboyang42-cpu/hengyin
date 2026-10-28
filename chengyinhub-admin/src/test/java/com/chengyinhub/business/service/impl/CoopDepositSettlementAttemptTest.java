package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit boundary only; real gateway/Mapper/transaction coverage is in CoopDepositSettlementAttemptMySqlIT. */
class CoopDepositSettlementAttemptTest {
    private static final String BUSINESS = "DEP89T100";
    private static final String ATTEMPT = "WXP00000000000000000000000000001";
    private static final BigDecimal AMOUNT = new BigDecimal("50.00");
    private CmsTopicServiceImpl topic;
    private IWechatPayService gateway;
    private ICoopInviteService invites;
    private CoopInviteMapper mapper;
    private CoopInvite row;
    private WechatVerifiedPaymentFactService verified;
    private com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact paymentProof;
    private com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference paymentReference;

    @BeforeEach
    void setup() {
        topic = new CmsTopicServiceImpl();
        gateway = mock(IWechatPayService.class);
        invites = mock(ICoopInviteService.class);
        mapper = mock(CoopInviteMapper.class);
        ReflectionTestUtils.setField(topic, "wechatPayService", gateway);
        ReflectionTestUtils.setField(topic, "coopInviteService", invites);
        ReflectionTestUtils.setField(topic, "coopInviteMapper", mapper);
        verified = mock(WechatVerifiedPaymentFactService.class);
        paymentProof = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,
                "merchant", "wx-original", BUSINESS, ATTEMPT, ATTEMPT, "WXTX89", "WXTX89", "JSAPI", "CNY",
                5000, 5000, java.time.Instant.ofEpochSecond(1700000000), "a".repeat(64), "SUCCESS");
        paymentReference = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(7L, paymentProof.factHash());
        when(verified.requireVerified(paymentReference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(paymentReference, paymentProof));
        when(verified.requireConsumablePayment(paymentReference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(paymentReference, paymentProof));
        when(verified.findPaymentRecovery(paymentReference)).thenReturn(java.util.Optional.empty());
        row = new CoopInvite();
        row.setId(89L); row.setDepositStatus(1); row.setDepositOutTradeNo(BUSINESS); row.setDepositAmount(AMOUNT);
        row.setTopicId(11L); row.setStatus(1);
        when(mapper.selectCoopInviteByIdForUpdate(89L)).thenReturn(row);
        var topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        var topicRow = new com.chengyinhub.business.domain.CmsTopic(); topicRow.setId(11L);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topicRow);
        ReflectionTestUtils.setField(topic, "cmsTopicMapper", topics);
        // Unit lifecycle only, no database/row-lock claim.
        ReflectionTestUtils.setField(topic, "transactionManager", new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) { }
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        });
    }

    @Test
    void payerrorClosesExactObservedAttemptThenFencesBeforeUnpaidCas() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR"));
        when(gateway.closePaymentAttempt(BUSINESS, ATTEMPT)).thenReturn(true);
        when(gateway.queryOrderStatus(ATTEMPT)).thenReturn(facts("CLOSED"));
        when(gateway.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        when(mapper.updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT)).thenReturn(1);
        assertTrue(resolve());
        var order = inOrder(gateway, mapper);
        order.verify(gateway).queryOrderStatus(BUSINESS);
        order.verify(gateway).closePaymentAttempt(BUSINESS, ATTEMPT);
        order.verify(gateway).queryOrderStatus(ATTEMPT);
        order.verify(gateway).lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        order.verify(mapper).updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT);
        verify(gateway, never()).closeOrder(anyString());
        assertEquals(5, row.getDepositStatus());
        assertEquals(BUSINESS, row.getDepositOutTradeNo());
    }

    @Test
    void closedObservationStillRequiresCurrentAttemptFence() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("CLOSED"));
        when(gateway.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        when(mapper.updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT)).thenReturn(1);
        assertTrue(resolve());
        var order = inOrder(gateway, mapper);
        order.verify(gateway).lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        order.verify(mapper).updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT);
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
    }

    @Test
    void noneOfTheUnpaidObservationsPermitsCasWhenClosureIsUnconfirmed() {
        for (String state : new String[]{"PAYERROR", "NOTPAY", "USERPAYING"}) {
            when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts(state));
            assertThrows(IllegalStateException.class, this::resolve);
            unchanged();
        }
        verifyNoInteractions(mapper, invites);
        verify(gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
    }

    @Test
    void closeExceptionAndUnknownOrRevokedNeverReleaseDeposit() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR"));
        when(gateway.closePaymentAttempt(BUSINESS, ATTEMPT)).thenThrow(new IllegalStateException("unknown"));
        assertThrows(IllegalStateException.class, this::resolve);
        for (String state : new String[]{"UNKNOWN", "REVOKED"}) {
            when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts(state));
            assertThrows(IllegalStateException.class, this::resolve);
        }
        unchanged();
        verifyNoInteractions(mapper, invites);
    }

    @Test
    void missingAttemptIdentityRejectsEvenSuccessWithoutDomainMutation() {
        for (String state : new String[]{"PAYERROR", "CLOSED", "SUCCESS"}) {
            Map<String, String> missing = facts(state);
            missing.remove("wx_out_trade_no");
            when(gateway.queryOrderStatus(BUSINESS)).thenReturn(missing);
            assertThrows(IllegalStateException.class, this::resolve);
        }
        unchanged();
        verifyNoInteractions(mapper, invites);
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
    }

    @Test
    void closedOldAttemptCannotCasTheCurrentDeposit() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("CLOSED"));
        assertThrows(IllegalStateException.class, this::resolve);
        unchanged();
        verify(gateway).lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        verify(mapper).selectCoopInviteByIdForUpdate(89L);
        verify(mapper, never()).updateDepositIfCurrent(anyLong(), anyInt(), any(), anyInt(), any());
        verifyNoInteractions(invites);
    }

    @Test
    void lateSuccessAfterUnconfirmedCloseUsesOriginalPaidReadbackOnRetry() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR"), facts("SUCCESS"));
        assertThrows(IllegalStateException.class, this::resolve);
        unchanged();
        CoopInvite paid = new CoopInvite(); paid.setId(89L); paid.setDepositStatus(2); paid.setTopicId(11L);
        paid.setStatus(1); paid.setDepositOutTradeNo(BUSINESS); paid.setDepositAmount(AMOUNT); paid.setDepositPaidTransactionId("WXTX89");
        when(gateway.lockPaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        when(invites.markDepositPaid(BUSINESS, 5000L, "WXTX89", paymentReference)).thenReturn(true);
        when(mapper.selectCoopInviteByIdForUpdate(89L)).thenReturn(row, paid);
        assertFalse(resolve());
        verify(invites).markDepositPaid(BUSINESS, 5000L, "WXTX89", paymentReference);
        verify(mapper, times(2)).selectCoopInviteByIdForUpdate(89L);
        verify(mapper, never()).updateDepositIfCurrent(anyLong(), anyInt(), any(), anyInt(), any());
    }

    @Test
    void successStillRequiresFrozenPaidReadbackAndCannotEnterUnpaidCas() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("SUCCESS"));
        when(gateway.lockPaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        when(invites.markDepositPaid(BUSINESS, 5000L, "WXTX89", paymentReference)).thenReturn(true);
        when(mapper.selectCoopInviteByIdForUpdate(89L)).thenReturn(row);
        assertThrows(IllegalStateException.class, this::resolve);
        unchanged();
        verify(mapper, never()).updateDepositIfCurrent(anyLong(), anyInt(), any(), anyInt(), any());
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
    }

    @Test
    void failedUnpaidCasDoesNotMutateInMemoryReceipt() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("CLOSED"));
        when(gateway.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        assertThrows(IllegalStateException.class, this::resolve);
        unchanged();
    }

    @Test
    void providerIoIsOutsideAndCurrentAttemptCasIsInsideTheShortTransaction() {
        when(gateway.queryOrderStatus(BUSINESS)).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return facts("PAYERROR");
        });
        when(gateway.closePaymentAttempt(BUSINESS, ATTEMPT)).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        when(gateway.queryOrderStatus(ATTEMPT)).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return facts("CLOSED");
        });
        when(gateway.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        when(mapper.updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT)).thenReturn(1);
        assertTrue(resolve());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void anActiveOuterTransactionIsRejectedBeforeAnyProviderIo() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, this::resolve);
            verifyNoInteractions(gateway, mapper, invites);
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
        unchanged();
    }

    @Test
    void changedOriginalScopeOrAmountCannotConsumeAnOutsideObservation() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("CLOSED"));
        CoopInvite changed = new CoopInvite(); changed.setId(89L); changed.setTopicId(99L); changed.setStatus(1);
        changed.setDepositStatus(1); changed.setDepositOutTradeNo(BUSINESS); changed.setDepositAmount(AMOUNT);
        when(mapper.selectCoopInviteByIdForUpdate(89L)).thenReturn(changed);
        assertThrows(IllegalStateException.class, this::resolve);
        changed.setTopicId(11L); changed.setDepositAmount(new BigDecimal("49.00"));
        assertThrows(IllegalStateException.class, this::resolve);
        verify(mapper, never()).updateDepositIfCurrent(anyLong(), anyInt(), any(), anyInt(), any());
        verify(gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
        unchanged();
    }

    @Test
    void aNewPendingPrepayInTheGapBlocksEvenTheZeroSalesRefundClose() {
        when(mapper.selectAcceptedByTopicForUpdate(11L)).thenReturn(java.util.List.of(row));
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(topic, "refundTopicDeposits", 11L));
        verify(invites, never()).requestTopicDepositRefunds(anyLong());
        verifyNoInteractions(gateway);
        unchanged();
    }

    @Test
    void inviteQueryWrapperRejectsOuterTransactionWithoutProviderOrBusinessRead() {
        CoopInviteServiceImpl owner = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(owner, "coopInviteMapper", mapper);
        ReflectionTestUtils.setField(owner, "wechatPayService", gateway);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> owner.markDepositPaid(BUSINESS));
            verifyNoInteractions(gateway, mapper);
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test
    void originalSuccessReceiptCompletesOnlyItsRecoveryTicketInTheSameTransaction() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("SUCCESS"));
        when(gateway.lockPaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        useRealTypedDepositOwner();
        when(verified.findPaymentRecovery(paymentReference)).thenReturn(java.util.Optional.of(
                new WechatVerifiedPaymentFactService.RecoveryTicket(17L,89L,
                        new WechatVerifiedPaymentFactService.Stored(paymentReference,paymentProof),4,"VERIFIED_SUCCESS_LOCAL_CONFLICT")));
        doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2,row.getDepositStatus()); assertEquals("WXTX89",row.getDepositPaidTransactionId()); return null;
        }).when(verified).completePaymentRecovery(paymentReference,89L);
        assertFalse(resolve());
        verify(verified).completePaymentRecovery(paymentReference,89L);
        verify(mapper, never()).updateDepositIfCurrent(anyLong(), anyInt(), any(), anyInt(), any());
    }

    @Test
    void anotherBusinessRecoveryTicketCannotBeAcknowledgedByThisDeposit() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("SUCCESS"));
        when(gateway.lockPaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        useRealTypedDepositOwner();
        when(verified.findPaymentRecovery(paymentReference)).thenReturn(java.util.Optional.of(
                new WechatVerifiedPaymentFactService.RecoveryTicket(17L,90L,
                        new WechatVerifiedPaymentFactService.Stored(paymentReference,paymentProof),4,"VERIFIED_SUCCESS_LOCAL_CONFLICT")));
        assertThrows(IllegalStateException.class,this::resolve);
        verify(verified,never()).completePaymentRecovery(any(),anyLong());
        // Unit doubles do not roll back mutable row objects; the MySQL case verifies actual rollback.
    }

    private CoopInviteServiceImpl useRealTypedDepositOwner() {
        CoopInviteServiceImpl owner = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(owner,"coopInviteMapper",mapper);
        ReflectionTestUtils.setField(owner,"depositVerifiedFacts",verified);
        ReflectionTestUtils.setField(topic,"coopInviteService",owner);
        when(mapper.selectByDepositOutTradeNoForUpdate(BUSINESS)).thenReturn(row);
        when(mapper.markDepositPaidConfirmed(89L,BUSINESS,AMOUNT,"WXTX89")).thenAnswer(call -> {
            row.setDepositStatus(2);row.setDepositPaidTransactionId("WXTX89");return 1;
        });
        return owner;
    }

    @Test
    void typedEntryRejectsOldReferenceAfterAttemptSwitchWithoutTouchingReceiptOrRecovery() {
        var owner = useRealTypedDepositOwner();
        when(verified.requireConsumablePayment(paymentReference)).thenThrow(new WechatVerifiedPaymentFactService.ConflictException());
        var mutation = new org.springframework.transaction.support.TransactionTemplate(
                (org.springframework.transaction.PlatformTransactionManager)ReflectionTestUtils.getField(topic,"transactionManager"));
        assertThrows(IllegalStateException.class,()->mutation.execute(status -> owner.markDepositPaid(BUSINESS,5000L,"WXTX89",paymentReference)));
        unchanged();verify(mapper,never()).markDepositPaidConfirmed(any(),any(),any(),any());
        verify(verified,never()).completePaymentRecovery(any(),anyLong());
        assertEquals(paymentReference,verified.requireVerified(paymentReference).reference());
    }

    @Test
    void directTypedEntryAcceptsCurrentReferenceAndExactReplayWithoutASecondCas() {
        var owner = useRealTypedDepositOwner();
        var mutation = new org.springframework.transaction.support.TransactionTemplate(
                (org.springframework.transaction.PlatformTransactionManager)ReflectionTestUtils.getField(topic,"transactionManager"));
        assertEquals(Boolean.TRUE,mutation.execute(status -> owner.markDepositPaid(BUSINESS,5000L,"WXTX89",paymentReference)));
        assertEquals(Boolean.TRUE,mutation.execute(status -> owner.markDepositPaid(BUSINESS,5000L,"WXTX89",paymentReference)));
        verify(mapper,times(1)).markDepositPaidConfirmed(89L,BUSINESS,AMOUNT,"WXTX89");
        assertEquals(2,row.getDepositStatus());assertEquals("WXTX89",row.getDepositPaidTransactionId());
    }

    private boolean resolve() {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(topic, "resolvePendingDepositPaymentForSettlement", row));
    }

    private void unchanged() {
        assertEquals(1, row.getDepositStatus());
        assertEquals(BUSINESS, row.getDepositOutTradeNo());
        assertEquals(AMOUNT, row.getDepositAmount());
    }

    private Map<String, String> facts(String state) {
        Map<String, String> facts = new HashMap<>();
        facts.put("out_trade_no", BUSINESS); facts.put("wx_out_trade_no", ATTEMPT);
        facts.put("trade_state", state); facts.put("currency", "CNY");
        facts.put("total", "5000"); facts.put("transaction_id", "WXTX89");
        if ("SUCCESS".equals(state)) {
            facts.put("verified_fact_id", Long.toString(paymentReference.id()));
            facts.put("verified_fact_hash", paymentReference.hash());
        }
        return facts;
    }
}
