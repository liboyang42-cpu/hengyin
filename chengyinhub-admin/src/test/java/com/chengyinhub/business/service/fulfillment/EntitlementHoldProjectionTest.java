package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.fulfillment.EntitlementHoldProjection.HoldFact;
import com.chengyinhub.business.service.fulfillment.EntitlementHoldProjection.HoldView;
import com.chengyinhub.business.service.fulfillment.EntitlementHoldProjection.Outcome;
import com.chengyinhub.common.constant.EntitlementInvalidReason;

class EntitlementHoldProjectionTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Instant END = Instant.parse("2026-11-01T00:00:00Z");
    private final EntitlementHoldProjection projection = new EntitlementHoldProjection();

    @Test
    void soldUnredeemedOccupiesCapacityWithoutCallingReserveOrRedeem() {
        HoldFact fact = new HoldFact("c07-hold-1", 78L, 124L, 9L,
                EntitlementHoldProjection.STATUS_HELD, END, "");
        HoldView view = projection.project(fact, NOW);
        assertEquals(Outcome.HELD, view.outcome());
        assertTrue(view.occupiesCapacity());
        assertFalse(view.executedReserve());
        assertFalse(view.executedRedeem());
        assertFalse(view.executedAward());
        assertEquals("c07-hold-1", view.originalRequestId());
        assertEquals(view.outcome(), projection.requery(fact, NOW).outcome());
    }

    @Test
    void missingStatusIsUnknownNotANewRequest() {
        HoldFact fact = new HoldFact("c07-hold-2", 78L, 124L, 9L, null, END, "");
        HoldView view = projection.project(fact, NOW);
        assertEquals(Outcome.UNKNOWN, view.outcome());
        assertEquals("c07-hold-2", view.originalRequestId());
        assertFalse(view.occupiesCapacity());
    }

    @Test
    void refundInvalidReasonDoesNotOccupyCapacity() {
        HoldFact fact = new HoldFact("c07-hold-3", 78L, 124L, 9L,
                EntitlementHoldProjection.STATUS_HELD, END, EntitlementInvalidReason.REFUND);
        HoldView view = projection.project(fact, NOW);
        assertEquals(Outcome.INVALID, view.outcome());
        assertFalse(view.occupiesCapacity());
        assertFalse(view.executedRedeem());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueHoldApplies() {
        EntitlementHoldProjection holds = new EntitlementHoldProjection();
        HoldView neverHold = holds.recoverOriginal("c07-hold-never-seen");
        assertEquals("c07-hold-never-seen", neverHold.originalRequestId());
        assertEquals(Outcome.UNKNOWN, neverHold.outcome());
        assertEquals(0, holds.occupyCount("c07-hold-never-seen"));
        HoldView unknownHold = holds.project(
                new HoldFact("c07-hold-never-seen", 78L, 124L, 9L, null, END, ""), NOW);
        assertEquals(Outcome.UNKNOWN, unknownHold.outcome());
        assertEquals(0, holds.occupyCount("c07-hold-never-seen"));
        HoldView held = holds.project(
                new HoldFact("c07-hold-never-seen", 78L, 124L, 9L,
                        EntitlementHoldProjection.STATUS_HELD, END, ""), NOW);
        assertEquals(Outcome.HELD, held.outcome());
        assertEquals(1, holds.occupyCount("c07-hold-never-seen"));
        assertEquals(Outcome.HELD, holds.recoverOriginal("c07-hold-never-seen").outcome());
        HoldView steal = holds.project(
                new HoldFact("c07-hold-never-seen", 99L, 1L, 2L,
                        EntitlementHoldProjection.STATUS_REDEEMED, END, ""), NOW);
        assertEquals(Outcome.HELD, steal.outcome());
        assertEquals(1, holds.occupyCount("c07-hold-never-seen"));
    }
}
