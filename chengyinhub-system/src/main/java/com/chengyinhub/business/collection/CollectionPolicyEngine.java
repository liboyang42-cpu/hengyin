package com.chengyinhub.business.collection;

import com.chengyinhub.business.payout.ApplymentState;
import com.chengyinhub.business.payout.SellerOfRecordPolicy;

import java.util.Map;

/**
 * Pure rules of the S2 collection model. No database, no WeChat call.
 *
 * <ol>
 *   <li>A product the platform sells itself (pass, marketing product, template, merchant purchase, deposit) or an official
 *       ticket (platform content slot, or an activity run by the platform settlement account) → {@code PLATFORM}.</li>
 *   <li>Any other seller is third party. Its configured policy is {@code SPLIT} or {@code REFUSE}; {@code PLATFORM} is never
 *       accepted for a third party (that would be collect-then-redistribute) and is read as {@code REFUSE}.</li>
 *   <li>{@code SPLIT} needs the split rails switched on and a finished sub-merchant whose id is not the platform's own;
 *       otherwise the sale is refused with the reason, never collected by the platform instead.</li>
 * </ol>
 */
public final class CollectionPolicyEngine {

    /** Configured policy per third-party seller type. Missing type → REFUSE. */
    public record Rules(Map<SellerOfRecordPolicy.SellerType, CollectionPolicy> thirdParty, boolean splitRailsReady,
                        String platformMchid) { }

    private CollectionPolicyEngine() { }

    public static CollectionDecision decide(SellerOfRecordPolicy.Facts facts, boolean officialActivity, Rules rules) {
        if (facts == null || facts.kind() == null) {
            return CollectionDecision.refuse(SellerOfRecordPolicy.SellerType.UNKNOWN, null, CollectionDecision.SELLER_UNKNOWN);
        }
        switch (facts.kind()) {
            case TOPIC_TICKET: case ACTIVITY_TICKET: case CLUB_DUES: break;
            default: return CollectionDecision.platform(CollectionDecision.PLATFORM_PRODUCT);
        }
        if (facts.platformOwned() || officialActivity) return CollectionDecision.platform(CollectionDecision.OFFICIAL);
        SellerOfRecordPolicy.SellerType type = facts.sellerType();
        if (type == null || type == SellerOfRecordPolicy.SellerType.UNKNOWN || type == SellerOfRecordPolicy.SellerType.PLATFORM
                || facts.sellerId() == null || facts.sellerId() <= 0) {
            return CollectionDecision.refuse(type, facts.sellerId(), CollectionDecision.SELLER_UNKNOWN);
        }
        CollectionPolicy configured = rules == null || rules.thirdParty() == null ? null : rules.thirdParty().get(type);
        if (configured != CollectionPolicy.SPLIT) {
            return CollectionDecision.refuse(type, facts.sellerId(), CollectionDecision.POLICY_REFUSE);
        }
        if (!ApplymentState.FINISH.name().equals(facts.onboardingState())
                || facts.subMchid() == null || !facts.subMchid().matches("[0-9]{6,32}")) {
            return CollectionDecision.refuse(type, facts.sellerId(), CollectionDecision.SELLER_NOT_BOUND);
        }
        if (rules.platformMchid() != null && facts.subMchid().equals(rules.platformMchid())) {
            return CollectionDecision.refuse(type, facts.sellerId(), CollectionDecision.SUB_MCHID_IS_PLATFORM);
        }
        if (!rules.splitRailsReady()) {
            return CollectionDecision.refuse(type, facts.sellerId(), CollectionDecision.SPLIT_RAILS_OFF);
        }
        return new CollectionDecision(CollectionPolicy.SPLIT, type, facts.sellerId(), facts.subMchid(), CollectionDecision.SUB_MERCHANT);
    }

    /**
     * The platform's take on a split payment, in fen: {@code totalFen × rate}, half-up to the fen, the same rounding the
     * settlement ledger uses ({@code SettlementEntryServiceImpl#share}). The rate is never chosen here: it is the rate the
     * existing pricing path froze on the order. Null or out-of-range rate → -1 (caller sends the order to manual review).
     */
    public static long platformTakeFen(long totalFen, java.math.BigDecimal rate) {
        if (totalFen <= 0 || rate == null || rate.signum() < 0 || rate.compareTo(java.math.BigDecimal.ONE) > 0) return -1;
        return java.math.BigDecimal.valueOf(totalFen).multiply(rate)
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
