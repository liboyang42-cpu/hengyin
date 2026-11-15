package com.chengyinhub.business.collection;

import com.chengyinhub.business.payout.SellerOfRecordPolicy;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.Facts;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.ProductKind;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.SellerType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S2 collection model: pure policy rules (no database, no WeChat). */
class CollectionPolicyEngineTest {
    static final String SP = "1900000001";
    static final String SUB = "1900000999";

    static Map<SellerType, CollectionPolicy> policy(CollectionPolicy merchant, CollectionPolicy club, CollectionPolicy member) {
        Map<SellerType, CollectionPolicy> m = new EnumMap<>(SellerType.class);
        m.put(SellerType.MERCHANT, merchant);
        m.put(SellerType.CLUB, club);
        m.put(SellerType.MEMBER, member);
        return m;
    }

    static final CollectionPolicyEngine.Rules SPLIT_ALL = new CollectionPolicyEngine.Rules(
            policy(CollectionPolicy.SPLIT, CollectionPolicy.SPLIT, CollectionPolicy.SPLIT), true, SP);

    static Facts ticket(SellerType type, Long id, String state, String sub) {
        return new Facts(ProductKind.ACTIVITY_TICKET, false, type, id, state, sub);
    }

    @Test
    void officialTicketsAndPlatformProductsArePlatformCollected() {
        assertEquals(CollectionPolicy.PLATFORM, CollectionPolicyEngine.decide(
                new Facts(ProductKind.TOPIC_TICKET, true, SellerType.PLATFORM, null, null, null), false, SPLIT_ALL).policy());
        assertEquals(CollectionDecision.OFFICIAL, CollectionPolicyEngine.decide(
                ticket(SellerType.MEMBER, 5L, null, null), true, SPLIT_ALL).reason());
        for (ProductKind kind : new ProductKind[]{ProductKind.PLATFORM_PASS, ProductKind.MARKETING_PRODUCT,
                ProductKind.WORKSHOP_TEMPLATE, ProductKind.MERCHANT_PURCHASE, ProductKind.COOP_DEPOSIT}) {
            assertEquals(CollectionDecision.PLATFORM_PRODUCT, CollectionPolicyEngine.decide(
                    new Facts(kind, false, SellerType.MERCHANT, 7L, null, null), false, SPLIT_ALL).reason(), kind.name());
        }
    }

    @Test
    void boundThirdPartySellerIsSplitOnItsOwnSubMerchant() {
        CollectionDecision d = CollectionPolicyEngine.decide(ticket(SellerType.MERCHANT, 7L, "FINISH", SUB), false, SPLIT_ALL);
        assertEquals(CollectionPolicy.SPLIT, d.policy());
        assertEquals(SUB, d.subMchid());
        assertEquals(7L, d.sellerId());
        assertEquals(CollectionPolicy.SPLIT, CollectionPolicyEngine.decide(
                ticket(SellerType.CLUB, 3L, "FINISH", SUB), false, SPLIT_ALL).policy());
    }

    @Test
    void unboundSellerIsRefusedNeverPlatformCollected() {
        for (String state : new String[]{null, "DRAFT", "CHECKING", "AUDITING", "NEED_SIGN", "REJECTED"}) {
            CollectionDecision d = CollectionPolicyEngine.decide(ticket(SellerType.CLUB, 3L, state, null), false, SPLIT_ALL);
            assertEquals(CollectionPolicy.REFUSE, d.policy(), String.valueOf(state));
            assertEquals(CollectionDecision.SELLER_NOT_BOUND, d.reason());
        }
        assertEquals(CollectionDecision.SELLER_NOT_BOUND, CollectionPolicyEngine.decide(
                ticket(SellerType.MERCHANT, 7L, "FINISH", "not-a-mchid"), false, SPLIT_ALL).reason());
    }

