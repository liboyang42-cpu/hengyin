package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RegistrationSeat;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.RegistrationSeatSettlementReader;
import com.chengyinhub.business.service.support.CoopSettlementContract;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real fee contract and evidence consumer; C's storage port is explicit, not a MySQL proof. */
class CoopSettlementSeatBillingTest {
    final CmsActivityMapper activities=mock(CmsActivityMapper.class);
    final CoopSettlementEvidenceMapper evidence=mock(CoopSettlementEvidenceMapper.class);
    final RegistrationSeatMapper seatFacts=mock(RegistrationSeatMapper.class);
    final RegistrationSeatSettlementReader seatOwner=new RegistrationSeatSettlementReader(seatFacts);
    final TopicSettlementEvidenceService reader=new TopicSettlementEvidenceService(activities,mock(CmsRegistrationMapper.class),mock(CoopGroupVerifyMapper.class),evidence);
    final Date cutoff=new Date(1000);
    final CoopSettlementSnapshot snapshot=snapshot();
    CoopSettlementSeatBillingTest(){ReflectionTestUtils.setField(reader,"registrationSeats",seatOwner);when(activities.selectActiveActivityIdsByTopicId(11L)).thenReturn(List.of(201L));when(seatFacts.selectUnprovenMultiGroupOrdersForUpdate(11L,201L,cutoff)).thenReturn(List.of());}
    @Test void fourPurchasedSeatsButOnlyTwoVerifiedChargeTwiceAndNeverExpandOrderQuantity() {
        CoopSettlementCandidate candidate=candidate();candidate.setOrderQuantity(4L);
        when(evidence.selectTopicEvidenceCandidatesForUpdate(eq(snapshot),anyList(),eq(cutoff))).thenReturn(List.of(candidate));
        when(seatFacts.selectVerifiedForGroupForUpdate(11L,201L,cutoff)).thenReturn(List.of(seat(1L,500),seat(2L,600)));
        var lines=CoopSettlementContract.originLines(snapshot,reader.lockCandidates(snapshot,cutoff),cutoff);
        assertEquals(1,lines.size());assertEquals(new BigDecimal("4.00"),lines.get(0).getOriginAmount());
        assertEquals(List.of(1L,2L),candidate.getVerifiedSeatIds());verify(seatFacts).selectVerifiedForGroupForUpdate(11L,201L,cutoff);
    }
    @Test void frozenGroupReceiptAndCanonicalSeatsUseTheSameFixedFeeWithoutIndividualScans() {
        snapshot.setFulfillmentEvidenceRule("COOP_GROUP_VERIFY");
        CoopSettlementContract.requireSupportedCashPath(snapshot);
        CoopSettlementCandidate candidate=candidate();candidate.setOrderQuantity(4L);candidate.setMerchantScanPresent(false);
        candidate.setGroupEvidenceMatchesOrder(true);candidate.setGroupVerifyTime(new Date(600));candidate.setGroupMemberVerifyTime(new Date(500));
        when(evidence.selectTopicEvidenceCandidatesForUpdate(eq(snapshot),anyList(),eq(cutoff))).thenReturn(List.of(candidate));
        when(seatFacts.selectVerifiedForGroupForUpdate(11L,201L,cutoff)).thenReturn(List.of(seat(1L,500),seat(2L,600)));
        var lines=CoopSettlementContract.originLines(snapshot,reader.lockCandidates(snapshot,cutoff),cutoff);
        assertEquals(new BigDecimal("4.00"),lines.get(0).getOriginAmount());
        candidate.setGroupEvidenceMatchesOrder(false);
        assertTrue(CoopSettlementContract.originLines(snapshot,List.of(candidate),cutoff).isEmpty());
    }
    @Test void singleVerifiedGroupWithoutCanonicalSeatRetainsTheOriginalOneSeatProof() {
        snapshot.setFulfillmentEvidenceRule("COOP_GROUP_VERIFY");
        CoopSettlementCandidate candidate=candidate();candidate.setOrderQuantity(1L);candidate.setMerchantScanPresent(false);
        candidate.setGroupEvidenceMatchesOrder(true);candidate.setGroupVerifyTime(new Date(600));candidate.setGroupMemberVerifyTime(new Date(500));
        when(evidence.selectTopicEvidenceCandidatesForUpdate(eq(snapshot),anyList(),eq(cutoff))).thenReturn(List.of(candidate));
        when(seatFacts.selectVerifiedForGroupForUpdate(11L,201L,cutoff)).thenReturn(List.of());
        var lines=CoopSettlementContract.originLines(snapshot,reader.lockCandidates(snapshot,cutoff),cutoff);
        assertNull(candidate.getVerifiedSeatIds());assertEquals(1,lines.size());assertEquals(new BigDecimal("2.00"),lines.get(0).getOriginAmount());
        candidate.setGroupEvidenceMatchesOrder(false);
        assertTrue(CoopSettlementContract.originLines(snapshot,List.of(candidate),cutoff).isEmpty());
    }
    @Test void multipleSeatsWithoutCanonicalEvidenceDoNotUseTheSingleSeatFallback() {
        CoopSettlementCandidate candidate=candidate();candidate.setOrderQuantity(4L);
        when(evidence.selectTopicEvidenceCandidatesForUpdate(eq(snapshot),anyList(),eq(cutoff))).thenReturn(List.of(candidate));
        when(seatFacts.selectVerifiedForGroupForUpdate(11L,201L,cutoff)).thenReturn(List.of());
        assertTrue(CoopSettlementContract.originLines(snapshot,reader.lockCandidates(snapshot,cutoff),cutoff).isEmpty());
        assertTrue(candidate.getVerifiedSeatIds().isEmpty());
    }
    @Test void repeatedCanonicalSeatIdentityIsChargedOnce() {
        CoopSettlementCandidate candidate=candidate();candidate.setOrderQuantity(4L);
        when(evidence.selectTopicEvidenceCandidatesForUpdate(eq(snapshot),anyList(),eq(cutoff))).thenReturn(List.of(candidate));
        when(seatFacts.selectVerifiedForGroupForUpdate(11L,201L,cutoff)).thenReturn(List.of(seat(1L,500),seat(1L,500)));
        var lines=CoopSettlementContract.originLines(snapshot,reader.lockCandidates(snapshot,cutoff),cutoff);
        assertEquals(new BigDecimal("2.00"),lines.get(0).getOriginAmount());assertEquals(List.of(1L),candidate.getVerifiedSeatIds());
    }
    @Test void unverifiedAndCutoffSeatsNeverBecomeFeeEligibility() {
        CoopSettlementCandidate candidate=candidate();candidate.setOrderQuantity(4L);
        when(evidence.selectTopicEvidenceCandidatesForUpdate(eq(snapshot),anyList(),eq(cutoff))).thenReturn(List.of(candidate));
        when(seatFacts.selectVerifiedForGroupForUpdate(11L,201L,cutoff)).thenReturn(List.of(seat(1L,1000)));
        assertTrue(CoopSettlementContract.originLines(snapshot,reader.lockCandidates(snapshot,cutoff),cutoff).isEmpty());
        assertTrue(candidate.getVerifiedSeatIds().isEmpty());
    }
    @Test void missingCanonicalMultiSeatEvidenceStillRefusesInsteadOfMultiplyingBoughtQuantity() {
        CoopSettlementCandidate candidate=candidate();candidate.setOrganizerScopeCovered(false);candidate.setOrderQuantity(4L);
        assertThrows(IllegalStateException.class,()->CoopSettlementContract.originLines(snapshot,List.of(candidate),cutoff));
        verifyNoInteractions(seatFacts);
    }
    @Test void foreignSeatScopeCannotEnterTheFrozenContract() {
        CoopSettlementCandidate candidate=candidate();candidate.setOrderQuantity(4L);RegistrationSeat foreign=seat(1L,500);foreign.setTopicId(99L);
        when(evidence.selectTopicEvidenceCandidatesForUpdate(eq(snapshot),anyList(),eq(cutoff))).thenReturn(List.of(candidate));
        when(seatFacts.selectVerifiedForGroupForUpdate(11L,201L,cutoff)).thenReturn(List.of(foreign));
        assertThrows(IllegalStateException.class,()->reader.lockCandidates(snapshot,cutoff));
    }
    private static CoopSettlementSnapshot snapshot(){CoopSettlementSnapshot s=new CoopSettlementSnapshot();s.setOrderId(701L);s.setTopicId(11L);s.setProductType(1);s.setInitiatorMemberId(7L);s.setTermsVersion("v1");s.setCatalogFrozenAt(new Date(50));s.setCommercialContextType("TOPIC_EDITION");s.setCommercialContextId(11L);
        s.setBeneficiaryRole("MERCHANT");s.setBeneficiaryId(88L);s.setServiceUnit("MERCHANT_PARTICIPATION");s.setCompensationMode("FIXED_PER_FULFILLMENT");s.setFixedFee(new BigDecimal("2.00"));
        s.setFulfillmentEvidenceRule("REGISTRATION_SCAN");s.setEligiblePurchaseKinds(List.of("GUIDED_TICKET"));s.setScopeType("TOPIC");s.setScopeId(11L);
        s.setFulfillmentStartInclusive(new Date(100));s.setFulfillmentEndExclusive(new Date(1000));s.setSettlementSnapshotAt(new Date(1000));return s;}
    private static CoopSettlementCandidate candidate(){CoopSettlementCandidate c=new CoopSettlementCandidate();c.setRegistrationId(1L);c.setOwnerType(1L);c.setOwnerId(11L);c.setSourceTopicId(11L);c.setPaymentStatus(2);c.setRegistrationStatus(2);c.setVerificationStatus(1);
        c.setPurchaseKind("GUIDED_TICKET");c.setEligibleSalesSnapshot(new BigDecimal("20.00"));c.setScopeCovered(true);c.setTicketOwnerMatches(true);c.setOrganizerScopeCovered(true);c.setMerchantScanPresent(true);c.setMerchantScanTime(new Date(500));
        c.setFulfillmentStartInclusive(new Date(100));c.setFulfillmentEndExclusive(new Date(1000));return c;}
    private static RegistrationSeat seat(Long id,long time){RegistrationSeat s=new RegistrationSeat();s.setId(id);s.setRegistrationId(1L);s.setSeatNo(id.intValue());s.setStatus(1);s.setTopicId(11L);s.setActivityId(201L);s.setVerifierMemberId(7L);s.setVerifiedAt(new Date(time));return s;}
}
