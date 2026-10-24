package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.service.support.rpg.adapter.PartyFactAdapter.Decision;
import com.chengyinhub.business.service.support.rpg.adapter.PartyFactAdapter.Outcome;
import com.chengyinhub.business.service.support.rpg.adapter.PartyFactAdapter.PartyFact;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyFactAdapterTest {

    private final PartyFactAdapter adapter = new PartyFactAdapter();

    @Test
    void missingSourceIsUnknownAndDoesNotInventMembers() {
        PartyFact missing = new PartyFact("req-party-1", Set.of(7L, 8L), false);
        Decision decision = adapter.project(missing);
        assertEquals(Outcome.UNKNOWN, decision.outcome());
        assertEquals("req-party-1", decision.originalRequestId());
        assertTrue(decision.memberIds().isEmpty());
        assertFalse(adapter.memberOf(missing, 7L));
    }

    @Test
    void presentRosterKeepsOriginalRequestId() {
        PartyFact fact = new PartyFact("req-party-2", Set.of(11L, 12L), true);
        Decision decision = adapter.project(fact);
        assertEquals(Outcome.PRESENT, decision.outcome());
        assertEquals("req-party-2", decision.originalRequestId());
        assertTrue(adapter.memberOf(fact, 11L));
        assertFalse(adapter.memberOf(fact, 99L));
    }
}
