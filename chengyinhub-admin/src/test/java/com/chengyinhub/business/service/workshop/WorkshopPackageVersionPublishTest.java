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
}
