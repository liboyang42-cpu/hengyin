package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls the real topic settlement and claim writers, with explicit frozen unit inputs.
 * Mapper maps and the original-close observer are unit doubles. Independent positive ranges
 * are controlled evidence inputs to real prepare/service/claim code, pending the production
 * order-to-scan source contract; they do not prove runtime multi-order cash admission.
 * This class does not prove MySQL durability, provider verification, or CLUB cash enablement.
 */
@Timeout(5)
class CoopSettlementPayeeDedupTest {
    private static final long MERCHANT = 900002L;

    @Test
    void noneBeforePaidOriginalCannotConsumeThePaidOrdersScope() throws Exception {
        var none = order(1L, MERCHANT, 0, null, null);
        var paid = order(2L, MERCHANT, 2, null, "10.00");
        var fixture = fixture(980L, none, paid);
        independentScopes(fixture);

        assertEquals(1, fixture.settle());

        amount("20.00", fixture.orderHead(2L).getAmount());
        assertEquals(0, fixture.heads.values().stream().filter(h -> Long.valueOf(1L).equals(h.getCoopOrderId())).count());
        assertEquals(4, fixture.claimsByKey.size()); // NONE and paid consume their own, disjoint identities.
        assertEquals(2, fixture.details.size());
        assertEquals(1, fixture.heads.values().stream().filter(h -> h.getCoopOrderId() != null).count());
        conservation(fixture);
        fixture.assertOriginalCloseCount(1);
    }

    @Test
    void paidBeforeNoneHasTheSameOriginalAmountAndConservation() throws Exception {
        var none = order(1L, MERCHANT, 0, null, null);
        var paid = order(2L, MERCHANT, 2, null, "10.00");
        var fixture = fixture(981L, paid, none);
        independentScopes(fixture);

        assertEquals(1, fixture.settle());

        amount("20.00", fixture.orderHead(2L).getAmount());
        assertEquals(4, fixture.claimsByKey.size());
        assertEquals(2, fixture.details.size());
        conservation(fixture);
        fixture.assertOriginalCloseCount(1);
    }

    @Test
    void sameMerchantTwoIndependentOrdersAreSettledFromTheirOwnSalesOnce() throws Exception {
        var fixture = fixture(982L, order(1L, MERCHANT, 1, "50", null),
                order(2L, MERCHANT, 1, "25", null));
        independentScopes(fixture);

        assertEquals(1, fixture.settle());

        amount("20.00", fixture.orderHead(1L).getAmount()); // 40 * 50%, not whole-topic 100 * 50%.
        amount("15.00", fixture.orderHead(2L).getAmount()); // 60 * 25%, not whole-topic 100 * 25%.
        assertEquals("101,102", fixture.orderHead(1L).getVerifiedRegIds());
        assertEquals("201,202", fixture.orderHead(2L).getVerifiedRegIds());
        assertEquals(2, fixture.heads.values().stream().filter(h -> h.getCoopOrderId() != null).count());
        assertEquals(4, fixture.claimsByKey.size());
        assertEquals(4, fixture.details.size());
        assertTrue(fixture.details.values().stream().allMatch(d -> fixture.claimsByKey.values().stream()
                .anyMatch(c -> c.getId().equals(d.getClaimId()))));
        for (var detail : fixture.details.values()) {
            var head = fixture.heads.get(detail.getSettlementId());
            var claim = fixture.claimsByKey.values().stream()
                    .filter(c -> c.getId().equals(detail.getClaimId())).findFirst().orElseThrow();
            assertEquals("COOP_ORDER", claim.getSourceContractType());
            assertEquals(head.getCoopOrderId(), claim.getSourceContractId());
            assertEquals(detail.getRegistrationId(), claim.getFulfillmentId());
        }
        amount("7.00", line(fixture, "INITIATOR_GUARANTEE").getAmount());
        // Two contracts of the same merchant do not turn that one verified merchant into two merchants.
        amount("48.00", line(fixture, "INITIATOR_MARGIN").getAmount());
        conservation(fixture);
        fixture.assertOriginalCloseCount(1);
    }

    @Test
    void overlappingFulfillmentAndSalesCannotBeSettledTwiceOrChooseTheFirstContract() throws Exception {
        var fixture = fixture(983L, order(1L, MERCHANT, 1, "50", null),
                order(2L, MERCHANT, 1, "25", null));
        List<CoopSettlementCandidate> sameOriginalCash = List.of(candidate(983L, 101L, "40.00"),
                candidate(983L, 102L, "60.00"));
        fixture.scope(1L, sameOriginalCash);
        fixture.scope(2L, sameOriginalCash);
        fixture.cashScope(sameOriginalCash);

        assertRefused(fixture, "MANUAL_REPAIR:ORIGINAL_ORDER_SCOPE_OVERLAP");
    }

