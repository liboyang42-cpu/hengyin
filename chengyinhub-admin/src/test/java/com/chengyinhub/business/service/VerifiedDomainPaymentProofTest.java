package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual domain methods with explicit immutable unit facts. Transaction counters are NOT an InnoDB rollback proof. */
class VerifiedDomainPaymentProofTest {
    @Test void missingReferenceRejectsBeforeFactReaderOrBusinessSql() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);assertThrows(IllegalArgumentException.class,()->f.consume(null));
            verifyNoInteractions(f.proof.reader,f.invoices,f.orders,f.clubs,f.members,f.subscriptions,f.audits);
        }
    }
    @Test void unknownHashCannotBeReinterpretedAsAnUnpaidOrder() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);assertThrows(IllegalStateException.class,()->f.consume(new Reference(f.ref.id(),"0".repeat(64))));
            verifyNoInteractions(f.invoices,f.orders,f.clubs,f.members,f.subscriptions,f.audits);
        }
    }
    @Test void validForeignOrderFactRejectsBeforeBusinessMutation() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);var foreign=f.proof.issue("TPLTM_FOREIGN",f.fen,"TX-original");
            assertThrows(IllegalStateException.class,()->f.consume(foreign));
            verifyNoInteractions(f.invoices,f.orders,f.clubs,f.members,f.subscriptions,f.audits);
        }
    }
    @Test void legacyPaidWithoutConsumptionProofCannotBeUpgradedByASuccessObservation() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);f.invoice.setStatus("PAID");f.invoice.setTransactionId("TX-original");
            f.order.setStatus(1L);f.order.setPaymentTransactionId("TX-original");assertFalse(f.consume(f.ref));
            f.assertNoGrantOrCompletion();assertNull(f.invoice.getVerifiedPaymentFactId());assertNull(f.order.getVerifiedPaymentFactId());
        }
    }
    @Test void currentAttemptDenialHasNoPaidGrantOrProofWrite() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);doThrow(new WechatVerifiedPaymentFactService.ConflictException()).when(f.proof.reader).requireConsumablePayment(f.ref);
            assertFalse(f.consume(f.ref));f.assertNoGrantOrCompletion();
            verify(f.invoices,never()).markInvoicePaidCas(any(),anyInt(),anyString(),any());
            verify(f.orders,never()).markCommerceOrderPaid(any(),anyString());
        }
    }
    @Test void successfulApplicationBindsAndReplaysOnlyTheOriginalProofInsideTheBusinessTransaction() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);
            doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(f.ref,call.getArgument(0));assertEquals(Long.valueOf(isClub?100L:8L),call.<Long>getArgument(1));return null;
            }).when(f.proof.reader).completePaymentRecovery(any(),anyLong());
            assertTrue(f.consume(f.ref));assertTrue(f.consume(f.ref));
            verify(f.proof.reader,times(2)).completePaymentRecovery(f.ref,isClub?100L:8L);
            if(isClub){verify(f.invoices,times(1)).insertTerm(any());assertEquals(f.ref.hash(),f.invoice.getVerifiedPaymentFactHash());}
            else{verify(f.subscriptions,times(1)).grantMerchantSubscription(any());assertEquals(f.ref.hash(),f.order.getVerifiedPaymentFactHash());}
            assertEquals(2,f.commits.get());assertEquals(0,f.rollbacks.get());
        }
    }
    @Test void zeroProofBindRowsFailsTheTransactionAndDoesNotCompleteRecovery() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);
            if(isClub)doReturn(0).when(f.invoices).bindInvoicePaymentProof(any(),anyString(),any(),anyString(),any(),anyString());
            else doReturn(0).when(f.orders).bindCommercePaymentProof(any(),anyString(),any(),anyString());
            assertThrows(IllegalStateException.class,()->f.consume(f.ref));assertEquals(1,f.rollbacks.get());
            verify(f.proof.reader,never()).completePaymentRecovery(any(),anyLong());
        }
    }
    @Test void conflictingProofReadbackFailsRatherThanCompletingAnotherReceipt() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);
            if(isClub)doAnswer(call->{f.bindInvoice(call);f.invoice.setVerifiedPaymentFactHash("0".repeat(64));return 1;})
                .when(f.invoices).bindInvoicePaymentProof(any(),anyString(),any(),anyString(),any(),anyString());
            else doAnswer(call->{f.bindOrder(call);f.order.setVerifiedPaymentFactHash("0".repeat(64));return 1;})
                .when(f.orders).bindCommercePaymentProof(any(),anyString(),any(),anyString());
            assertThrows(IllegalStateException.class,()->f.consume(f.ref));assertEquals(1,f.rollbacks.get());
            verify(f.proof.reader,never()).completePaymentRecovery(any(),anyLong());
        }
    }
    @Test void recoveryCompletionFailurePropagatesAndRollsBackTheOriginalBusinessBoundary() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);doThrow(new IllegalStateException("controlled original ticket conflict"))
                .when(f.proof.reader).completePaymentRecovery(f.ref,isClub?100L:8L);
            assertThrows(IllegalStateException.class,()->f.consume(f.ref));assertEquals(1,f.rollbacks.get());assertEquals(0,f.commits.get());
        }
    }

    @Test void boundProofCannotCompleteAClosedOrSupersededAttemptTicket() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);f.invoice.setStatus("PAID");f.invoice.setTransactionId("TX-original");
            f.invoice.setVerifiedPaymentFactId(f.ref.id());f.invoice.setVerifiedPaymentFactHash(f.ref.hash());
            f.order.setStatus(1L);f.order.setPaymentTransactionId("TX-original");
            f.order.setVerifiedPaymentFactId(f.ref.id());f.order.setVerifiedPaymentFactHash(f.ref.hash());
            doThrow(new WechatVerifiedPaymentFactService.ConflictException()).when(f.proof.reader).requireConsumablePayment(f.ref);
            assertFalse(f.consume(f.ref));f.assertNoGrantOrCompletion();
            assertEquals(f.ref.hash(),f.invoice.getVerifiedPaymentFactHash());assertEquals(f.ref.hash(),f.order.getVerifiedPaymentFactHash());
        }
    }
    @Test void sharedCurrentReadFailureIsNeverConvertedToAStaleFalseOrASuccess() {
        for(boolean isClub:new boolean[]{true,false}) {
            var f=new Fixture(isClub);var failure=new org.springframework.dao.DataAccessResourceFailureException("controlled current read unavailable");
            doThrow(failure).when(f.proof.reader).requireConsumablePayment(f.ref);
            assertSame(failure,assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->f.consume(f.ref)));
            f.assertNoGrantOrCompletion();verify(f.invoices,never()).markInvoicePaidCas(any(),anyInt(),anyString(),any());
            verify(f.orders,never()).markCommerceOrderPaid(any(),anyString());assertEquals(1,f.rollbacks.get());
        }
    }

    private static final class Fixture {
        final boolean isClub;final String no;final int fen;final VerifiedPaymentProofFixtures proof=new VerifiedPaymentProofFixtures();final Reference ref;
        final ClubMembershipMapper invoices=mock(ClubMembershipMapper.class);final OmsOrderMapper orders=mock(OmsOrderMapper.class);
        final ClubMapper clubs=mock(ClubMapper.class);final ClubMemberMapper members=mock(ClubMemberMapper.class);
        final MerchantSubscriptionMapper subscriptions=mock(MerchantSubscriptionMapper.class);final BusinessAuditEventMapper audits=mock(BusinessAuditEventMapper.class);
        final IWechatPayService pay=mock(IWechatPayService.class);final ClubMembershipServiceImpl club=new ClubMembershipServiceImpl();
        final MerchantCommerceService commerce=new MerchantCommerceService(false);final ClubMembershipInvoice invoice=new ClubMembershipInvoice();final OmsOrder order=new OmsOrder();
        final AtomicInteger commits=new AtomicInteger(),rollbacks=new AtomicInteger();
        Fixture(boolean isClub) {
            this.isClub=isClub;no=isClub?"CLM"+"a".repeat(29):"TPLTM_PROOF";fen=isClub?9900:1990;ref=proof.issue(no,fen,"TX-original");
            var manager=new AbstractPlatformTransactionManager(){
                protected Object doGetTransaction(){return new Object();}protected void doBegin(Object t,TransactionDefinition d){}
                protected void doCommit(DefaultTransactionStatus s){commits.incrementAndGet();}protected void doRollback(DefaultTransactionStatus s){rollbacks.incrementAndGet();}
            };
            ReflectionTestUtils.setField(club,"transactionManager",manager);ReflectionTestUtils.setField(commerce,"paymentTransactions",manager);
            ReflectionTestUtils.setField(club,"verifiedPaymentFacts",proof.reader);ReflectionTestUtils.setField(commerce,"verifiedPaymentFacts",proof.reader);
            ReflectionTestUtils.setField(club,"clubMembershipMapper",invoices);ReflectionTestUtils.setField(club,"clubMapper",clubs);
            ReflectionTestUtils.setField(club,"clubMemberMapper",members);ReflectionTestUtils.setField(club,"businessAuditEventMapper",audits);
            ReflectionTestUtils.setField(club,"wechatPayService",pay);ReflectionTestUtils.setField(commerce,"wechatPayService",pay);
            ReflectionTestUtils.setField(commerce,"orderMapper",orders);ReflectionTestUtils.setField(commerce,"subscriptionMapper",subscriptions);
            invoice.setId(100L);invoice.setClubId(7L);invoice.setMemberId(70L);invoice.setOwnerMemberIdSnapshot(70L);invoice.setPlanId(9L);
            invoice.setInvoiceNo(no);invoice.setOutTradeNo(no);invoice.setAmountFen((long)fen);invoice.setPlanVersionSnapshot(1);
            invoice.setPlanNameSnapshot("original");invoice.setDurationDaysSnapshot(30);invoice.setBenefitsSnapshot("[]");
            invoice.setGraceDaysSnapshot(0);invoice.setPastDueDaysSnapshot(30);invoice.setExitPolicySnapshot("END_IMMEDIATELY");
            invoice.setRefundPolicySnapshot("MANUAL_REVIEW");invoice.setStatus("PAYING");invoice.setVersion(0);
            invoice.setSnapshotHash(ClubMembershipSnapshotCodec.hash(invoice));
            var owner=new Club();owner.setId(7L);owner.setMemberId(70L);owner.setStatus(1L);owner.setDelFlag(0);
            when(clubs.selectClubByIdForUpdate(7L)).thenReturn(owner);
            when(invoices.selectInvoiceByOutTradeNo(no)).thenReturn(invoice);when(invoices.selectInvoiceByOutTradeNoForUpdate(no)).thenReturn(invoice);
            when(invoices.markInvoicePaidCas(any(),anyInt(),anyString(),any())).thenReturn(1);when(invoices.insertTerm(any())).thenReturn(1);
            when(audits.insertBusinessAuditEvent(any())).thenReturn(1);
            doAnswer(call->{bindInvoice(call);return 1;}).when(invoices).bindInvoicePaymentProof(any(),anyString(),any(),anyString(),any(),anyString());
            order.setId(8L);order.setMemberId(70L);order.setMmsMerchantId(34L);order.setOrderSn(no);order.setBizType("premium_template");
            order.setPayAmount(BigDecimal.valueOf(fen,2));order.setStatus(0L);order.setEntitlementDays(30);order.setEntitlementMaxUsage(3);
            when(orders.selectCommerceOrderForUpdate(no)).thenReturn(order);when(orders.markCommerceOrderPaid(8L,"TX-original")).thenReturn(1);
            when(subscriptions.grantMerchantSubscription(any())).thenReturn(1);
            doAnswer(call->{bindOrder(call);return 1;}).when(orders).bindCommercePaymentProof(any(),anyString(),any(),anyString());
            when(pay.lockPaymentAttemptForUpdate(no,no)).thenReturn(true);
        }
        void bindInvoice(org.mockito.invocation.InvocationOnMock call){invoice.setStatus("PAID");invoice.setTransactionId(call.getArgument(1));invoice.setVerifiedPaymentFactId(call.getArgument(4));invoice.setVerifiedPaymentFactHash(call.getArgument(5));}
        void bindOrder(org.mockito.invocation.InvocationOnMock call){order.setStatus(1L);order.setPaymentTransactionId(call.getArgument(1));order.setVerifiedPaymentFactId(call.getArgument(2));order.setVerifiedPaymentFactHash(call.getArgument(3));}
        boolean consume(Reference reference){return isClub?club.handlePaidInvoice(no,true,fen,"TX-original",reference):commerce.handlePaidOrder(no,true,fen,"TX-original",reference);}
        void assertNoGrantOrCompletion(){verify(invoices,never()).insertTerm(any());verify(subscriptions,never()).grantMerchantSubscription(any());verify(proof.reader,never()).completePaymentRecovery(any(),anyLong());}
    }
}
