package com.chengyinhub.business.service.support.rpg.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RewardAcceptLedgerTest {

    @Test
    void explicitAcceptThenDuplicateDoesNotSecondGrant() {
        RewardAcceptLedger ledger = new RewardAcceptLedger();
        RewardAcceptLedger.AcceptFact fact = new RewardAcceptLedger.AcceptFact(
                "c03-rew-1", "actor-9", "badge", "hash-frozen", 1L, true);
        RewardAcceptLedger.Decision first = ledger.accept(fact);
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, first.outcome());
        assertEquals("c03-rew-1", first.originalRequestId());
        assertFalse(first.wroteWallet());
        assertFalse(first.secondEffect());
        RewardAcceptLedger.Decision replay = ledger.accept(fact);
        assertEquals(RewardAcceptLedger.Outcome.REPLAY, replay.outcome());
        assertEquals("c03-rew-1", replay.originalRequestId());
        assertFalse(replay.secondEffect());
        assertFalse(replay.wroteWallet());
        assertTrue(first.grantsAccept());
        assertTrue(replay.grantsAccept());
    }

    @Test
    void missingRequestIdStaysNull() {
        RewardAcceptLedger.Decision decision = new RewardAcceptLedger().accept(
                new RewardAcceptLedger.AcceptFact(null, "actor-9", "badge", "hash", 1L, true));
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, decision.outcome());
        assertNull(decision.originalRequestId());
        assertFalse(decision.grantsAccept());
    }

    @Test
    void missingFrozenGrantIsWaitingSourceNotInventedEconomy() {
        RewardAcceptLedger.Decision decision = new RewardAcceptLedger().accept(
                new RewardAcceptLedger.AcceptFact("c03-rew-wait", "actor-9", "badge", null, null, true));
        assertEquals(RewardAcceptLedger.Outcome.WAITING_SOURCE, decision.outcome());
        assertEquals("c03-rew-wait", decision.originalRequestId());
        assertFalse(decision.wroteWallet());
    }

    @Test
    void unknownRecoverKeepsOriginalIntent() {
        RewardAcceptLedger ledger = new RewardAcceptLedger();
        ledger.markUnknown("c03-rew-unk", "server receipt UNKNOWN");
        RewardAcceptLedger.Decision recovered = ledger.recover("c03-rew-unk");
        assertEquals("c03-rew-unk", recovered.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, recovered.outcome());
        assertFalse(recovered.mintedNewRequestId());
        assertFalse(recovered.projectionTakenAsSuccess());
    }
}
