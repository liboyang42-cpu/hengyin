package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.service.fulfillment.MerchantSupplyDryRun.Decision;
import com.chengyinhub.business.service.fulfillment.MerchantSupplyDryRun.Outcome;
import com.chengyinhub.business.service.fulfillment.MerchantSupplyDryRun.SkuSupplyFact;

class MerchantSupplyDryRunTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant NOON = LocalDateTime.of(2026, 10, 23, 12, 0)
            .atZone(SHANGHAI).toInstant();
    private static final Instant VALID_START = LocalDateTime.of(2026, 10, 1, 0, 0)
            .atZone(SHANGHAI).toInstant();
    private static final Instant VALID_END = LocalDateTime.of(2026, 11, 1, 0, 0)
            .atZone(SHANGHAI).toInstant();

    private final MerchantSupplyDryRun dryRun = new MerchantSupplyDryRun();

    @Test
    void bookableSkuAcceptsInsideHoursWithoutWritingStockOrReservation() {
        Decision decision = dryRun.evaluate(bookable(10L, 2, 4), NOON, 1);
        assertEquals(Outcome.ACCEPT, decision.outcome());
        assertEquals("c07-supply-1", decision.originalRequestId());
        assertEquals(2, decision.remainingCapacity());
        assertFalse(decision.writesStock());
        assertFalse(decision.writesReservation());
        assertEquals(true, decision.grantsIssue());
    }

    @Test
    void exhaustedHoursCapacityRejectsAndStillDoesNotReserve() {
        Decision decision = dryRun.evaluate(bookable(10L, 4, 4), NOON, 1);
        assertEquals(Outcome.REJECT, decision.outcome());
        assertEquals("hours-capacity exhausted", decision.reason());
        assertFalse(decision.writesReservation());
        assertEquals("c07-supply-1", decision.originalRequestId());
    }

    @Test
    void missingHoursOnBookableSkuIsUnknownNotAWrite() {
        SkuSupplyFact noHours = new SkuSupplyFact(8L, 3L, 10L, true, 60, 4, 4, 0,
                VALID_START, VALID_END, null, null, "c07-supply-2");
        Decision decision = dryRun.evaluate(noHours, NOON, 1);
        assertEquals(Outcome.UNKNOWN, decision.outcome());
        assertEquals("c07-supply-2", decision.originalRequestId());
        assertFalse(decision.grantsIssue());
    }

    @Test
    void missingValidityIsUnknownAndKeepsOriginalRequestId() {
        SkuSupplyFact noWindow = new SkuSupplyFact(8L, 3L, 10L, false, 60, 0, null, null,
                null, null, null, null, "c07-supply-3");
        Decision decision = dryRun.evaluate(noWindow, NOON, 1);
        assertEquals(Outcome.UNKNOWN, decision.outcome());
        assertEquals("c07-supply-3", decision.originalRequestId());
    }

    @Test
    void serverNowIsRequired() {
        assertThrows(NullPointerException.class, () -> dryRun.evaluate(bookable(10L, 0, 4), null, 1));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueAcceptApplies() {
        MerchantSupplyDryRun supply = new MerchantSupplyDryRun();
        Decision neverSupply = supply.recoverOriginal("c07-supply-never-seen");
        assertEquals(Outcome.UNKNOWN, neverSupply.outcome());
        assertEquals("c07-supply-never-seen", neverSupply.originalRequestId());
        assertEquals(0, supply.holdCount("c07-supply-never-seen"));
        supply.evaluate(new SkuSupplyFact(8L, 3L, null, false, 60, 0, null, null,
                VALID_START, VALID_END, null, null, "c07-supply-never-seen"), NOON, 1);
        assertEquals(0, supply.holdCount("c07-supply-never-seen"));
        Decision accepted = supply.evaluate(new SkuSupplyFact(8L, 3L, 10L, false, 60, 0, null, null,
                VALID_START, VALID_END, null, null, "c07-supply-never-seen"), NOON, 1);
        assertEquals(Outcome.ACCEPT, accepted.outcome());
        assertEquals(1, supply.holdCount("c07-supply-never-seen"));
        assertEquals(Outcome.ACCEPT, supply.recoverOriginal("c07-supply-never-seen").outcome());
    }

    @Test
    void recoverFromDeskReceiptReadsAppliedWithoutHolding() {
        MerchantSupplyDryRun supply = new MerchantSupplyDryRun();
        MerchantAccessContext access = new MerchantAccessContext(7L, 3L, 7L, "OWNER");
        MerchantSupplyDryRun.WaitingSourceException waiting = assertThrows(
                MerchantSupplyDryRun.WaitingSourceException.class,
                () -> supply.recoverFromDeskReceipt(access, "desk-req-01", null));
        assertEquals(MerchantSupplyDryRun.DESK_RECEIPT, waiting.getWaitingCapability());
        assertEquals(0, supply.holdCount("desk-req-01"));
        assertNull(supply.recoverFromDeskReceipt(null, "desk-req-01", (ctx, requestId) -> {
            throw new AssertionError("missing access must not read");
        }));
        MerchantSupplyDryRun.WaitingSourceException missingDesk = assertThrows(
                MerchantSupplyDryRun.WaitingSourceException.class,
                () -> MerchantSupplyDryRun.fromDesk(null).receipt(access, "desk-req-01"));
        assertEquals(MerchantSupplyDryRun.DESK_RECEIPT, missingDesk.getWaitingCapability());

        Map<String, Object> absent = new LinkedHashMap<String, Object>();
        absent.put("state", "NOT_FOUND");
        absent.put("requestId", "desk-missing");
        Map<String, Object> applied = new LinkedHashMap<String, Object>();
        applied.put("state", "APPLIED");
        applied.put("requestId", "desk-req-01");
        applied.put("action", "SAVE_SKU");
        MerchantSupplyDryRun.DeskReceiptSource source = (ctx, requestId) ->
                "desk-missing".equals(requestId) ? absent : applied;
        assertNull(supply.recoverFromDeskReceipt(access, "desk-missing", source));
        assertEquals(0, supply.holdCount("desk-missing"));
        Map<String, Object> recovered = supply.recoverFromDeskReceipt(access, "desk-req-01", source);
        assertEquals("APPLIED", recovered.get("state"));
        assertEquals("SAVE_SKU", recovered.get("action"));
        assertEquals(0, supply.holdCount("desk-req-01"));
        assertFalse(supply.recoverOriginal("desk-req-01").grantsIssue());
    }

    private static SkuSupplyFact bookable(long stock, int reserved, int total) {
        return new SkuSupplyFact(8L, 3L, stock, true, 60, 4, total, reserved,
                VALID_START, VALID_END, 10 * 60, 21 * 60, "c07-supply-1");
    }
}
