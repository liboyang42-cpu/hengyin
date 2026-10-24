package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.service.support.JourneyRpg;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.choice;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressClockConsumerTest {
    @Test
    void tickSpendAndAspectUseJourneyRpgNotASecondWallet() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode tick = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_TICK);
        tick.put("var", "counter.alarm");
        session.setPlayerChoice(tick);
        session.setOriginalRequestId("clock-1");
        Map<String, Object> ticked = clocks.apply(session);
        assertEquals(1, ((Number) ticked.get("value")).intValue());
        assertEquals(Boolean.FALSE, ticked.get("filled"));

        ObjectNode spend = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_SPEND);
        spend.put("spend", 1);
        session.setPlayerChoice(spend);
        session.setOriginalRequestId("clock-2");
        int before = JourneyRpg.momentumOf(session.getState());
        Map<String, Object> spent = clocks.apply(session);
        assertEquals(before - 1, ((Number) spent.get("value")).intValue());
        assertEquals(before - 1, JourneyRpg.momentumOf(session.getState()));

        ObjectNode aspect = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_ASPECT);
        aspect.put("key", "noticed");
        session.setPlayerChoice(aspect);
        session.setOriginalRequestId("clock-3");
        Map<String, Object> invoked = clocks.apply(session);
        assertEquals(Boolean.TRUE, invoked.get("active"));
        assertTrue(session.getState().getStatusConditions().contains("noticed"));
        assertTrue(session.getState().getStatusConditions().contains("night_owl"));
    }

    @Test
    void unknownClockVarIsRejected() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode tick = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_TICK);
        tick.put("var", "counter.missing");
        session.setPlayerChoice(tick);
        assertThrows(IllegalArgumentException.class, () -> clocks.apply(session));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueTickApplies() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play unseen = play(binder);
        unseen.setOriginalRequestId("c03-clock-never");
        unseen.setOriginalAction(null);
        Map<String, Object> recovered = clocks.recoverOriginal(unseen);
        assertEquals("c03-clock-never", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-clock-never"));
        ObjectNode tick = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_TICK);
        tick.put("var", "counter.alarm");
        unseen.setPlayerChoice(tick);
        Map<String, Object> first = clocks.apply(unseen);
        assertEquals("c03-clock-never", first.get("requestId"));
        assertEquals(1, ((Number) first.get("value")).intValue());
        assertEquals(Boolean.FALSE, first.get("replay"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-clock-never").getStatus());
        Map<String, Object> replay = clocks.apply(unseen);
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c03-clock-never", replay.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-clock-never").getStatus());
    }
}
