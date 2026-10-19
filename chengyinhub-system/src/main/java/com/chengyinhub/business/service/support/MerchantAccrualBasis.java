package com.chengyinhub.business.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Optional read-only explanation of the frozen one-person accrual contract.
 * This does not calculate settlementAmount, read current offer rates, or change a ledger.
 */
public final class MerchantAccrualBasis {
    private final String unitFee;
    private final Integer headCount;

    private MerchantAccrualBasis(String unitFee) {
        this.unitFee = unitFee;
        this.headCount = Integer.valueOf(1);
    }
    public String getUnitFee() { return unitFee; }
    public Integer getHeadCount() { return headCount; }

    /** Immutable projection input; values are copied from existing payable facts. */
    public static final class Fact {
        private final Long id;
        private final Long claimId;
        private final Long merchantMemberId;
        private final Long redemptionId;
        private final String eventKey;
        private final String entryType;
        private final String idempotencyKey;
        private final BigDecimal feeSnapshot;
        private final BigDecimal originalAmount;

        public Fact(Long id, Long claimId, Long merchantMemberId, Long redemptionId,
                    String eventKey, String entryType, String idempotencyKey,
                    BigDecimal feeSnapshot, BigDecimal originalAmount) {
            this.id = id; this.claimId = claimId; this.merchantMemberId = merchantMemberId;
            this.redemptionId = redemptionId; this.eventKey = eventKey; this.entryType = entryType;
            this.idempotencyKey = idempotencyKey; this.feeSnapshot = feeSnapshot;
            this.originalAmount = originalAmount;
        }
    }

    /**
     * Missing or ambiguous basis is null, never zero or an estimated head count.
     * The actual writer freezes ORIGIN + ACCRUAL with amount == fee for exactly
     * one player in one merchant chapter. A later REVERSAL is not another person.
     */
    public static MerchantAccrualBasis project(Long ownerMemberId, Long redemptionId, List<Fact> facts) {
        if (!positive(ownerMemberId) || !positive(redemptionId) || facts == null) return null;
        Fact original = null;
        for (Fact fact : facts) {
            if (fact == null || !ownerMemberId.equals(fact.merchantMemberId)
                    || !redemptionId.equals(fact.redemptionId)) return null;
            if ("ACCRUAL".equals(fact.entryType)) {
                if (original != null) return null;
                original = fact;
            } else if (!"REVERSAL".equals(fact.entryType)) {
                return null;
            }
        }
        if (original == null || !positive(original.id) || !positive(original.claimId)
                || !"ORIGIN".equals(original.eventKey)
                || !("accrual:" + redemptionId).equals(original.idempotencyKey)
                || original.feeSnapshot == null || original.feeSnapshot.signum() < 0
                || original.originalAmount == null
                || original.feeSnapshot.compareTo(original.originalAmount) != 0) return null;
        try {
            // Database precision is two decimals. Never round an unsupported
            // historical value into an apparently valid basis.
            return new MerchantAccrualBasis(original.feeSnapshot.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        } catch (ArithmeticException unsupportedPrecision) {
            return null;
        }
    }
    private static boolean positive(Long value) { return value != null && value.longValue() > 0L; }
}
