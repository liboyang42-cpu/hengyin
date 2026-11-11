package com.chengyinhub.business.payout;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitSharePlannerTest {
    private static final String SELLER = "1900000101";
    private static final String PLATFORM = "1900000001";
    private static final String OTHER = "1900000202";

    private static ProfitSharePlanner.Order order(long totalFen) {
        return new ProfitSharePlanner.Order("REG1", "4200000001", SELLER, totalFen, Set.of(11L));
    }

    private static ProfitSharePlanner.Component c(long settlementId, String type, String kind, Long member, String yuan) {
        return new ProfitSharePlanner.Component(settlementId, type, kind, member, new BigDecimal(yuan));
    }

    @Test
    void otherSubMerchantShareGoesManualUnlessRelayedViaThePlatform() {
        List<ProfitSharePlanner.Component> cs = List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                c(2, "MERCHANT_VERIFY", "MEMBER", 22L, "10.00"),
                c(3, "INITIATOR_MARGIN", "MEMBER", 11L, "75.00"));
        // f-money 待决定 2：微信不支持二级商户之间直接分账，合单关时整单转人工，不出任何接收方。
        ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(order(10000), cs, PLATFORM, 30,
                m -> m == 22L ? Optional.of(OTHER) : Optional.empty());
        assertEquals("MANUAL", plan.state());
        assertEquals(ProfitSharePlanner.SUB_TO_SUB_UNSUPPORTED, plan.manualReason());
        assertTrue(plan.receivers().isEmpty());
        assertEquals(0, plan.sharedFen());
        assertTrue(plan.lines().stream().noneMatch(l -> l.kind() == ProfitSharePlanner.ReceiverKind.SUB_MERCHANT));
        ProfitSharePlanner.Plan relay = ProfitSharePlanner.plan(order(10000), cs, PLATFORM, 30,
                m -> m == 22L ? Optional.of(OTHER) : Optional.empty(), true);
        assertTrue(relay.ready());
        assertEquals(2500, relay.sharedFen());
        assertEquals(List.of(new ProfitSharePlanner.Receiver(PLATFORM, 2500)), relay.receivers());
        assertEquals(3, relay.lines().size());
        assertNotNull(relay.planHash());
    }

    @Test
    void sameReceiverAcrossLinesIsMergedIntoOneWechatReceiver() {
        ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "1.00"),
                c(2, "MERCHANT_VERIFY", "MEMBER", 22L, "0.50"),
                c(3, "LEAD_FEE", "MEMBER", 23L, "0.50"),
                c(4, "INITIATOR_MARGIN", "MEMBER", 11L, "8.00")), PLATFORM, 30, m -> Optional.of(OTHER), true);
        assertEquals(List.of(new ProfitSharePlanner.Receiver(PLATFORM, 200)), plan.receivers());
    }

    @Test
    void receiverWhoseMerchantIsTheSellerIsRetained() {
        ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "MERCHANT_VERIFY", "MEMBER", 33L, "2.00"),
                c(2, "INITIATOR_MARGIN", "MEMBER", 11L, "8.00")), PLATFORM, 30, m -> Optional.of(SELLER));
        assertTrue(plan.nothingToShare());
        assertEquals(0, plan.sharedFen());
    }

    @Test
    void notOnboardedReceiverGoesManualNeverPartialSplit() {
        ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "1.00"),
                c(2, "LEAD_FEE", "MEMBER", 44L, "1.00"),
                c(3, "INITIATOR_MARGIN", "MEMBER", 11L, "8.00")), PLATFORM, 30, m -> Optional.empty());
        assertEquals("MANUAL", plan.state());
        assertEquals("RECEIVER_NOT_ONBOARDED", plan.manualReason());
        assertTrue(plan.receivers().isEmpty());
    }

    @Test
    void ledgerMustConserveTheOrderCash() {
        ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "1.00"),
                c(3, "INITIATOR_MARGIN", "MEMBER", 11L, "8.00")), PLATFORM, 30, m -> Optional.empty());
        assertEquals("LEDGER_CONSERVATION", plan.manualReason());
    }

    @Test
    void ratioAboveWechatCapGoesManual() {
        ProfitSharePlanner.Plan plan = ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "3.01"),
                c(3, "INITIATOR_MARGIN", "MEMBER", 11L, "6.99")), PLATFORM, 30, m -> Optional.empty());
        assertEquals("SHARE_RATIO_EXCEEDED", plan.manualReason());
        ProfitSharePlanner.Plan exact = ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "3.00"),
                c(3, "INITIATOR_MARGIN", "MEMBER", 11L, "7.00")), PLATFORM, 30, m -> Optional.empty());
        assertTrue(exact.ready());
    }

    @Test
    void invalidInputsGoManual() {
        assertEquals("LEDGER_AMOUNT_INVALID", ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "0.001")), PLATFORM, 30, m -> Optional.empty()).manualReason());
        assertEquals("LEDGER_NEGATIVE_COMPONENT", ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "-1.00")), PLATFORM, 30, m -> Optional.empty()).manualReason());
        assertEquals("PLATFORM_RECEIVER_MISSING", ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "1.00")), "", 30, m -> Optional.empty()).manualReason());
        assertEquals("PLATFORM_IS_SELLER", ProfitSharePlanner.plan(order(1000), List.of(
                c(1, "PLATFORM", "ACCOUNTING", null, "1.00")), SELLER, 30, m -> Optional.empty()).manualReason());
        assertEquals("NO_LEDGER_COMPONENTS", ProfitSharePlanner.plan(order(1000), List.of(), PLATFORM, 30,
                m -> Optional.empty()).manualReason());
        assertEquals("ORDER_FACTS_MISSING", ProfitSharePlanner.plan(
                new ProfitSharePlanner.Order("REG1", null, SELLER, 1000, Set.of()), List.of(), PLATFORM, 30,
                m -> Optional.empty()).manualReason());
    }

    @Test
    void samePlanSameHash() {
        List<ProfitSharePlanner.Component> cs = List.of(c(2, "MERCHANT_VERIFY", "MEMBER", 22L, "1.00"),
                c(1, "PLATFORM", "ACCOUNTING", null, "1.00"), c(3, "INITIATOR_MARGIN", "MEMBER", 11L, "8.00"));
        String a = ProfitSharePlanner.plan(order(1000), cs, PLATFORM, 30, m -> Optional.of(OTHER), true).planHash();
        String b = ProfitSharePlanner.plan(order(1000), List.of(cs.get(2), cs.get(1), cs.get(0)), PLATFORM, 30,
                m -> Optional.of(OTHER), true).planHash();
        assertNotNull(a);
        assertEquals(a, b);
    }

    private static Map<String, long[]> shared(long platform, long other) {
        Map<String, long[]> m = new LinkedHashMap<>();
        m.put(PLATFORM, new long[]{platform, 0});
        m.put(OTHER, new long[]{other, 0});
        return m;
    }

    @Test
    void fullRefundReturnsEverything() {
        assertEquals(Map.of(PLATFORM, 1500L, OTHER, 1000L), ProfitSharePlanner.returnsForRefund(shared(1500, 1000), 10000, 10000, 0));
    }

    @Test
    void partialRefundsAreProportionalAndCumulativeExact() {
        Map<String, long[]> s = shared(1500, 1000);
        Map<String, Long> first = ProfitSharePlanner.returnsForRefund(s, 10000, 3333, 0);
        long firstTotal = first.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(833, firstTotal); // round(2500 * 3333 / 10000)
        first.forEach((k, v) -> s.get(k)[1] += v);
        Map<String, Long> rest = ProfitSharePlanner.returnsForRefund(s, 10000, 6667, 3333);
        long restTotal = rest.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(2500 - 833, restTotal);
        assertEquals(1500L, first.getOrDefault(PLATFORM, 0L) + rest.getOrDefault(PLATFORM, 0L));
        assertEquals(1000L, first.getOrDefault(OTHER, 0L) + rest.getOrDefault(OTHER, 0L));
    }

    @Test
    void refundBeyondPaidIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProfitSharePlanner.returnsForRefund(shared(1, 1), 100, 60, 50));
        assertTrue(ProfitSharePlanner.returnsForRefund(Map.of(), 100, 50, 0).isEmpty());
    }
}
