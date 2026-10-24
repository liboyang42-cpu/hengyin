package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.RegistrationEntitlement;
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
        assertEquals(Outcome.UNKNOWN, steal.outcome());
        assertEquals(Outcome.HELD, holds.recoverOriginal("c07-hold-never-seen").outcome());
        assertEquals(1, holds.occupyCount("c07-hold-never-seen"));
    }

    @Test
    void projectFromLedgerRequiresPerkListAndNewInstanceReadsExistingEntitlement() {
        EntitlementHoldProjection missing = new EntitlementHoldProjection();
        EntitlementHoldProjection.WaitingSourceException waiting = assertThrows(
                EntitlementHoldProjection.WaitingSourceException.class,
                () -> missing.projectFromLedger("c07-hold-led1", 78L, 124L, 9L, END, NOW));
        assertEquals(EntitlementHoldProjection.PERK_LIST, waiting.getWaitingCapability());
        assertEquals(0, missing.occupyCount("c07-hold-led1"));

        RegistrationEntitlement row = new RegistrationEntitlement();
        row.setRegistrationId(78L);
        row.setChapterId(124L);
        row.setStatus(EntitlementHoldProjection.STATUS_HELD);
        EntitlementHoldProjection.OriginalEntitlementSource source = registrationId -> {
            if (!Long.valueOf(78L).equals(registrationId)) {
                return Collections.emptyList();
            }
            return List.of(row);
        };
        EntitlementHoldProjection wired = new EntitlementHoldProjection(source);
        HoldView neverSeen = wired.projectFromLedger("c07-hold-led1", 78L, 999L, 9L, END, NOW);
        assertEquals(Outcome.UNKNOWN, neverSeen.outcome());
        assertEquals(0, wired.occupyCount("c07-hold-led1"));
        HoldView held = wired.projectFromLedger("c07-hold-led1", 78L, 124L, 9L, END, NOW);
        assertEquals(Outcome.HELD, held.outcome());
        assertTrue(held.occupiesCapacity());
        assertFalse(held.executedReserve());
        assertFalse(held.executedRedeem());
        assertEquals(1, wired.occupyCount("c07-hold-led1"));
        EntitlementHoldProjection restarted = new EntitlementHoldProjection(source);
        assertEquals(Outcome.HELD, restarted.projectFromLedger("c07-hold-led1", 78L, 124L, 9L, END, NOW).outcome());
        assertEquals(Outcome.UNKNOWN, restarted.recoverOriginal("c07-hold-missing").outcome());
        assertEquals(0, restarted.occupyCount("c07-hold-missing"));
    }

    @Test
    void recoverFromRedemptionsReadsActiveOriginalWithoutRedeeming() {
        EntitlementHoldProjection missing = new EntitlementHoldProjection();
        EntitlementHoldProjection.WaitingSourceException waiting = assertThrows(
                EntitlementHoldProjection.WaitingSourceException.class,
                () -> missing.recoverFromRedemptions("c07-rdm-1", 78L, 124L, NOW));
        assertEquals(EntitlementHoldProjection.PERK_REDEMPTIONS, waiting.getWaitingCapability());
        assertEquals(0, missing.occupyCount("c07-rdm-1"));

        Redemption reversed = new Redemption();
        reversed.setRegistrationId(78L);
        reversed.setChapterId(124L);
        reversed.setMerchantId(9L);
        reversed.setStatus("REVERSED");
        reversed.setReversedAt(new java.util.Date(1L));
        Redemption active = new Redemption();
        active.setRegistrationId(78L);
        active.setChapterId(124L);
        active.setMerchantId(9L);
        active.setStatus("ACTIVE");
        EntitlementHoldProjection.OriginalRedemptionSource source = registrationId -> {
            if (!Long.valueOf(78L).equals(registrationId)) {
                return Collections.emptyList();
            }
            return List.of(reversed, active);
        };
        EntitlementHoldProjection wired = new EntitlementHoldProjection(null, source);
        HoldView neverSeen = wired.recoverFromRedemptions("c07-rdm-1", 78L, 999L, NOW);
        assertEquals(Outcome.UNKNOWN, neverSeen.outcome());
        assertEquals(0, wired.occupyCount("c07-rdm-1"));
        HoldView redeemed = wired.recoverFromRedemptions("c07-rdm-1", 78L, 124L, NOW);
        assertEquals(Outcome.REDEEMED, redeemed.outcome());
        assertNull(redeemed.originalRequestId());
        assertFalse(redeemed.executedRedeem());
        assertFalse(redeemed.occupiesCapacity());
        assertEquals(0, wired.occupyCount("c07-rdm-1"));
        assertEquals(Outcome.UNKNOWN, wired.recoverOriginal("c07-rdm-1").outcome());
        EntitlementHoldProjection restarted = new EntitlementHoldProjection(null, source);
        assertEquals(Outcome.REDEEMED, restarted.recoverFromRedemptions("c07-rdm-1", 78L, 124L, NOW).outcome());
        assertEquals(Outcome.UNKNOWN, restarted.recoverOriginal("c07-rdm-1").outcome());
    }

    @Test
    void currentFulfillmentFactDoesNotReplaceImmutableOriginalOrCrossScope() {
        EntitlementHoldProjection holds = new EntitlementHoldProjection();
        HoldFact held = new HoldFact("c07-hold-live", 78L, 124L, 9L,
                EntitlementHoldProjection.STATUS_HELD, END, "");
        assertEquals(Outcome.HELD, holds.project(held, NOW).outcome());
        HoldFact redeemed = new HoldFact("c07-hold-live", 78L, 124L, 9L,
                EntitlementHoldProjection.STATUS_REDEEMED, END, "");
        assertEquals(Outcome.REDEEMED, holds.project(redeemed, NOW).outcome());
        assertEquals(Outcome.HELD, holds.recoverOriginal("c07-hold-live").outcome());
        assertEquals(1, holds.occupyCount("c07-hold-live"));
        HoldFact otherRegistration = new HoldFact("c07-hold-live", 99L, 124L, 9L,
                EntitlementHoldProjection.STATUS_HELD, END, "");
        assertEquals(Outcome.UNKNOWN, holds.project(otherRegistration, NOW).outcome());
        assertEquals(Outcome.HELD, holds.recoverOriginal("c07-hold-live").outcome());
    }
}
