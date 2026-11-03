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

class RouteCommunityDayConsumerTest {
    @Test
    void takingASegmentWritesAuthorEffects() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        RouteCommunityDayConsumer route = new RouteCommunityDayConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode take = choice(MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY,
                RouteCommunityDayConsumer.ACTION_TAKE_SEGMENT);
        take.put("segmentId", "market");
        session.setPlayerChoice(take);
        session.setOriginalRequestId("route-1");
        Map<String, Object> walked = route.apply(session);
        assertEquals("market", walked.get("segmentId"));
        assertEquals(1, session.getState().getVariables().get("clue.page").intValue());
    }

    @Test
    void communityDayUsesServerWeekpartNotClientCalendar() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        RouteCommunityDayConsumer route = new RouteCommunityDayConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode take = choice(MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY,
                RouteCommunityDayConsumer.ACTION_TAKE_SEGMENT);
        take.put("segmentId", "square");
        session.setPlayerChoice(take);
        session.setOriginalRequestId("route-weekday");
        session.getState().getEnvironment().put(JourneyRpg.ENV_WEEKPART, "weekday");
        IllegalStateException closed = assertThrows(IllegalStateException.class, () -> route.apply(session));
        assertTrue(closed.getMessage().contains("社区日未到"));

        session.setOriginalRequestId("route-weekend");
        session.getState().getEnvironment().put(JourneyRpg.ENV_WEEKPART, "weekend");
        Map<String, Object> opened = route.apply(session);
        assertEquals(Boolean.TRUE, opened.get("communityDay"));
        assertEquals("weekend", opened.get("weekpart"));
        assertEquals(1, session.getState().getVariables().get("counter.alarm").intValue());
    }

    @Test
    void unknownWeekpartDoesNotCountAsCommunityDay() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        RouteCommunityDayConsumer route = new RouteCommunityDayConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode take = choice(MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY,
                RouteCommunityDayConsumer.ACTION_TAKE_SEGMENT);
        take.put("segmentId", "square");
        session.setPlayerChoice(take);
        session.getState().getEnvironment().put(JourneyRpg.ENV_WEEKPART, JourneyRpg.ENV_UNKNOWN);
        assertThrows(IllegalStateException.class, () -> route.apply(session));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueTakeSegmentApplies() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        RouteCommunityDayConsumer route = new RouteCommunityDayConsumer(binder);
        MechanismRequestIdBinder.Play unseen = play(binder);
        unseen.setOriginalRequestId("c03-route-never");
        unseen.setOriginalAction(null);
        Map<String, Object> recovered = route.recoverOriginal(unseen);
        assertEquals("c03-route-never", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-route-never"));
        ObjectNode take = choice(MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY,
                RouteCommunityDayConsumer.ACTION_TAKE_SEGMENT);
        take.put("segmentId", "market");
        unseen.setPlayerChoice(take);
        Map<String, Object> first = route.apply(unseen);
        assertEquals("c03-route-never", first.get("requestId"));
        assertEquals("market", first.get("segmentId"));
        assertEquals(Boolean.FALSE, first.get("replay"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-route-never").getStatus());
        Map<String, Object> replay = route.apply(unseen);
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c03-route-never", replay.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-route-never").getStatus());
        assertEquals(1, unseen.getState().getVariables().get("clue.page").intValue());
    }
}
