package com.chengyinhub.business.service.fulfillment;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C07 exclusive adapter: SKU / validity / hours-capacity dry-run.
 *
 * <p>Reads {@code MerchantDeskServiceImpl} save-SKU range rules and
 * {@code FulfillmentWindowResolver} half-open windows as facts. Does not call
 * {@code saveSku}, does not write {@code pms_sku} / {@code pms_sku_booking_rule} /
 * {@code pms_sku_capacity_slot.capacity_reserved}, and does not touch
 * {@code BundleBookingService}.
 */
public final class MerchantSupplyDryRun {

    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    public enum Outcome {
        ACCEPT,
        REJECT,
        UNKNOWN
    }

    /**
     * Supply facts already resolved by the caller from existing writers.
     * Hours are minutes from midnight in Asia/Shanghai; null hours on a bookable
     * SKU are UNKNOWN (do not assume 24h).
     */
    public record SkuSupplyFact(
            Long skuId,
            Long merchantId,
            Long stock,
            boolean bookable,
            Integer slotMinutes,
            Integer capacityPerSlot,
            Integer capacityTotal,
            Integer capacityReserved,
            Instant validStartInclusive,
            Instant validEndExclusive,
            Integer hoursOpenMinute,
            Integer hoursCloseMinute,
            String originalRequestId) {

        public SkuSupplyFact {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
        }
    }

    public record Decision(
            Outcome outcome,
            String originalRequestId,
            int remainingCapacity,
            boolean writesStock,
            boolean writesReservation,
            String reason) {

        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            reason = reason == null ? "" : reason;
        }

        public boolean grantsIssue() {
            return outcome == Outcome.ACCEPT && !writesStock && !writesReservation;
        }
    }

    private final ConcurrentHashMap<String, Decision> originalIntentByRequestId =
            new ConcurrentHashMap<String, Decision>();
    private final ConcurrentHashMap<String, Integer> holdCountByRequestId =
            new ConcurrentHashMap<String, Integer>();

    /**
     * Dry-run only. {@code requestedQty} is how many units the original request
     * asked to hold; this method never deducts them.
     * Non-UNKNOWN original is unique; UNKNOWN observation does not occupy.
     */
    public Decision evaluate(SkuSupplyFact fact, Instant serverNow, int requestedQty) {
        Decision decision = compute(fact, serverNow, requestedQty);
        rememberOriginal(decision);
        return decision;
    }

    private Decision compute(SkuSupplyFact fact, Instant serverNow, int requestedQty) {
        Objects.requireNonNull(serverNow, "serverNow");
        if (fact == null) {
            return unknown(null, "supply fact missing");
        }
        String requestId = fact.originalRequestId();
        if (requestId == null || fact.skuId() == null || fact.skuId() <= 0L
                || fact.merchantId() == null || fact.merchantId() <= 0L) {
            return unknown(requestId, "sku or originalRequestId missing");
        }
        if (requestedQty < 1) {
            return reject(requestId, 0, "requested qty must be at least 1");
        }
        if (fact.stock() == null) {
            return unknown(requestId, "stock missing");
        }
        if (fact.stock() < requestedQty) {
            return reject(requestId, remaining(fact), "stock insufficient");
        }
        if (fact.validStartInclusive() == null || fact.validEndExclusive() == null) {
            return unknown(requestId, "validity window missing");
        }
        if (!fact.validStartInclusive().isBefore(fact.validEndExclusive())) {
            return unknown(requestId, "validity window not half-open");
        }
        if (serverNow.isBefore(fact.validStartInclusive()) || !serverNow.isBefore(fact.validEndExclusive())) {
            return reject(requestId, remaining(fact), "outside validity window");
        }
        if (fact.bookable()) {
            Decision booking = evaluateBooking(fact, serverNow, requestedQty, requestId);
            if (booking.outcome() != Outcome.ACCEPT) {
                return booking;
            }
        }
        return accept(requestId, remaining(fact));
    }

    public Decision recoverOriginal(String originalRequestId) {
        String id = FulfillmentEvidence.blankToNull(originalRequestId);
        if (id == null) {
            return unknown(null, "original intent unknown");
        }
        Decision original = originalIntentByRequestId.get(id);
        return original == null ? unknown(id, "original intent unknown") : original;
    }

    public int holdCount(String originalRequestId) {
        String id = FulfillmentEvidence.blankToNull(originalRequestId);
        if (id == null) {
            return 0;
        }
        Integer count = holdCountByRequestId.get(id);
        return count == null ? 0 : count;
    }

    private void rememberOriginal(Decision decision) {
        if (decision.originalRequestId() == null || decision.outcome() == Outcome.UNKNOWN) {
            return;
        }
        originalIntentByRequestId.putIfAbsent(decision.originalRequestId(), decision);
        if (decision.outcome() == Outcome.ACCEPT) {
            holdCountByRequestId.putIfAbsent(decision.originalRequestId(), 1);
        }
    }

    private Decision evaluateBooking(SkuSupplyFact fact, Instant serverNow, int requestedQty, String requestId) {
        Integer slot = fact.slotMinutes();
        Integer perSlot = fact.capacityPerSlot();
        if (slot == null || perSlot == null) {
            return unknown(requestId, "booking rule missing");
        }
        if (slot < 15 || slot > 480 || slot % 15 != 0) {
            return reject(requestId, remaining(fact), "slot minutes invalid");
        }
        if (perSlot < 1 || perSlot > 1000) {
            return reject(requestId, remaining(fact), "bookable capacity per slot must be 1-1000");
        }
        if (fact.capacityTotal() == null || fact.capacityReserved() == null) {
            return unknown(requestId, "hours-capacity slot missing");
        }
        if (fact.capacityReserved() < 0 || fact.capacityTotal() < 0
                || fact.capacityReserved() > fact.capacityTotal()) {
            return unknown(requestId, "capacity slot inconsistent");
        }
        int remaining = fact.capacityTotal() - fact.capacityReserved();
        if (remaining < requestedQty) {
            return reject(requestId, remaining, "hours-capacity exhausted");
        }
        if (fact.hoursOpenMinute() == null || fact.hoursCloseMinute() == null) {
            return unknown(requestId, "business hours missing");
        }
        int open = fact.hoursOpenMinute();
        int close = fact.hoursCloseMinute();
        if (open < 0 || close < 0 || open >= close || close > 24 * 60) {
            return unknown(requestId, "business hours not a documented daytime window");
        }
        ZonedDateTime local = serverNow.atZone(SHANGHAI);
        int minuteOfDay = local.getHour() * 60 + local.getMinute();
        if (minuteOfDay < open || minuteOfDay >= close) {
            return reject(requestId, remaining, "outside business hours");
        }
        return accept(requestId, remaining);
    }

    private static int remaining(SkuSupplyFact fact) {
        if (fact.bookable() && fact.capacityTotal() != null && fact.capacityReserved() != null) {
            return Math.max(0, fact.capacityTotal() - fact.capacityReserved());
        }
        if (fact.stock() == null || fact.stock() > Integer.MAX_VALUE) {
            return 0;
        }
        return fact.stock().intValue();
    }

    private static Decision accept(String requestId, int remaining) {
        return new Decision(Outcome.ACCEPT, requestId, remaining, false, false, "dry-run accept");
    }

    private static Decision reject(String requestId, int remaining, String reason) {
        return new Decision(Outcome.REJECT, requestId, remaining, false, false, reason);
    }

    private static Decision unknown(String requestId, String reason) {
        return new Decision(Outcome.UNKNOWN, requestId, 0, false, false, reason);
    }
}
