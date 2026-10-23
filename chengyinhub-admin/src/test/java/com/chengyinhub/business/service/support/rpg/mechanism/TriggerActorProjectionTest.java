package com.chengyinhub.business.service.support.rpg.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TriggerActorProjectionTest {

    @Test
    void explicitChoiceBindsActor() {
        TriggerActorProjection.View view = new TriggerActorProjection().project(
                new TriggerActorProjection.ActorFact("c03-act-1", "actor-2", true, false));
        assertEquals(TriggerActorProjection.Outcome.BOUND, view.outcome());
        assertEquals("c03-act-1", view.originalRequestId());
        assertEquals("actor-2", view.actorId());
        assertFalse(view.defaultFired());
    }

    @Test
    void defaultFireIsRejected() {
        TriggerActorProjection.View view = new TriggerActorProjection().project(
                new TriggerActorProjection.ActorFact("c03-act-2", "actor-2", true, true));
        assertEquals(TriggerActorProjection.Outcome.REJECTED, view.outcome());
        assertEquals("c03-act-2", view.originalRequestId());
    }

    @Test
    void missingRequestIdStaysNull() {
        TriggerActorProjection.View view = new TriggerActorProjection().project(
                new TriggerActorProjection.ActorFact(null, "actor-2", true, false));
        assertEquals(TriggerActorProjection.Outcome.UNKNOWN, view.outcome());
        assertNull(view.originalRequestId());
    }
}
