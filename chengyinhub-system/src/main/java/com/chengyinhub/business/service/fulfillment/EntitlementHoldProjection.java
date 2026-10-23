package com.chengyinhub.business.service.fulfillment;

import java.time.Instant;
import java.util.Objects;

import com.chengyinhub.common.constant.EntitlementInvalidReason;

/**
 * C07 exclusive adapter: held multi-merchant entitlement projection.
 *
 * <p>Mirrors {@code registration_entitlement} occupancy: status 0 (应得/已售未核销)
 * occupies capacity; 1 already redeemed; 2 invalid. Projects a hold keyed by the
 * original requestId. Does not call {@code IPerkLedgerService#reserveEntitlements}
 * or {@code #redeemChapter}, and does not write the mapper.
 */
public final class EntitlementHoldProjection {

    /** {@code registration_entitlement.status}: 已售未核销, occupies capacity. */
    public static final int STATUS_HELD = 0;
    public static final int STATUS_REDEEMED = 1;
    public static final int STATUS_INVALID = 2;

    public enum Outcome {
        HELD,
        REDEEMED,
        INVALID,
        UNKNOWN
    }

    /**
     * One chapter hold for one original issue request. {@code merchantId} is the
     * fulfilling store for that chapter, not a transfer recipient.
     */
    public record HoldFact(
            String originalRequestId,
            Long registrationId,
            Long chapterId,
            Long merchantId,
            Integer status,
            Instant validEndExclusive,
            String invalidReason) {

        public HoldFact {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            invalidReason = invalidReason == null ? "" : invalidReason;
        }
    }

    public record HoldView(
            String originalRequestId,
            Outcome outcome,
            boolean occupiesCapacity,
            boolean executedReserve,
            boolean executedRedeem,
            boolean executedAward) {

        public HoldView {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
        }
    }

    /**
     * Project the hold that already belongs to {@code originalRequestId}.
     * Missing source is UNKNOWN and does not mint a new request.
     */
    public HoldView project(HoldFact fact, Instant serverNow) {
        Objects.requireNonNull(serverNow, "serverNow");
        if (fact == null) {
            return unknown(null);
        }
        String requestId = fact.originalRequestId();
        if (requestId == null || fact.registrationId() == null || fact.registrationId() <= 0L
                || fact.chapterId() == null || fact.chapterId() <= 0L
                || fact.merchantId() == null || fact.merchantId() <= 0L) {
            return unknown(requestId);
        }
        if (fact.status() == null) {
            return unknown(requestId);
        }
        if (fact.status() == STATUS_REDEEMED) {
            return new HoldView(requestId, Outcome.REDEEMED, false, false, false, false);
        }
        if (fact.status() == STATUS_INVALID) {
            return new HoldView(requestId, Outcome.INVALID, false, false, false, false);
        }
        if (fact.status() != STATUS_HELD) {
            return unknown(requestId);
        }
        if (fact.validEndExclusive() == null) {
            return unknown(requestId);
        }
        if (!serverNow.isBefore(fact.validEndExclusive())) {
            return new HoldView(requestId, Outcome.INVALID, false, false, false, false);
        }
        if (!fact.invalidReason().isEmpty()
                && (EntitlementInvalidReason.REFUND.equals(fact.invalidReason())
                || EntitlementInvalidReason.ORGANIZER_CANCEL.equals(fact.invalidReason())
                || EntitlementInvalidReason.FULFILLMENT_GAP.equals(fact.invalidReason())
                || EntitlementInvalidReason.MANUAL.equals(fact.invalidReason()))) {
            return new HoldView(requestId, Outcome.INVALID, false, false, false, false);
        }
        return new HoldView(requestId, Outcome.HELD, true, false, false, false);
    }

    public HoldView requery(HoldFact fact, Instant serverNow) {
        return project(fact, serverNow);
    }

    private static HoldView unknown(String requestId) {
        return new HoldView(requestId, Outcome.UNKNOWN, false, false, false, false);
    }
}
