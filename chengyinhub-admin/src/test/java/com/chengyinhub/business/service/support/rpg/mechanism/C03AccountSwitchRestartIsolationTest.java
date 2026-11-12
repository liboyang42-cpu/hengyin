package com.chengyinhub.business.service.support.rpg.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * C12 isolation: another actor/request cannot rewrite the original accept;
 * in-process recover keeps original ACCEPTED/UNKNOWN; missing requestId stays
 * Java {@code null}, never {@code ""}.
 */
class C03AccountSwitchRestartIsolationTest {

    private static final String ORIGINAL = "c03-acct-owner";
    private static final String OTHER = "c03-acct-other";

    @Test
    void otherRequestDoesNotRewriteOriginalAccept() {
        RewardAcceptLedger ledger = new RewardAcceptLedger();
        RewardAcceptLedger.Decision first = ledger.accept(new RewardAcceptLedger.AcceptFact(
                ORIGINAL, "player-7", "badge", "frozen-grant-hash", 0L, true));
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, first.outcome());
        assertEquals(ORIGINAL, first.originalRequestId());

        RewardAcceptLedger.Decision steal = ledger.accept(new RewardAcceptLedger.AcceptFact(
                ORIGINAL, "player-9", "badge", "other-hash", 1L, true));
        assertEquals(RewardAcceptLedger.Outcome.REPLAY, steal.outcome());
        assertFalse(steal.secondEffect());
        assertEquals(ORIGINAL, steal.originalRequestId());
        assertEquals(ORIGINAL, ledger.existing(ORIGINAL).requestId());
        assertEquals(RewardAcceptLedger.APPLIED, ledger.existing(ORIGINAL).status());

        RewardAcceptLedger.Decision independent = ledger.accept(new RewardAcceptLedger.AcceptFact(
                OTHER, "player-9", "badge", "frozen-grant-hash", 0L, true));
        assertEquals(OTHER, independent.originalRequestId());
        assertNotEquals(first.originalRequestId(), independent.originalRequestId());
        assertEquals(ORIGINAL, ledger.recover(ORIGINAL).originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, ledger.recover(ORIGINAL).outcome());
    }

    @Test
    void inProcessRecoverKeepsOriginalAcceptAndUnknown() {
        RewardAcceptLedger ledger = new RewardAcceptLedger();
        ledger.accept(new RewardAcceptLedger.AcceptFact(
                ORIGINAL, "player-7", "badge", "frozen-grant-hash", 0L, true));
        RewardAcceptLedger.Decision recovered = ledger.recover(ORIGINAL);
        assertEquals(ORIGINAL, recovered.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, recovered.outcome());
        assertFalse(recovered.mintedNewRequestId());
        assertFalse(recovered.projectionTakenAsSuccess());

        RewardAcceptLedger unknownLedger = new RewardAcceptLedger();
        unknownLedger.markUnknown(ORIGINAL + "-unk", "server receipt UNKNOWN");
        RewardAcceptLedger.Decision unknownRecover = unknownLedger.recover(ORIGINAL + "-unk");
        assertEquals(ORIGINAL + "-unk", unknownRecover.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, unknownRecover.outcome());
        assertFalse(unknownRecover.projectionTakenAsSuccess());
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, ledger.recover(ORIGINAL).outcome());
    }

    @Test
    void missingRequestIdStaysJavaNullNeverEmptyString() {
        RewardAcceptLedger.Decision omitted = new RewardAcceptLedger().accept(
                new RewardAcceptLedger.AcceptFact(null, "player-7", "badge", "hash", 0L, true));
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, omitted.outcome());
        assertNull(omitted.originalRequestId());

        RewardAcceptLedger.Decision blank = new RewardAcceptLedger().accept(
                new RewardAcceptLedger.AcceptFact("  ", "player-7", "badge", "hash", 0L, true));
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, blank.outcome());
        assertNull(blank.originalRequestId());

        RewardAcceptLedger.Decision recoveredMissing = new RewardAcceptLedger().recover(null);
        assertNull(recoveredMissing.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, recoveredMissing.outcome());
    }

    @Test
    void recoverOfNeverSeenIdDoesNotOccupyLedgerThenAcceptApplies() {
        RewardAcceptLedger ledger = new RewardAcceptLedger();
        RewardAcceptLedger.Decision marked = ledger.markUnknown("c03-rew-never-seen", "server receipt UNKNOWN");
        assertEquals("c03-rew-never-seen", marked.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, marked.outcome());
        assertFalse(marked.mintedNewRequestId());
        assertNull(ledger.existing("c03-rew-never-seen"));

        RewardAcceptLedger.Decision recovered = ledger.recover("c03-rew-never-seen");
        assertEquals("c03-rew-never-seen", recovered.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, recovered.outcome());
        assertFalse(recovered.mintedNewRequestId());
        assertNull(ledger.existing("c03-rew-never-seen"));

        RewardAcceptLedger.Decision firstWrite = ledger.accept(new RewardAcceptLedger.AcceptFact(
                "c03-rew-never-seen", "player-7", "badge", "frozen-grant-hash", 0L, true));
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, firstWrite.outcome());
        assertEquals("c03-rew-never-seen", firstWrite.originalRequestId());
        assertFalse(firstWrite.secondEffect());
        assertEquals(RewardAcceptLedger.APPLIED, ledger.existing("c03-rew-never-seen").status());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyLedgerThenAcceptApplies() {
        RewardAcceptLedger ledger = new RewardAcceptLedger();
        RewardAcceptLedger.Decision blank = ledger.recoverOriginal(null);
        assertNull(blank.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, blank.outcome());
        assertFalse(blank.mintedNewRequestId());
        assertNull(ledger.existing(null));

        RewardAcceptLedger.Decision recovered = ledger.recoverOriginal("c03-rew-recover-never-seen");
        assertEquals("c03-rew-recover-never-seen", recovered.originalRequestId());
        assertEquals(RewardAcceptLedger.Outcome.UNKNOWN, recovered.outcome());
        assertFalse(recovered.mintedNewRequestId());
        assertNull(ledger.existing("c03-rew-recover-never-seen"));

        RewardAcceptLedger.Decision firstWrite = ledger.accept(new RewardAcceptLedger.AcceptFact(
                "c03-rew-recover-never-seen", "player-7", "badge", "frozen-grant-hash", 0L, true));
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED, firstWrite.outcome());
        assertEquals("c03-rew-recover-never-seen", firstWrite.originalRequestId());
        assertFalse(firstWrite.secondEffect());
        assertEquals(RewardAcceptLedger.APPLIED, ledger.existing("c03-rew-recover-never-seen").status());
        assertEquals(RewardAcceptLedger.Outcome.ACCEPTED,
                ledger.recoverOriginal("c03-rew-recover-never-seen").outcome());
    }
}