    @Test
    void refusePolicyRailsOffPlatformSubMchidAndUnknownSellerAreRefused() {
        CollectionPolicyEngine.Rules refuseMerchants = new CollectionPolicyEngine.Rules(
                policy(CollectionPolicy.REFUSE, CollectionPolicy.SPLIT, CollectionPolicy.SPLIT), true, SP);
        assertEquals(CollectionDecision.POLICY_REFUSE, CollectionPolicyEngine.decide(
                ticket(SellerType.MERCHANT, 7L, "FINISH", SUB), false, refuseMerchants).reason());
        CollectionPolicyEngine.Rules railsOff = new CollectionPolicyEngine.Rules(SPLIT_ALL.thirdParty(), false, SP);
        assertEquals(CollectionDecision.SPLIT_RAILS_OFF, CollectionPolicyEngine.decide(
                ticket(SellerType.MERCHANT, 7L, "FINISH", SUB), false, railsOff).reason());
        assertEquals(CollectionDecision.SUB_MCHID_IS_PLATFORM, CollectionPolicyEngine.decide(
                ticket(SellerType.MERCHANT, 7L, "FINISH", SP), false, SPLIT_ALL).reason());
        assertEquals(CollectionDecision.SELLER_UNKNOWN, CollectionPolicyEngine.decide(null, false, SPLIT_ALL).reason());
        assertEquals(CollectionDecision.SELLER_UNKNOWN, CollectionPolicyEngine.decide(
                ticket(SellerType.UNKNOWN, null, null, null), false, SPLIT_ALL).reason());
        assertEquals(CollectionDecision.SELLER_UNKNOWN, CollectionPolicyEngine.decide(
                ticket(SellerType.MERCHANT, 0L, "FINISH", SUB), false, SPLIT_ALL).reason());
    }

    @Test
    void configuredPlatformOrGarbageForAThirdPartyIsReadAsRefuse() {
        assertEquals(CollectionPolicy.REFUSE, CollectionModelService.thirdPartyPolicy("merchant", "PLATFORM"));
        assertEquals(CollectionPolicy.REFUSE, CollectionModelService.thirdPartyPolicy("club", "platform-collect"));
        assertEquals(CollectionPolicy.REFUSE, CollectionModelService.thirdPartyPolicy("club", null));
        assertEquals(CollectionPolicy.SPLIT, CollectionModelService.thirdPartyPolicy("club", " split "));
        assertEquals(CollectionPolicy.REFUSE, CollectionModelService.thirdPartyPolicy("member", "REFUSE"));
    }

    /** The core promise: whatever the facts and rules, a third-party ticket is never PLATFORM. */
    @Test
    void noThirdPartyTicketIsEverPlatformCollected() {
        String[] states = {null, "DRAFT", "FINISH"};
        String[] subs = {null, SUB, SP, "x"};
        CollectionPolicy[] policies = {CollectionPolicy.SPLIT, CollectionPolicy.REFUSE, CollectionPolicy.PLATFORM, null};
        int checked = 0;
        for (ProductKind kind : new ProductKind[]{ProductKind.TOPIC_TICKET, ProductKind.ACTIVITY_TICKET, ProductKind.CLUB_DUES})
            for (SellerType type : new SellerType[]{SellerType.MERCHANT, SellerType.CLUB, SellerType.MEMBER, SellerType.UNKNOWN})
                for (Long id : new Long[]{null, 0L, 7L})
                    for (String state : states)
                        for (String sub : subs)
                            for (CollectionPolicy p : policies)
                                for (boolean rails : new boolean[]{true, false}) {
                                    Facts facts = new Facts(kind, false, type, id, state, sub);
                                    CollectionDecision d = CollectionPolicyEngine.decide(facts, false,
                                            new CollectionPolicyEngine.Rules(policy(p, p, p), rails, SP));
                                    assertNotEquals(CollectionPolicy.PLATFORM, d.policy(), facts + " " + p + " " + rails);
                                    if (d.split()) {
                                        assertEquals(SUB, d.subMchid());
                                        assertTrue(rails && p == CollectionPolicy.SPLIT && "FINISH".equals(state));
                                    }
                                    checked++;
                                }
        assertTrue(checked > 1000);
    }

    @Test
    void platformTakeComesFromTheFrozenRateHalfUpToTheFen() {
        assertEquals(207L, CollectionPolicyEngine.platformTakeFen(6900L, new BigDecimal("0.03")));
        assertEquals(0L, CollectionPolicyEngine.platformTakeFen(6900L, BigDecimal.ZERO));
        assertEquals(2L, CollectionPolicyEngine.platformTakeFen(15L, new BigDecimal("0.1")), "1.5 fen rounds half-up");
        assertEquals(-1L, CollectionPolicyEngine.platformTakeFen(6900L, null));
        assertEquals(-1L, CollectionPolicyEngine.platformTakeFen(6900L, new BigDecimal("1.01")));
        assertEquals(-1L, CollectionPolicyEngine.platformTakeFen(6900L, new BigDecimal("-0.01")));
    }

    @Test
    void sellerTypesAreTheOnesOfTheExistingSellerOfRecordPolicy() {
        assertEquals(5, SellerOfRecordPolicy.SellerType.values().length);
    }
}
