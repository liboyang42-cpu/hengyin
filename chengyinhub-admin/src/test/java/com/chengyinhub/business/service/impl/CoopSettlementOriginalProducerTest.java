package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual G4d allocation owner; storage/provider/consumption ports are explicit units, not MySQL proof. */
class CoopSettlementOriginalProducerTest {
    final CmsTopicMapper topics=mock(CmsTopicMapper.class);
    final CoopSettlementOriginalMapper originals=mock(CoopSettlementOriginalMapper.class);
    final CoopSettlementCashEvidenceMapper cash=mock(CoopSettlementCashEvidenceMapper.class);
    final CoopSettlementOriginalLedger ledger=mock(CoopSettlementOriginalLedger.class);
    final RegistrationPaymentConsumptionReadService consumed=mock(RegistrationPaymentConsumptionReadService.class);
    final WechatVerifiedPaymentFactService facts=mock(WechatVerifiedPaymentFactService.class);
    final RegistrationSeatMapper seats=mock(RegistrationSeatMapper.class);
    final CoopSettlementManualCases manual=mock(CoopSettlementManualCases.class);
    final CoopSettlementOriginalProducer producer=new CoopSettlementOriginalProducer(topics,originals,cash,ledger,consumed,facts,seats,manual);
    final CmsTopic topic=new CmsTopic();
    List<CmsRegistration> registrations;
    List<CoopSettlement> heads;
    @BeforeEach void originals() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        topic.setId(11L);topic.setMemberId(7L);topic.setIsSettlement(1);topic.setFeePlatformRate(new BigDecimal("0.10"));
        topic.setFeeGuaranteeBaseRate(new BigDecimal("0.05"));topic.setFeeGuaranteeStepRate(new BigDecimal("0.02"));topic.setFeeGuaranteeMaxMerchant(5);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topic);
        registrations=new ArrayList<>(List.of(registration(1L,"10.00"),registration(2L,"10.00")));
        when(originals.selectOriginalRegistrationsForUpdate(11L)).thenAnswer(c->registrations);
        when(seats.selectByRegistrationForUpdate(anyLong())).thenReturn(List.of());
        when(consumed.findConsumedPayment(anyLong())).thenReturn(Optional.empty());
        for(CmsRegistration row:registrations) {
            var payment=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"mch","app",row.getRegistrationNo(),row.getRegistrationNo(),row.getRegistrationNo(),
                    row.getTransactionId(),row.getTransactionId(),"APP","CNY",1000,1000,java.time.Instant.parse("2026-10-24T00:00:00Z"),null,"SUCCESS");
            var ref=new WechatVerifiedPaymentFact.Reference(30L+row.getId(),payment.factHash());
            when(originals.selectPaymentVerifiedReferenceForUpdate(row.getId())).thenReturn(ref);
            when(facts.requireVerified(ref)).thenReturn(new WechatVerifiedPaymentFactService.Stored(ref,payment));
        }
        heads=new ArrayList<>(List.of(head(1L,"PLATFORM",null,"2"),head(2L,"INITIATOR_GUARANTEE",7L,"1"),head(3L,"INITIATOR_MARGIN",7L,"17")));
        when(originals.selectTopicHeadsForUpdate(11L)).thenAnswer(c->heads);
    }
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    @Test void positiveFrozenHeadsProduceConservedOriginalOrderComponents() {
        producer.freezeOriginals(topic,basis(),new Date(5000));
        ArgumentCaptor<List<CoopSettlementOriginalComponent>> components=ArgumentCaptor.forClass(List.class);
        verify(ledger).freezeOriginals(any(),anyList(),components.capture());
        assertEquals(new BigDecimal("20.00"),components.getValue().stream().map(CoopSettlementOriginalComponent::getOriginalAmount).reduce(new BigDecimal("0.00"),BigDecimal::add));
        for(long id:List.of(1L,2L)) assertEquals(new BigDecimal("10.00"),components.getValue().stream().filter(c->c.getPayerRegistrationId()==id)
                .map(CoopSettlementOriginalComponent::getOriginalAmount).reduce(new BigDecimal("0.00"),BigDecimal::add));
        assertTrue(components.getValue().stream().filter(c->"PLATFORM".equals(c.getLineType())).allMatch(c->c.getReceiverMemberId()==null && "ACCOUNTING".equals(c.getReceiverKind()) && c.getSourceClaimId()==null));
    }
    @Test void inlineConsumedReferenceIsUsedWithoutHistoricalInboxLookup() {
        var ref=originals.selectPaymentVerifiedReferenceForUpdate(1L);
        when(consumed.findConsumedPayment(1L)).thenReturn(Optional.of(new RegistrationPaymentFactReceipt(1L,"REG1",ref.id(),ref.hash(),"TX1")));
        clearInvocations(originals);
        producer.freezeOriginals(topic,basis(),new Date(5000));
        verify(originals,never()).selectPaymentVerifiedReferenceForUpdate(1L);verify(ledger).freezeOriginals(any(),anyList(),anyList());
    }
    @Test void missingOriginalPaymentReceiptCannotFreezeAHead() {
        when(originals.selectPaymentVerifiedReferenceForUpdate(1L)).thenReturn(null);
        assertThrows(IllegalStateException.class,()->producer.freezeOriginals(topic,basis(),new Date(5000)));
        verifyNoInteractions(ledger);
    }
    @Test void merchantComponentCopiesExactOriginalClaimWithoutInventingClaimForAccounting() {
        heads.add(head(4L,"MERCHANT_VERIFY",8L,"2"));heads.get(2).setAmount(new BigDecimal("15.00"));
        CoopSettlementDetail detail=new CoopSettlementDetail();detail.setRegistrationId(1L);detail.setClaimId(101L);detail.setOriginAmount(new BigDecimal("2.00"));
        when(originals.selectOriginalDetailsForHeadForUpdate(4L)).thenReturn(List.of(detail));
        producer.freezeOriginals(topic,basis(),new Date(5000));
        ArgumentCaptor<List<CoopSettlementOriginalComponent>> components=ArgumentCaptor.forClass(List.class);
        verify(ledger).freezeOriginals(any(),anyList(),components.capture());
        var merchant=components.getValue().stream().filter(c->c.getSettlementId()==4L).findFirst().orElseThrow();
        assertEquals(101L,merchant.getSourceClaimId());assertEquals(new BigDecimal("2.00"),merchant.getOriginalAmount());
    }
    @Test void perOrderNegativeMarginDefersTheWholeCloseWithoutDebitingAnyone() {
        heads.add(head(4L,"MERCHANT_VERIFY",8L,"10"));heads.get(2).setAmount(new BigDecimal("7.00"));
        CoopSettlementDetail detail=new CoopSettlementDetail();detail.setRegistrationId(1L);detail.setClaimId(101L);detail.setOriginAmount(new BigDecimal("10.00"));
        when(originals.selectOriginalDetailsForHeadForUpdate(4L)).thenReturn(List.of(detail));
        IllegalStateException error=assertThrows(IllegalStateException.class,()->producer.freezeOriginals(topic,basis(),new Date(5000)));
        assertTrue(error.getMessage().contains("NEGATIVE_ORGANIZER_MARGIN_MANUAL_REQUIRED"));
        verify(manual).afterRollback(eq(11L),anyString(),eq(new BigDecimal("-1.50")));verifyNoInteractions(ledger);
    }
    @Test void largestRemainderTiesUseRegistrationIdRegardlessOfInputOrder() {
        Map<Long,BigDecimal> weights=new LinkedHashMap<>();weights.put(9L,new BigDecimal("1"));weights.put(2L,new BigDecimal("1"));weights.put(5L,new BigDecimal("1"));
        var allocation=CoopOriginalAllocation.allocate(new BigDecimal("0.02"),weights);
        assertEquals(new BigDecimal("0.01"),allocation.get(2L));assertEquals(new BigDecimal("0.01"),allocation.get(5L));assertEquals(new BigDecimal("0.00"),allocation.get(9L));
    }
    @Test void largestRemainderUsesExactCashWeightsAndPreservesHeadTotal() {
        var allocation=CoopOriginalAllocation.allocate(new BigDecimal("0.05"),Map.of(1L,new BigDecimal("1"),2L,new BigDecimal("2")));
        assertEquals(new BigDecimal("0.02"),allocation.get(1L));assertEquals(new BigDecimal("0.03"),allocation.get(2L));
        assertEquals(new BigDecimal("0.05"),allocation.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    private TopicSettlementCashBasis basis(){return TopicSettlementCashBasis.fromLockedRows(11L,registrations);}
    private CmsRegistration registration(Long id,String amount){CmsRegistration r=new CmsRegistration();r.setId(id);r.setRegistrationNo("REG"+id);r.setMemberId(7L);r.setOwnerType(1L);r.setOwnerId(11L);
        r.setOrderNum(1L);r.setPaymentStatus(2);r.setRegistrationStatus(2);r.setVerificationStatus(0);r.setPayableAmount(new BigDecimal(amount));r.setWechatPaymentAmount(new BigDecimal(amount));r.setTransactionId("TX"+id);return r;}
    private CoopSettlement head(Long id,String line,Long member,String amount){CoopSettlement h=new CoopSettlement();h.setId(id);h.setTopicId(11L);h.setLineType(line);h.setMerchantId(member);h.setAmount(new BigDecimal(amount));return h;}
}
