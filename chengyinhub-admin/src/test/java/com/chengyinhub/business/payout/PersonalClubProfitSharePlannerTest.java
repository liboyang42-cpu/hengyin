package com.chengyinhub.business.payout;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonalClubProfitSharePlannerTest {
    private final ProfitSharePlanner.Order order = new ProfitSharePlanner.Order("paid", "transaction", "seller", 100, Set.of(7L));
    private ProfitSharePlanner.Component club(String openid, String appid) {
        return new ProfitSharePlanner.Component(1, "CLUB", "MEMBER", 7L, new BigDecimal("1.00"), 8L, "原俱乐部", openid, appid);
    }
    @Test void personalReceiverIsNotRetainedBySellerOwnerOrRelayed() {
        var plan = ProfitSharePlanner.plan(order, List.of(club("openid", "wx_platform")), "platform", 100,
                id -> Optional.of("seller"), true, true, "wx_platform");
        assertTrue(plan.ready());
        assertEquals(ProfitSharePlanner.ReceiverKind.PERSONAL, plan.lines().get(0).kind());
        assertEquals(new ProfitSharePlanner.Receiver("PERSONAL_OPENID", "openid", "wx_platform", 100), plan.receivers().get(0));
        assertEquals("原俱乐部", plan.lines().get(0).clubName());
        assertEquals(7L, plan.lines().get(0).receiverMemberId());
    }
    @Test void missingBindingHoldsWholeOrderWithoutMerchantFallback() {
        var plan = ProfitSharePlanner.plan(order, List.of(club(null, null)), "platform", 100,
                id -> Optional.of("seller"), true, true, "wx_platform");
        assertEquals("PERSONAL_BINDING_REQUIRED", plan.manualReason());
        assertTrue(plan.receivers().isEmpty());
    }
    @Test void wrongAppidDoesNotUseHistoricalOpenid() {
        var plan = ProfitSharePlanner.plan(order, List.of(club("openid", "wx_other")), "platform", 100,
                id -> Optional.of("seller"), false, true, "wx_platform");
        assertEquals("PERSONAL_BINDING_APPID_INVALID", plan.manualReason());
    }
    @Test void personalAndMerchantWithSameAccountNeverGroupTogether() {
        var personal = new ProfitSharePlanner.Component(1, "CLUB", "MEMBER", 7L, new BigDecimal("0.50"), 8L, "X", "same", "wx_platform");
        var platform = new ProfitSharePlanner.Component(2, "FEE", "ACCOUNTING", null, new BigDecimal("0.50"));
        var plan = ProfitSharePlanner.plan(order, List.of(personal, platform), "same", 100,
                id -> Optional.empty(), false, true, "wx_platform");
        assertTrue(plan.ready());
        assertEquals(2, plan.receivers().size());
        assertNotEquals(plan.receivers().get(0).identityKey(), plan.receivers().get(1).identityKey());
    }
    @Test void switchOffPreservesMerchantRetainedBehaviorEvenWithoutBinding() {
        var plan = ProfitSharePlanner.plan(order, List.of(club(null, null)), "platform", 100,
                id -> Optional.of("seller"), false, false, "wx_platform");
        assertTrue(plan.nothingToShare());
        assertEquals(ProfitSharePlanner.ReceiverKind.RETAINED, plan.lines().get(0).kind());
        assertTrue(plan.receivers().isEmpty());
        assertFalse(PayoutSwitches.allOff().withPersonalClubEnabled(true).personalClubEnabled());
    }
    @Test void labelReadsOnlyPersistedClubSnapshot() {
        var line = new com.chengyinhub.business.domain.EcomProfitShareLine();
        line.setClubId(8L);
        line.setClubName("原俱乐部");
        assertEquals("来自俱乐部 原俱乐部", line.getSourceLabel());
        line.setReceiverMemberId(99L);
        assertEquals("来自俱乐部 原俱乐部", line.getSourceLabel());
    }
}
