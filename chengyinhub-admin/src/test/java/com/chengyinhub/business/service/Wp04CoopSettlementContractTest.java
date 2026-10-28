package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.CoopSettlementContract;
import com.chengyinhub.business.service.support.FailClosedCoopOrderLineupSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 行为回归；只 mock 数据边界，不是 MySQL/结算运行验收。 */
class Wp04CoopSettlementContractTest {
    @Test void readsRealFrozenContractAndCatalogueInsteadOfUnavailableBean() {
        Fixture f = new Fixture();
        CoopSettlementSnapshot s = f.reader.read(f.order);
        assertEquals(11L, s.getTopicId());
        assertEquals("TOPIC_EDITION", s.getCommercialContextType());
        assertEquals(11L, s.getCommercialContextId());
        assertEquals("MERCHANT", s.getBeneficiaryRole());
        assertEquals(88L, s.getBeneficiaryId());
        assertEquals("REGISTRATION_SCAN", s.getFulfillmentEvidenceRule());
        assertEquals(List.of("GUIDED_TICKET"), s.getEligiblePurchaseKinds());
        assertEquals(new BigDecimal("2.00"), s.getFixedFee());
        assertEquals(new Date(1_000), s.getFulfillmentStartInclusive());
        assertEquals(new Date(10_000), s.getFulfillmentEndExclusive());
    }
    @Test void percentageUsesFrozenRateAndPaidSalesWithoutInventingPerSeatFixedFee() {
        Fixture f = new Fixture(); f.order.setShareMode(1); f.order.setShareRate(new BigDecimal("50"));
        f.order.setTermsSnapshot("{\"shareMode\":1,\"compensationMode\":\"SALES_PERCENT\",\"shareRate\":50}");
        CoopSettlementCandidate c = f.candidate(); c.setOrderQuantity(3L);
        assertEquals(new BigDecimal("5.00"), origins(f.snapshot(),c).get(0).getOriginAmount());
        f.order.setShareRate(new BigDecimal("51"));
        assertRepair(() -> f.reader.read(f.order), "FROZEN_SHARE_RATE_UNPROVEN");
    }
    @Test void clubEconomicBeneficiaryCannotUseUnfrozenLiveClubOwnerAsPayee() {
        Fixture f = new Fixture(); CoopSettlementSnapshot s = f.snapshot();
        s.setBeneficiaryRole("CLUB"); s.setServiceUnit("ORGANIZER_LEAD");
        s.setFulfillmentEvidenceRule("COOP_GROUP_VERIFY");
        assertRepair(() -> CoopSettlementContract.requireSupportedCashPath(s), "UNSUPPORTED_CASH_PATH");
    }
    @Test void missingFrozenTermsNeverUsesScalarFixedFee() {
        Fixture f = new Fixture(); f.order.setTermsSnapshot(null);
        assertRepair(() -> f.reader.read(f.order), "FROZEN_LINEUP_INVALID");
    }
    @Test void scalarCannotOverrideFrozenFeeOrMode() {
        Fixture f = new Fixture(); f.order.setFixedFee(new BigDecimal("9.00"));
        assertRepair(() -> f.reader.read(f.order), "FROZEN_FIXED_FEE_UNPROVEN");
        Fixture other = new Fixture(); other.order.setShareMode(1);
        assertRepair(() -> other.reader.read(other.order), "FROZEN_COMPENSATION_CONFLICT");
    }
    @Test void missingFrozenPlatformParametersAndChangedInitiatorPayeeAreBlocked() {
        Fixture f = new Fixture(); f.topic.setFeePlatformRate(null);
        assertRepair(() -> f.reader.read(f.order), "TOPIC_FROZEN_FEE_PARAMETERS_MISSING");
        Fixture other = new Fixture(); other.topic.setMemberId(99L);
        assertRepair(() -> other.reader.read(other.order), "INITIATOR_PAYEE_UNPROVEN");
    }
    @Test void absentTermsVersionAndSaleFreezeAreNotInferred() {
        Fixture f = new Fixture(); f.order.setTermsVersion(null);
        assertRepair(() -> f.reader.read(f.order), "ORDER_OR_TERMS_VERSION_UNPROVEN");
        Fixture other = new Fixture(); other.topic.setSellingStartedAt(null);
        assertRepair(() -> other.reader.read(other.order), "SALE_CATALOG_NOT_FROZEN");
    }
    @Test void missingTicketWindowNeverFallsBackToTopicDates() {
        Fixture f = new Fixture(); f.ticket.setEndTime(null);
        f.topic.setStartDate(new Date(0)); f.topic.setEndDate(new Date(20_000));
        assertRepair(() -> f.reader.read(f.order), "FROZEN_CONTRACT_READ_FAILED");
        Fixture empty = new Fixture(); when(empty.tickets.selectOmsTicketList(any())).thenReturn(List.of());
        assertRepair(() -> empty.reader.read(empty.order), "FROZEN_TICKET_WINDOW_MISSING");
    }
    @Test void ticketMustBelongToFrozenTopic() {
        Fixture f = new Fixture(); f.ticket.setOwnerType(2L); // 同一个数字 id 也不是主题票。
        assertRepair(() -> f.reader.read(f.order), "TICKET_OWNER_UNPROVEN");
    }
    @Test void nodeIdMatchingTopicNumberDoesNotProveParentRelation() {
        Fixture f = new Fixture(); f.order.setScopeTypeSnapshot("NODE");
        f.order.setScopeIdSnapshot(11L); f.order.setEligiblePurchaseKindsSnapshot("EXPLORE_PASS");
        f.ticket.setMode(2);
        CmsTopicNode node = new CmsTopicNode(); node.setId(11L); node.setTopicId(99L);
        when(f.nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        assertRepair(() -> f.reader.read(f.order), "NODE_TOPIC_RELATION_UNPROVEN");
        node.setTopicId(11L);
        CoopSettlementSnapshot s = f.reader.read(f.order);
        assertEquals("NODE", s.getScopeType());
        assertRepair(() -> CoopSettlementContract.requireSupportedCashPath(s), "UNSUPPORTED_CASH_PATH");
    }
    @Test void nodeGuidedConflictAndUnapprovedActivityOrderRemainBlocked() {
        Fixture f = new Fixture(); f.order.setScopeTypeSnapshot("NODE");
        assertRepair(() -> f.reader.read(f.order), "FROZEN_LINEUP_INVALID");
        Fixture other = new Fixture(); other.order.setScopeTypeSnapshot("ACTIVITY");
        assertRepair(() -> other.reader.read(other.order), "FROZEN_LINEUP_INVALID");
    }
    @Test void windowIsStartInclusiveAndEndExclusive() {
        Fixture f = new Fixture(); CoopSettlementSnapshot s = f.snapshot();
        CoopSettlementCandidate c = f.candidate(); c.setMerchantScanTime(new Date(1_000));
        assertEquals(1, origins(s, c).size());
        c.setMerchantScanTime(new Date(999)); assertTrue(origins(s, c).isEmpty());
        c.setMerchantScanTime(new Date(10_000)); assertTrue(origins(s, c).isEmpty());
    }
    @Test void frozenScanRuleDoesNotSwitchToGroupOrVerificationFallback() {
        Fixture f = new Fixture(); CoopSettlementCandidate c = f.candidate();
        c.setMerchantScanPresent(false); c.setOrganizerScopeCovered(true);
        c.setGroupEvidenceMatchesOrder(true); c.setGroupVerifyTime(new Date(2_000));
        c.setGroupMemberVerifyTime(new Date(2_000)); c.setVerificationTime(new Date(2_000));
        assertTrue(origins(f.snapshot(), c).isEmpty());
    }
    @Test void quantityMissingZeroOrMultipleRemainsBlockedForFixedFee() {
        for (Long quantity : Arrays.asList(null, 0L, 2L, 3L)) {
            Fixture f = new Fixture(); CoopSettlementCandidate c = f.candidate(); c.setOrderQuantity(quantity);
            assertRepair(() -> origins(f.snapshot(), c), "FIXED_FEE_QUANTITY_UNPROVEN");
        }
    }
    @Test void registrationOwnerDefinesEconomicContextEvenWhenNumericIdsCollide() {
        Fixture f = new Fixture(); CoopSettlementCandidate c = f.candidate(); c.setOwnerType(2L);
        assertRepair(() -> origins(f.snapshot(), c), "ECONOMIC_CONTEXT_SPLIT_REQUIRED");
        CoopSettlementSnapshot activity = f.snapshot(); activity.setCommercialContextType("ACTIVITY");
        assertEquals("ACTIVITY", origins(activity, c).get(0).getCommercialContextType());
        assertRepair(() -> CoopSettlementContract.requireSupportedCashPath(activity), "UNSUPPORTED_CASH_PATH");
    }
    @Test void foreignSourceTopicAndIneligibleStatesNeverCreateOrigins() {
        Fixture f = new Fixture(); CoopSettlementCandidate c = f.candidate(); c.setSourceTopicId(99L);
        assertTrue(origins(f.snapshot(), c).isEmpty());
        c = f.candidate(); c.setPaymentStatus(1); assertTrue(origins(f.snapshot(), c).isEmpty());
        c = f.candidate(); c.setScopeCovered(false); assertTrue(origins(f.snapshot(), c).isEmpty());
    }
    @Test void wrongTicketOwnerAndMissingAmountDoNotBecomeZeroCashSuccess() {
        Fixture f = new Fixture(); CoopSettlementCandidate wrongOwner = f.candidate(); wrongOwner.setTicketOwnerMatches(false);
        assertRepair(() -> origins(f.snapshot(), wrongOwner), "REGISTRATION_FROZEN_WINDOW_UNPROVEN");
        CoopSettlementCandidate noMoney = f.candidate(); noMoney.setEligibleSalesSnapshot(null);
        assertRepair(() -> origins(f.snapshot(), noMoney), "ELIGIBLE_SALES_UNPROVEN");
    }
    @Test void prepareRejectsOpenWindowBeforeLockOrLedgerWork() {
        Fixture f = new Fixture();
        assertRepair(() -> f.service.prepare(f.order, new Date(9_999)), "FROZEN_FULFILLMENT_WINDOW_NOT_CLOSED");
        verifyNoInteractions(f.evidence, f.claims, f.heads, f.details);
    }
    @Test void emptyEvidenceDoesNotConsumePositiveOrderWithZeroTerminal() {
        Fixture f = new Fixture(); when(f.evidence.lockCandidates(any(), any())).thenReturn(List.of());
        assertRepair(() -> f.service.prepare(f.order, new Date(11_000)), "NO_PROVEN_FULFILLMENT");
        verifyNoInteractions(f.claims, f.heads, f.details);
    }
    @Test void approvedOrPendingRefundBlocksBeforeAnyLedgerWrite() {
        Fixture f = new Fixture(); doThrow(new IllegalStateException("MANUAL_REPAIR:REFUND_NET_BASIS_UNPROVEN"))
                .when(f.evidence).requireUnrefundedCashBasis(11L);
        assertRepair(() -> f.service.prepare(f.order, new Date(11_000)), "REFUND_NET_BASIS_UNPROVEN");
        verifyNoInteractions(f.claims, f.heads, f.details);
    }
    @Test void realReaderPrepareAndPersistProduceNonzeroOriginAndCanonicalClaim() {
        Fixture f = new Fixture();
        when(f.claims.claim(any())).thenAnswer(call -> { SettlementClaim claim = call.getArgument(0); claim.setId(61L); return claim; });
        when(f.heads.insertCoopSettlement(any())).thenAnswer(call -> {
            CoopSettlement head = call.getArgument(0); assertEquals(0, head.getAmount().signum());
            assertEquals(0, head.getVerifiedHeads()); head.setId(51L); return 1;
        });
        when(f.details.insertIgnoreDetail(any())).thenReturn(1);
        when(f.details.refreshOriginAggregate(51L)).thenReturn(1);
        CoopSettlementPlan plan = f.service.prepare(f.order, new Date(11_000));
        CoopSettlement head = new CoopSettlement(); head.setTopicId(11L); f.service.persist(plan, head);
        assertEquals(new BigDecimal("2.00"), plan.getOriginAmount());
        assertEquals(new BigDecimal("2.00"), head.getAmount());
        assertEquals(1, head.getVerifiedHeads());
        ArgumentCaptor<SettlementClaim> claim = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(f.claims).claim(claim.capture());
        assertEquals("TOPIC_EDITION", claim.getValue().getCommercialContextType());
        assertEquals(11L, claim.getValue().getCommercialContextId());
        assertEquals("REGISTRATION", claim.getValue().getFulfillmentType());
        assertEquals(31L, claim.getValue().getFulfillmentId());
        assertEquals(88L, claim.getValue().getBeneficiaryId());
        verify(f.details).refreshOriginAggregate(51L);
        verify(f.evidence).requireUnrefundedCashBasis(11L);
    }
    private static List<CoopSettlementOriginLine> origins(CoopSettlementSnapshot s, CoopSettlementCandidate c) {
        return CoopSettlementContract.originLines(s, List.of(c), new Date(10_000));
    }
    private static void assertRepair(Runnable action, String reason) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, action::run);
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }
    static final class Fixture {
        final CoopOrderMapper orders = mock(CoopOrderMapper.class);
        final CoopInviteMapper invites = mock(CoopInviteMapper.class);
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final OmsTicketMapper tickets = mock(OmsTicketMapper.class);
        final TopicSettlementEvidenceService evidence = mock(TopicSettlementEvidenceService.class);
        final ISettlementClaimService claims = mock(ISettlementClaimService.class);
        final CoopSettlementMapper heads = mock(CoopSettlementMapper.class);
        final CoopSettlementDetailMapper details = mock(CoopSettlementDetailMapper.class);
        final CoopOrder order = new CoopOrder(); final CmsTopic topic = new CmsTopic(); final OmsTicket ticket = new OmsTicket();
        final FrozenCoopOrderSettlementSnapshotReader reader;
        final CoopSettlementClaimServiceImpl service;
        Fixture() {
            order.setId(701L); order.setTopicId(11L); order.setFromId(77L); order.setToType("merchant"); order.setToId(88L);
            order.setStatus(0); order.setDelFlag(0); order.setTermsVersion("v1"); order.setRoleSnapshot("SUPPLY");
            order.setScopeTypeSnapshot("TOPIC"); order.setScopeIdSnapshot(11L);
            order.setEligiblePurchaseKindsSnapshot("GUIDED_TICKET"); order.setFulfillmentEvidenceRuleSnapshot("REGISTRATION_SCAN");
            order.setShareMode(2); order.setFixedFee(new BigDecimal("2.00"));
            order.setTermsSnapshot("{\"shareMode\":2,\"compensationMode\":\"FIXED_PER_FULFILLMENT\",\"fixedFee\":2.00}");
            topic.setId(11L); topic.setDelFlag(0); topic.setProductType(1); topic.setLifecycle(3); topic.setSellingStartedAt(new Date(100));
            topic.setMemberId(77L); topic.setFeePlatformRate(new BigDecimal("0.10"));
            topic.setFeeGuaranteeBaseRate(new BigDecimal("0.05")); topic.setFeeGuaranteeStepRate(new BigDecimal("0.02"));
            topic.setFeeGuaranteeMaxMerchant(5);
            ticket.setId(21L); ticket.setMode(1); ticket.setSubType("guided"); ticket.setOwnerType(1L); ticket.setOwnerId(11L); ticket.setDelFlag(0);
            ticket.setStartTime(new Date(1_000)); ticket.setEndTime(new Date(10_000));
            when(orders.selectEffectiveOrdersByTopicId(11L)).thenReturn(List.of(order));
            when(topics.selectCmsTopicById(11L)).thenReturn(topic); when(tickets.selectOmsTicketList(any())).thenReturn(List.of(ticket));
            reader = new FrozenCoopOrderSettlementSnapshotReader(new FailClosedCoopOrderLineupSource(orders, invites), topics, nodes, tickets);
            service = new CoopSettlementClaimServiceImpl(reader, new SnapshotFulfillmentWindowResolver(), evidence, claims,
                    heads, details, mock(CoopSettlementAdjustmentMapper.class));
            org.springframework.test.util.ReflectionTestUtils.setField(service,"topicMapper",topics);
            when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topic);
            when(evidence.lockCandidates(any(), any())).thenAnswer(call -> List.of(candidate()));
        }
        CoopSettlementSnapshot snapshot() { CoopSettlementSnapshot s = reader.read(order); s.setSettlementSnapshotAt(new Date(11_000)); return s; }
        CoopSettlementCandidate candidate() {
            CoopSettlementCandidate c = new CoopSettlementCandidate(); c.setRegistrationId(31L); c.setOwnerType(1L); c.setOwnerId(11L);
            c.setSourceTopicId(11L); c.setOrderQuantity(1L); c.setRegistrationStatus(2); c.setPaymentStatus(2); c.setVerificationStatus(1);
            c.setPurchaseKind("GUIDED_TICKET"); c.setEligibleSalesSnapshot(new BigDecimal("10.00")); c.setScopeCovered(true);
            c.setTicketOwnerMatches(true); c.setFulfillmentStartInclusive(new Date(1_000)); c.setFulfillmentEndExclusive(new Date(10_000));
            c.setMerchantScanPresent(true); c.setMerchantScanTime(new Date(2_000)); return c;
        }
    }
}
