package com.chengyinhub.business.service.fulfillment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import com.chengyinhub.business.util.RefundPolicy;

/**
 * C07 exclusive adapter: isolation evidence for order/issue → hold →
 * booking/reschedule → in-store redeem → original refund estimate / aftercare.
 *
 * <p>Records what the original requestId already asked. Never calls
 * {@code IPerkLedgerService#redeemChapter}, {@code IRefundService}, or
 * {@code IMerchantAftercareService#respond}. Refund estimate reuses
 * {@link RefundPolicy#evaluate} (pure) and does not payout.
 */
public final class FulfillmentEvidence {

    public enum Stage {
        ORDER_ISSUE,
        HOLD,
        BOOKING,
        RESCHEDULE,
        IN_STORE_REDEEM,
        REFUND_ESTIMATE,
        AFTERCARE
    }

    public enum Outcome {
        RECORDED,
        UNKNOWN
    }

    public record ChainStep(
            Stage stage,
            String originalRequestId,
            Outcome outcome,
            boolean executedWriter,
            String detail) {

        public ChainStep {
            originalRequestId = originalRequestId == null ? "" : originalRequestId;
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            detail = detail == null ? "" : detail;
        }
    }

    public record BookingFact(String slotId, String newSlotId, boolean slotKnown) {
        public BookingFact {
            slotId = slotId == null ? "" : slotId;
            newSlotId = newSlotId == null ? "" : newSlotId;
        }
    }

    /**
     * Inputs already frozen on the original order. Snapshot deadline wins, same
     * as {@link RefundPolicy#evaluate}.
     */
    public record RefundEstimateFact(
            Long ownerType,
            Integer paymentStatus,
            Integer verificationStatus,
            Integer ticketMode,
            Date ticketStartTime,
            Date ticketEndTime,
            Date activityStartTime,
            Integer purchaseKind,
            Date snapshotDeadline,
            Date now) {
    }

    public record AftercareFact(boolean caseOpen, boolean merchantResponded) {
    }

    public record JourneyFacts(
            boolean orderIssued,
            boolean holdOccupiesCapacity,
            BookingFact booking,
            RefundEstimateFact refund,
            AftercareFact aftercare) {
    }

    /**
     * Walk the exclusive chain for one original requestId. Reschedule keeps that
     * id. In-store redeem / refund / aftercare are evidence only.
     */
    public List<ChainStep> recordJourney(String originalRequestId, JourneyFacts facts) {
        String requestId = requireRequestId(originalRequestId);
        Objects.requireNonNull(facts, "facts");
        List<ChainStep> steps = new ArrayList<ChainStep>();
        steps.add(facts.orderIssued
                ? recorded(Stage.ORDER_ISSUE, requestId, "actual issue already attributed to original request")
                : unknown(Stage.ORDER_ISSUE, requestId, "order issue missing"));
        steps.add(facts.holdOccupiesCapacity
                ? recorded(Stage.HOLD, requestId, "held entitlement occupies capacity")
                : unknown(Stage.HOLD, requestId, "hold missing"));
        BookingFact booking = facts.booking == null ? new BookingFact("", "", false) : facts.booking;
        if (!booking.slotKnown() || booking.slotId().isBlank()) {
            steps.add(unknown(Stage.BOOKING, requestId, "booking slot missing"));
            steps.add(unknown(Stage.RESCHEDULE, requestId, "no original booking to reschedule"));
        } else {
            steps.add(recorded(Stage.BOOKING, requestId, "slot " + booking.slotId()));
            if (booking.newSlotId().isBlank()) {
                steps.add(unknown(Stage.RESCHEDULE, requestId, "reschedule target missing"));
            } else {
                steps.add(recorded(Stage.RESCHEDULE, requestId,
                        "same requestId moved to slot " + booking.newSlotId()));
            }
        }
        steps.add(new ChainStep(Stage.IN_STORE_REDEEM, requestId, Outcome.RECORDED, false,
                "in-store redeem evidence only; PerkLedger.redeemChapter not called"));
        steps.add(refundEstimate(requestId, facts.refund));
        AftercareFact aftercare = facts.aftercare == null ? new AftercareFact(false, false) : facts.aftercare;
        if (!aftercare.caseOpen()) {
            steps.add(unknown(Stage.AFTERCARE, requestId, "aftercare case missing"));
        } else if (aftercare.merchantResponded()) {
            steps.add(new ChainStep(Stage.AFTERCARE, requestId, Outcome.RECORDED, false,
                    "aftercare already responded; MerchantAftercareService.respond not called"));
        } else {
            steps.add(recorded(Stage.AFTERCARE, requestId, "aftercare open; no respond writer"));
        }
        return Collections.unmodifiableList(steps);
    }

    public List<ChainStep> requery(String originalRequestId, JourneyFacts facts) {
        return recordJourney(originalRequestId, facts);
    }

    private static ChainStep refundEstimate(String requestId, RefundEstimateFact refund) {
        if (refund == null || refund.now() == null) {
            return unknown(Stage.REFUND_ESTIMATE, requestId, "refund estimate inputs missing");
        }
        RefundPolicy.Verdict verdict = RefundPolicy.evaluate(
                refund.ownerType(),
                refund.paymentStatus(),
                refund.verificationStatus(),
                refund.ticketMode(),
                refund.ticketStartTime(),
                refund.ticketEndTime(),
                refund.activityStartTime(),
                refund.purchaseKind(),
                refund.snapshotDeadline(),
                BigDecimal.ZERO,
                0L,
                refund.now());
        String detail = verdict.reason + (verdict.manualReview ? "; manualReview" : "");
        return new ChainStep(Stage.REFUND_ESTIMATE, requestId, Outcome.RECORDED, false, detail);
    }

    private static ChainStep recorded(Stage stage, String requestId, String detail) {
        return new ChainStep(stage, requestId, Outcome.RECORDED, false, detail);
    }

    private static ChainStep unknown(Stage stage, String requestId, String detail) {
        return new ChainStep(stage, requestId, Outcome.UNKNOWN, false, detail);
    }

    static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("originalRequestId 不能为空");
        }
        return requestId;
    }
}
