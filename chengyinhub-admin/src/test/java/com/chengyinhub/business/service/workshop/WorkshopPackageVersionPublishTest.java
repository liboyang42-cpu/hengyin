package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WorkshopPackageVersionPublishTest {

    private static final String CONTENT = "b".repeat(64);
    private static final String TERMS = "c".repeat(64);

    @Test
    void freezeThenSameRequestIdIsReplayWithoutPublicRegistryWrite() {
        WorkshopPackageVersionPublish publish = new WorkshopPackageVersionPublish();
        WorkshopPackageVersionPublish.FreezeFact fact = new WorkshopPackageVersionPublish.FreezeFact(
                "c10-pub-1", "mod-1", "1.0.0", CONTENT, TERMS);
        WorkshopPackageVersionPublish.Decision first = publish.freeze(fact);
        assertEquals(WorkshopPackageVersionPublish.Outcome.FROZEN, first.outcome());
        assertEquals(CONTENT, first.frozenContentHash());
        assertFalse(first.wrotePublicRegistry());
        WorkshopPackageVersionPublish.Decision second = publish.freeze(fact);
        assertEquals(WorkshopPackageVersionPublish.Outcome.REPLAY, second.outcome());
        assertEquals("c10-pub-1", second.originalRequestId());
        assertFalse(second.wrotePublicRegistry());
        assertEquals(CONTENT, publish.frozen().contentHash());
    }

    @Test
    void mutatingFrozenHashWithoutNewVersionRejects() {
        WorkshopOriginalRequestBinder binder = new WorkshopOriginalRequestBinder();
        WorkshopPackageVersionPublish publish = new WorkshopPackageVersionPublish(binder);
        publish.freeze(new WorkshopPackageVersionPublish.FreezeFact(
                "c10-pub-a", "mod-1", "1.0.0", CONTENT, TERMS));
        WorkshopPackageVersionPublish.Decision mutated = publish.freeze(new WorkshopPackageVersionPublish.FreezeFact(
                "c10-pub-b", "mod-1", "1.0.0", "d".repeat(64), TERMS));
        assertEquals(WorkshopPackageVersionPublish.Outcome.REJECTED, mutated.outcome());
        assertEquals(CONTENT, publish.frozen().contentHash());
    }

    @Test
    void missingRequestIdStaysNull() {
        WorkshopPackageVersionPublish.Decision decision = new WorkshopPackageVersionPublish().freeze(
                new WorkshopPackageVersionPublish.FreezeFact(null, "mod-1", "1.0.0", CONTENT, TERMS));
        assertEquals(WorkshopPackageVersionPublish.Outcome.UNKNOWN, decision.outcome());
        assertNull(decision.originalRequestId());
        assertFalse(decision.grantsPublish());
    }

    @Test
    void recoverOfNeverSeenIdDoesNotOccupyThenUniqueFreezeApplies() {
        WorkshopOriginalRequestBinder binder = new WorkshopOriginalRequestBinder();
        WorkshopPackageVersionPublish publish = new WorkshopPackageVersionPublish(binder);
        WorkshopPackageVersionPublish.Decision blank = publish.recover("  ");
        assertNull(blank.originalRequestId());
        assertEquals(WorkshopPackageVersionPublish.Outcome.UNKNOWN, blank.outcome());
        assertNull(binder.existing("  "));
        WorkshopPackageVersionPublish.Decision recovered = publish.recover("c10-pub-never-c10");
        assertEquals("c10-pub-never-c10", recovered.originalRequestId());
        assertEquals(WorkshopPackageVersionPublish.Outcome.UNKNOWN, recovered.outcome());
        assertFalse(recovered.grantsPublish());
        assertNull(binder.existing("c10-pub-never-c10"));
        assertNull(publish.frozen());
        WorkshopPackageVersionPublish.Decision first = publish.freeze(new WorkshopPackageVersionPublish.FreezeFact(
                "c10-pub-never-c10", "mod-1", "1.0.0", CONTENT, TERMS));
        assertEquals(WorkshopPackageVersionPublish.Outcome.FROZEN, first.outcome());
        assertEquals("c10-pub-never-c10", first.originalRequestId());
        assertEquals(CONTENT, first.frozenContentHash());
        assertFalse(first.wrotePublicRegistry());
        assertEquals(WorkshopOriginalRequestBinder.APPLIED, binder.existing("c10-pub-never-c10").status());
        WorkshopPackageVersionPublish.Decision replay = publish.freeze(new WorkshopPackageVersionPublish.FreezeFact(
                "c10-pub-never-c10", "mod-1", "1.0.0", CONTENT, TERMS));
        assertEquals(WorkshopPackageVersionPublish.Outcome.REPLAY, replay.outcome());
        assertEquals("c10-pub-never-c10", replay.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.APPLIED, binder.existing("c10-pub-never-c10").status());
        assertEquals(CONTENT, publish.frozen().contentHash());
    }
}