    @Test
    void sharedOriginalCashWithConflictingSalesSnapshotCannotBeAllocatedAgain() throws Exception {
        var fixture = fixture(984L, order(1L, MERCHANT, 1, "20", null),
                order(2L, MERCHANT, 1, "30", null));
        var original = candidate(984L, 101L, "100.00");
        fixture.scope(1L, List.of(original));
        fixture.scope(2L, List.of(candidate(984L, 101L, "50.00")));
        fixture.cashScope(List.of(original));

        assertRefused(fixture, "MANUAL_REPAIR:ORIGINAL_ORDER_SCOPE_OVERLAP");
    }

    @Test
    void missingFrozenScopeRefusesWholeTransactionWithoutPartialOriginalsOrPoints() throws Exception {
        var fixture = fixture(985L, order(1L, MERCHANT, 1, "50", null),
                order(2L, MERCHANT, 1, "25", null));
        independentScopes(fixture);
        fixture.frozen.get(2L).setScopeId(null);

        assertRefused(fixture, "MANUAL_REPAIR:UNSUPPORTED_CASH_PATH");
    }

    @Test
    void repeatedCloseKeepsBothOriginalHeadsAndAllEconomicIdentitiesUnchanged() throws Exception {
        var fixture = fixture(986L, order(1L, MERCHANT, 1, "50", null),
                order(2L, MERCHANT, 1, "25", null));
        independentScopes(fixture);
        assertEquals(1, fixture.settle());
        var headIds = List.copyOf(fixture.heads.keySet());
        var claims = List.copyOf(fixture.claimsByKey.keySet());
        var detailIds = fixture.details.values().stream().map(d -> d.getId()).toList();
        var adjustments = List.copyOf(fixture.adjustments.keySet());

        assertEquals(0, fixture.settle());

        assertEquals(headIds, List.copyOf(fixture.heads.keySet()));
        assertEquals(claims, List.copyOf(fixture.claimsByKey.keySet()));
        assertEquals(detailIds, fixture.details.values().stream().map(d -> d.getId()).toList());
        assertEquals(adjustments, List.copyOf(fixture.adjustments.keySet()));
        amount("20.00", fixture.orderHead(1L).getAmount());
        amount("15.00", fixture.orderHead(2L).getAmount());
        conservation(fixture);
        fixture.assertOriginalCloseCount(1);
    }

    @Test
    void differentMerchants_bothSettled() throws Exception {
        var fixture = fixture(987L, order(1L, MERCHANT, 1, "50", null),
                order(2L, MERCHANT + 1, 1, "25", null));
        independentScopes(fixture);

        assertEquals(1, fixture.settle());

        assertEquals(Long.valueOf(MERCHANT), fixture.orderHead(1L).getMerchantId());
        assertEquals(Long.valueOf(MERCHANT + 1), fixture.orderHead(2L).getMerchantId());
        amount("20.00", fixture.orderHead(1L).getAmount());
        amount("15.00", fixture.orderHead(2L).getAmount());
        amount("9.00", line(fixture, "INITIATOR_GUARANTEE").getAmount());
        conservation(fixture);
    }

    @Test
    void clubAndMerchantWithSameNumericIdCannotMergeOrEnableUnsupportedClubCash() throws Exception {
        var merchant = order(1L, MERCHANT, 1, "50", null);
        var club = order(2L, MERCHANT, 1, "25", null);
        var fixture = fixture(988L, merchant, club);
        independentScopes(fixture);
        club.setToType("club");
        fixture.frozen.get(2L).setBeneficiaryRole("CLUB");
        fixture.frozen.get(2L).setServiceUnit("ORGANIZER_LEAD");

        assertRefused(fixture, "MANUAL_REPAIR:UNSUPPORTED_CASH_PATH");
    }

    @Test
    void repeatedClubContractsCannotSilentlyChooseOneOrOpenAnUnsupportedCashRoute() throws Exception {
        var first = order(1L, MERCHANT, 1, "50", null);
        var second = order(2L, MERCHANT, 1, "25", null);
        var fixture = fixture(989L, first, second);
        independentScopes(fixture);
        for (var contract : List.of(first, second)) {
            contract.setToType("club");
            fixture.frozen.get(contract.getId()).setBeneficiaryRole("CLUB");
            fixture.frozen.get(contract.getId()).setServiceUnit("ORGANIZER_LEAD");
        }

        assertRefused(fixture, "MANUAL_REPAIR:UNSUPPORTED_CASH_PATH");
    }

    @Test
    void currentFrozenScanReaderRejectsNoneThenPaidWhenBothActuallySelectTheSameSales() throws Exception {
        var fixture = fixture(990L, order(1L, MERCHANT, 0, null, null), order(2L, MERCHANT, 2, null, "10.00"));
        fixture.currentFrozenScanSource(sharedScans(990L));

        assertRefused(fixture, "MANUAL_REPAIR:ORIGINAL_ORDER_SCOPE_OVERLAP:orders=1,2:registration=101");
    }

