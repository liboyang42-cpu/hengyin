package com.chengyinhub.business.reward;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Server-only facts. No HTTP input, money, randomness or personal-point mutation. */
public final class NonCashRewardContracts {
    private NonCashRewardContracts() { }
    public enum ScopeType { THEME, MAP }
    public enum RewardKind { PHYSICAL, COUPON, SERVICE }
    public enum AwardState { AWARDED, REDEEMED, EXPIRED, REVERSED }
    public enum Allocation { FINITE, RESERVED_GUARANTEE }
    public static final class Scope {
        public final ScopeType type;
        public final String contextId, releaseId, instanceId;
        /** instanceId is runId for THEME, seasonId for MAP. No registration is implied. */
        public Scope(ScopeType type, String contextId, String releaseId, String instanceId) {
            this.type = Objects.requireNonNull(type, "SCOPE_REQUIRED"); this.contextId = id(contextId);
            this.releaseId = id(releaseId); this.instanceId = id(instanceId);
        }
        public String key() { return hash(type.name(), contextId, releaseId, instanceId); }
    }
    public static final class Pledge {
        public final String pledgeId, pledgeVersion, approvalId, rulesVersion, awardSlot, rewardTitle, redemptionConditions;
        public final Scope scope;
        public final long merchantId, storeId, productId, skuId, quantity, validFrom, validUntil;
        public final Allocation allocation;
        public final RewardKind rewardKind;
        public Pledge(String pledgeId, String pledgeVersion, String approvalId, Scope scope,
                      String rulesVersion, String awardSlot, long merchantId, long storeId, long productId,
                      long skuId, long quantity, long validFrom, long validUntil, Allocation allocation, RewardKind rewardKind,
                      String rewardTitle, String redemptionConditions) {
            this.pledgeId = id(pledgeId); this.pledgeVersion = id(pledgeVersion); this.approvalId = id(approvalId);
            this.scope = Objects.requireNonNull(scope); this.rulesVersion = id(rulesVersion); this.awardSlot = id(awardSlot);
            this.merchantId = positive(merchantId); this.storeId = positive(storeId); this.productId = positive(productId);
            this.skuId = positive(skuId); this.quantity = positive(quantity); this.validFrom = validFrom;
            require(validFrom >= 0 && validUntil > validFrom, "INVALID_VALIDITY"); this.validUntil = validUntil;
            this.allocation = Objects.requireNonNull(allocation);
            require(rewardKind == RewardKind.PHYSICAL, "SUPPLY_ADAPTER_NOT_IMPLEMENTED"); this.rewardKind = rewardKind; this.rewardTitle = id(rewardTitle);
            this.redemptionConditions = id(redemptionConditions);
        }
        public String fingerprint() {
            return hash(pledgeId, pledgeVersion, approvalId, scope.key(), rulesVersion, awardSlot,
                    "" + merchantId, "" + storeId, "" + productId, "" + skuId, "" + quantity,
                    "" + validFrom, "" + validUntil, allocation.name(), rewardKind.name(), rewardTitle, redemptionConditions);
        }
    }
    public static final class Qualification {
        public final Scope scope;
        public final long memberId, occurredAt;
        public final String completionKey, entitlementKey, rulesVersion, awardSlot;
        /** completionKey is stable across duplicate transport events, never requestId. */
        public Qualification(Scope scope, long memberId, String completionKey, String entitlementKey,
                             String rulesVersion, String awardSlot, long occurredAt) {
            this.scope = Objects.requireNonNull(scope); this.memberId = positive(memberId);
            this.completionKey = id(completionKey); this.entitlementKey = id(entitlementKey);
            this.rulesVersion = id(rulesVersion); this.awardSlot = id(awardSlot); this.occurredAt = occurredAt;
        }
        public String awardKey() { return hash("AWARD", scope.key(), rulesVersion, awardSlot, "" + memberId, completionKey); }
        public String reservationKey() { return hash("RESERVATION", scope.key(), rulesVersion, awardSlot, "" + memberId, entitlementKey); }
    }
    /**
     * All calls run inside the reward transaction. Implementations must read approved facts and current
     * merchant/store/actor authority under compatible database locks, with the SAME transaction manager.
     * No boolean from a client, AI, GPS arrival or payment-status shortcut is a valid implementation.
     * No production implementation is supplied. Revocation and closed-store checks fail closed.
     */
    public interface Authority {
        Pledge approvedPledge(String pledgeId);
        void requireCurrentFulfillment(Pledge pledge, long now);
        /** Reservation eligibility, BEFORE any guarantee is displayed; not a completion event. */
        Qualification guaranteeEligibility(long memberId, String eligibilityId);
        /** Must reject test, uncommitted, revoked, foreign-owner and nonqualifying events. */
        Qualification qualifyingEvent(long memberId, String eventId);
        /** Must resolve an authoritative namespaced receipt bound to EXACT awardId, actor's CURRENT
         * store role, member, store and consumed quantity=1. Store additionally claims that receipt
         * once transactionally; mere member/store matching is insufficient. */
        void requireConsumption(long staffActorId, String sourceReceiptId, Award award, long now);
        /** Trusted reviewed aftercare, bound to this award. Does NOT execute monetary refunds. */
        void requireReversal(String caseId, Award award, long now);
    }
    /** Player-safe projection; no authorization or redemption credential is encoded here. */
    public static final class Award {
        public final String awardId, rewardKind = "PHYSICAL", rewardTitle, redemptionConditions;
        public final Scope scope;
        public final String rulesVersion;
        public final long merchantId, storeId, quantity = 1, validFrom, validUntil, awardedAt, asOf;
        public final AwardState state;
        Award(NonCashRewardRows.AwardRow row, Pledge p, long now) {
            awardId = row.awardId; scope = p.scope; rulesVersion = p.rulesVersion;
            merchantId = p.merchantId; storeId = p.storeId; rewardTitle = p.rewardTitle;
            redemptionConditions = p.redemptionConditions; validFrom = p.validFrom; validUntil = p.validUntil;
            awardedAt = row.awardedAt; asOf = now; state = AwardState.valueOf(row.state);
        }
    }
    static String id(String value) {
        require(value != null && !value.trim().isEmpty() && value.length() <= 512, "INVALID_REFERENCE");
        unicode(value); return value;
    }
    /** Preserve valid Unicode and whitespace verbatim; do not silently normalize business identity. */
    private static void unicode(String value) {
        require(value != null, "INVALID_REFERENCE");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)), "INVALID_UNICODE"); i++;
            } else require(!Character.isLowSurrogate(c), "INVALID_UNICODE");
        }
    }
    static long positive(long value) { require(value > 0, "POSITIVE_VALUE_REQUIRED"); return value; }
    static void require(boolean value, String error) { if (!value) throw new IllegalStateException(error); }
    static String hash(String... values) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String value : values) { unicode(value); byte[] b = value.getBytes(StandardCharsets.UTF_8);
                d.update((b.length + ":").getBytes(StandardCharsets.UTF_8)); d.update(b); }
            StringBuilder out = new StringBuilder(); for (byte b : d.digest()) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
