package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.TopicSettlementCashBasis;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual producer -> actual original ledger; reuse the existing G mapper-double fixture, not a success-returning owner double. */
class CoopSettlementOriginalProducerTest {
    CoopSettlementOriginalLedgerTest fixture;
    CoopSettlementOriginalProducer producer;
    CmsTopic topic;
    CmsRegistration registration;
    TopicSettlementCashBasis basis;
    @BeforeEach void setup() {
        fixture=new CoopSettlementOriginalLedgerTest();fixture.arrangeOriginalFrozenOrder();
        producer=new CoopSettlementOriginalProducer(fixture.topics,fixture.originals,fixture.evidence,fixture.owner);
        topic=new CmsTopic();topic.setId(11L);topic.setMemberId(7L);topic.setFeePlatformRate(new BigDecimal("0.10"));
        topic.setFeeGuaranteeBaseRate(BigDecimal.ZERO);topic.setFeeGuaranteeStepRate(BigDecimal.ZERO);topic.setFeeGuaranteeMaxMerchant(0);
        when(fixture.topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topic);
        registration=new CmsRegistration();registration.setId(1L);registration.setOwnerType(1L);registration.setOwnerId(11L);
        registration.setMemberId(77L);registration.setOrderNum(1L);registration.setRegistrationNo("REG1");registration.setTransactionId("WXTX1");
        registration.setPaymentStatus(2);registration.setRegistrationStatus(2);registration.setVerificationStatus(0);
        registration.setPayableAmount(new BigDecimal("10.00"));registration.setWechatPaymentAmount(new BigDecimal("10.00"));
        basis=TopicSettlementCashBasis.fromLockedRows(11L,List.of(registration));
        when(fixture.originals.selectOriginalRegistrationsForUpdate(11L)).thenReturn(List.of(registration));
        when(fixture.originals.selectPaymentVerifiedReferenceForUpdate(1L)).thenReturn(fixture.paymentReference);
        when(fixture.evidence.selectCloseForUpdate(11L)).thenReturn(null);
        when(fixture.evidence.recordClose(any())).thenAnswer(call->{CoopSettlementCashClose row=call.getArgument(0);row.setId(1L);return row;});
        when(fixture.originals.bindHeadToClose(anyLong(),eq(1L),eq(11L),anyString())).thenReturn(1);
        when(fixture.originals.insertComponent(any())).thenAnswer(call->{CoopSettlementOriginalComponent row=call.getArgument(0);row.setId(row.getSettlementId()+100);return 1;});
    }
    @AfterEach void clear() {TransactionSynchronizationManager.clear();}
    @Test void actualOwnerFreezesExactPersistedAmountsAndReferencesWithoutRecalculation() {
        assertEquals("FROZEN_EXACT_SINGLE_ORDER",producer.freezeSingleOrder(topic,basis,new Date(1700000000123L)));
        var captor=org.mockito.ArgumentCaptor.forClass(CoopSettlementOriginalComponent.class);
        verify(fixture.originals,times(2)).insertComponent(captor.capture());
        assertEquals(List.of(new BigDecimal("1.00"),new BigDecimal("9.00")),captor.getAllValues().stream().map(CoopSettlementOriginalComponent::getOriginalAmount).toList());
        assertTrue(captor.getAllValues().stream().allMatch(row->row.getPaymentFactId().equals(fixture.paymentReference.id()) && row.getSourceClaimId()==null));
        verifyNoInteractions(fixture.members,fixture.ledger,fixture.debtRows);
    }
    @Test void multipleOrdersAreExplicitlyNotEnabledAndDoNotFreezeAnything() {
        when(fixture.originals.selectOriginalRegistrationsForUpdate(11L)).thenReturn(List.of(registration,registration));
        assertEquals("NOT_ENABLED_ORDER_SET_REQUIRES_ALLOCATION",producer.freezeSingleOrder(topic,basis,new Date()));
        verify(fixture.evidence,never()).recordClose(any());verify(fixture.originals,never()).insertComponent(any());
    }
    @Test void depositSetoffCannotBeReclassifiedAsOrderCash() {
        fixture.platform.setLineType("DEPOSIT_DEDUCTION");
        assertEquals("NOT_ENABLED_DEPOSIT_SETOFF_ORIGINAL_ALLOCATION",producer.freezeSingleOrder(topic,basis,new Date()));
        verify(fixture.evidence,never()).recordClose(any());verify(fixture.originals,never()).insertComponent(any());
    }
    @Test void missingPaymentReceiptAndFrozenRatesDoNotCreateFallbackOriginals() {
        when(fixture.originals.selectPaymentVerifiedReferenceForUpdate(1L)).thenReturn(null);
        assertEquals("NOT_ENABLED_ORIGINAL_PAYMENT_RECEIPT_MISSING",producer.freezeSingleOrder(topic,basis,new Date()));
        topic.setFeeGuaranteeStepRate(null);
        assertEquals("NOT_ENABLED_FROZEN_TOPIC_FIELDS_MISSING",producer.freezeSingleOrder(topic,basis,new Date()));
        verify(fixture.evidence,never()).recordClose(any());verify(fixture.originals,never()).insertComponent(any());
    }
    @Test void multiSeatAndMixedPaymentRemainExplicitlyOutsideThisOneToOneSlice() {
        registration.setOrderNum(2L);
        assertEquals("NOT_ENABLED_PARENT_OR_MULTI_SEAT",producer.freezeSingleOrder(topic,basis,new Date()));
        registration.setOrderNum(1L);registration.setWechatPaymentAmount(new BigDecimal("9.00"));
        assertEquals("NOT_ENABLED_MIXED_OR_UNPROVEN_CASH",producer.freezeSingleOrder(topic,basis,new Date()));
        verify(fixture.evidence,never()).recordClose(any());verify(fixture.originals,never()).insertComponent(any());
    }
    @Test void missingOuterFundsTransactionCannotReadOrWriteProducerState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertEquals("ORIGINAL_PRODUCER_TRANSACTION_REQUIRED",assertThrows(IllegalStateException.class,()->producer.freezeSingleOrder(topic,basis,new Date())).getMessage());
        verify(fixture.originals,never()).selectOriginalRegistrationsForUpdate(anyLong());verify(fixture.evidence,never()).recordClose(any());
    }
}
