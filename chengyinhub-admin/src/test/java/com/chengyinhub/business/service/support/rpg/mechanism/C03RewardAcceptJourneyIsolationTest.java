package com.chengyinhub.business.service.support.rpg.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * C03 first reward-accept journey (isolation): explicit trigger actor →
 * original requestId accept of a frozen grant → duplicate id does not grant
 * twice → UNKNOWN recover keeps intent. Not a second wallet. Runtime unwired.
 */
class C03RewardAcceptJourneyIsolationTest {

    private static final String REQUEST_ID = "c03-journey-reward";

    @Test
    void explicitActorThenAcceptRecoverOriginal() {
        TriggerActorProjection.View actor = new TriggerActorProjection().project(
                new TriggerActorProjection.ActorFact(REQUEST_ID, "player-7", true, false));
        assertEquals(TriggerActorProjection.Outcome.BOUND, actor.outcome());
        assertEquals(REQUEST_ID, actor.originalRequestId());
        assertFalse(actor.defaultFired());

        RewardAcceptLedger ledger = new RewardAcceptLedger();
        RewardAcceptLedger.Decision accepted = ledger.accept(new RewardAcceptLedger.AcceptFact(
                REQUEST_ID, actor.actorId(), "badge", "frozen-grant-hash", 0L, true));
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, accepted.outcome());
        assertFalse(accepted.wroteWallet());
        assertEquals(REQUEST_ID, accepted.originalRequestId());

        RewardAcceptLedger.Decision replay = ledger.accept(new RewardAcceptLedger.AcceptFact(
                REQUEST_ID, actor.actorId(), "badge", "frozen-grant-hash", 0L, true));
        assertEquals(RewardAcceptLedger.Outcome.REPLAY, replay.outcome());
        assertFalse(replay.secondEffect());
        assertEquals(REQUEST_ID, replay.originalRequestId());

        RewardAcceptLedger.Decision keep = ledger.markUnknown(REQUEST_ID, "server UNKNOWN");
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, keep.outcome());
        assertFalse(keep.mintedNewRequestId());
        RewardAcceptLedger.Decision recovered = ledger.recover(REQUEST_ID);
        assertEquals(REQUEST_ID, recovered.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, recovered.outcome());
        assertFalse(recovered.mintedNewRequestId());

        RewardAcceptLedger unknownLedger = new RewardAcceptLedger();
        unknownLedger.markUnknown(REQUEST_ID + "-unk", "server receipt UNKNOWN");
        RewardAcceptLedger.Decision unknownRecover = unknownLedger.recover(REQUEST_ID + "-unk");
        assertEquals(REQUEST_ID + "-unk", unknownRecover.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, unknownRecover.outcome());
        assertFalse(unknownRecover.projectionTakenAsSuccess());

        RewardAcceptLedger.Decision omitted = new RewardAcceptLedger().accept(
                new RewardAcceptLedger.AcceptFact(null, "player-7", "badge", "hash", 0L, true));
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, omitted.outcome());
        assertNull(omitted.originalRequestId());
    }
}