    @Test
    void currentFrozenScanReaderRejectsPaidThenNoneWithoutSilentlyPickingThePaidOrder() throws Exception {
        var fixture = fixture(991L, order(2L, MERCHANT, 2, null, "10.00"), order(1L, MERCHANT, 0, null, null));
        fixture.currentFrozenScanSource(sharedScans(991L));

        assertRefused(fixture, "MANUAL_REPAIR:ORIGINAL_ORDER_SCOPE_OVERLAP:orders=2,1:registration=101");
    }

    @Test
    void currentFrozenScanReaderRejectsTwoPaidOriginalsSharingTheSameSalesAndFulfillment() throws Exception {
        var fixture = fixture(992L, order(1L, MERCHANT, 1, "50", null), order(2L, MERCHANT, 1, "25", null));
        fixture.currentFrozenScanSource(sharedScans(992L));

        assertRefused(fixture, "MANUAL_REPAIR:ORIGINAL_ORDER_SCOPE_OVERLAP:orders=1,2:registration=101");
    }

    @Test
    void oneCurrentFrozenScanOriginalRetainsItsRealPrepareAmountAndExactReplayDoesNothing() throws Exception {
        var fixture = fixture(993L, order(1L, MERCHANT, 1, "30", null));
        fixture.currentFrozenScanSource(sharedScans(993L));

        assertEquals(1, fixture.settle());

        amount("30.00", fixture.orderHead(1L).getAmount());
        assertEquals("101,102", fixture.orderHead(1L).getVerifiedRegIds());
        assertEquals(2, fixture.claimsByKey.size()); assertEquals(2, fixture.details.size());
        var originalHeads = List.copyOf(fixture.heads.keySet());
        var originalClaims = List.copyOf(fixture.claimsByKey.keySet());
        assertEquals(0, fixture.settle());
        assertEquals(originalHeads, List.copyOf(fixture.heads.keySet()));
        assertEquals(originalClaims, List.copyOf(fixture.claimsByKey.keySet()));
        conservation(fixture); fixture.assertOriginalCloseCount(1);
    }

    private static List<CoopSettlementCandidate> sharedScans(long topicId) {
        return List.of(candidate(topicId, 101L, "40.00"), candidate(topicId, 102L, "60.00"));
    }

    private static void assertRefused(CoopSettlementTest.SetoffFixture fixture, String reason) {
        var failure = assertThrows(IllegalStateException.class, fixture::settle);
        assertTrue(failure.getMessage().contains(reason), failure::getMessage);
        fixture.assertRejectedWithoutEconomicWrites();
    }

    private static CoopSettlementTest.SetoffFixture fixture(long topicId, CoopOrder... orders) throws Exception {
        var assembly = new CoopSettlementTest();
        assembly.setUp();
        var fixture = assembly.new SetoffFixture(topicId, orders);
        fixture.originalTopicClaim();
        return fixture;
    }

    private static void independentScopes(CoopSettlementTest.SetoffFixture fixture) {
        long topicId = fixture.topic.getId();
        var first = List.of(candidate(topicId, 101L, "30.00"), candidate(topicId, 102L, "10.00"));
        var second = List.of(candidate(topicId, 201L, "20.00"), candidate(topicId, 202L, "40.00"));
        fixture.scope(1L, first); fixture.scope(2L, second);
        var cash = new ArrayList<>(first); cash.addAll(second);
        fixture.cashScope(cash);
    }

    private static CoopOrder order(long id, long member, int mode, String rate, String fixed) {
        var order = new CoopOrder(); order.setId(id); order.setToId(member); order.setShareMode(mode);
        order.setShareRate(rate == null ? null : new BigDecimal(rate));
        order.setFixedFee(fixed == null ? null : new BigDecimal(fixed));
        return order;
    }

    private static CoopSettlementCandidate candidate(long topicId, long id, String amount) {
        var row = new CoopSettlementCandidate();
        row.setRegistrationId(id); row.setSourceTopicId(topicId); row.setOrderQuantity(1L);
        row.setTicketOwnerMatches(true); row.setFulfillmentStartInclusive(new Date(0));
        row.setFulfillmentEndExclusive(new Date(1000000)); row.setOwnerType(1L); row.setOwnerId(topicId);
        row.setRegistrationStatus(2); row.setPaymentStatus(2); row.setVerificationStatus(1);
        row.setPurchaseKind("GUIDED_TICKET"); row.setEligibleSalesSnapshot(new BigDecimal(amount));
        row.setScopeCovered(true); row.setMerchantScanPresent(true); row.setMerchantScanTime(new Date(900000));
        return row;
    }

    private static CoopSettlement line(CoopSettlementTest.SetoffFixture fixture, String type) {
        return fixture.heads.values().stream().filter(h -> type.equals(h.getLineType())).findFirst().orElseThrow();
    }

    private static void conservation(CoopSettlementTest.SetoffFixture fixture) {
        amount("100.00", fixture.heads.values().stream().map(CoopSettlement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static void amount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
