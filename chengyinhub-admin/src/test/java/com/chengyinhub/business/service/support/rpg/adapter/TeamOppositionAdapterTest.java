package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.GameplayCheckEngine;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.BEFORE;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.JSON;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.MEMBER;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.ctx;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.dice;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.role;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.rules;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamOppositionAdapterTest {

    @Test
    void whiteRedPassiveAndOppositionFeedExistingDisadvantageWithoutChangingNat() throws Exception {
        JourneyRules journey = rules(null);
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\","
                + "\"disadvantageIf\":[{\"op\":\"HAS_CONDITION\",\"value\":\"opposed\"}]}");
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setTeamOpposition(new RpgAdapterSpec.TeamOpposition());
        spec.getTeamOpposition().setLane(TeamOppositionAdapter.LANE_RED);
        spec.getTeamOpposition().setOpposeRoleCode("HUNTER");
        spec.getTeamOpposition().setOpposedCondition("opposed");

        TopicRouteEngine.State opposed = state();
        RpgAdapterContext red = ctx(journey, opposed, check, spec, BEFORE, dice(4, 15));
        red.setPartyRoles(Arrays.asList(role(MEMBER, "NAVIGATOR"), role(8L, "HUNTER")));
        PlayCheckReceipt roll = new RpgAdapterPipeline(null, BEFORE).roll(red);
        assertTrue(opposed.getStatusConditions().contains("opposed"));
        assertTrue(roll.isDisadvantage());
        assertEquals(4, roll.getKept().intValue(), "对抗走现有劣势双骰取低,不改 DC");
        Map<?, ?> team = (Map<?, ?>) roll.getBreakdown().get("teamOpposition");
        assertEquals(Boolean.FALSE, team.get("rerollAllowed"), "红检定投影禁止幸运重掷");
        assertEquals(Boolean.TRUE, team.get("natAutoUnchanged"));
        assertEquals(Boolean.TRUE, journey.isNatAuto());

        spec.getTeamOpposition().setLane(TeamOppositionAdapter.LANE_WHITE);
        Map<String, Object> white = new TeamOppositionAdapter().apply(
                ctx(journey, state(), check, spec, BEFORE, dice(10)));
        assertEquals(Boolean.TRUE, white.get("rerollAllowed"), "白检定沿用已有 luck.enabled");

        spec.getTeamOpposition().setLane(TeamOppositionAdapter.LANE_PASSIVE);
        Map<String, Object> passive = new TeamOppositionAdapter().apply(
                ctx(journey, state(), check, spec, BEFORE, dice(10)));
        assertEquals(Boolean.TRUE, passive.get("passive"));
    }

    @Test
    void unapprovedNatRulesStayOnExistingEngine() throws Exception {
        JourneyRules auto = rules(null);
        assertTrue(auto.isNatAuto());
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"hard\"}");
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setTeamOpposition(new RpgAdapterSpec.TeamOpposition());
        spec.getTeamOpposition().setLane(TeamOppositionAdapter.LANE_WHITE);
        PlayCheckReceipt crit = new RpgAdapterPipeline(null, BEFORE).roll(
                ctx(auto, state(), check, spec, BEFORE, dice(20)));
        assertEquals(PlayCheckReceipt.NAT_CRIT, crit.getNat());
        assertTrue(crit.getSuccess());
        assertEquals(Boolean.TRUE, crit.getBreakdown().get("natAuto"));

        JourneyRules off = JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,\"natAuto\":false,\"modCap\":4}");
        TopicRouteEngine.State copy = state();
        PlayCheckReceipt baseline = GameplayCheckEngine.roll(check, off, copy.copy(), dice(20), false, 0);
        PlayCheckReceipt adapted = new RpgAdapterPipeline(null, BEFORE).roll(
                ctx(off, copy, check, RpgAdapterSpec.inactive(), BEFORE, dice(20)));
        assertEquals(JSON.writeValueAsString(baseline), JSON.writeValueAsString(adapted));
        assertNull(adapted.getNat(), "显式 natAuto=false 仍按 total 比 DC,adapter 不得改写");
        assertTrue(adapted.getSuccess(), "20 ≥ hard 16,但不是 CRIT 口径");
    }

    @Test
    void frozenSpecDoesNotAddOpposedCondition() {
        TopicRouteEngine.State state = state();
        RpgAdapterContext context = ctx(rules(null), state, null, RpgAdapterSpec.inactive(), BEFORE, dice(10));
        context.setPartyRoles(Arrays.asList(role(8L, "HUNTER")));
        new TeamOppositionAdapter().apply(context);
        assertFalse(state.getStatusConditions().contains("opposed"));
    }

    @Test
    void unusedOppositionDoesNotOccupyThenUniqueHunterApplies() throws Exception {
        JourneyRules journey = rules(null);
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}");
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setTeamOpposition(new RpgAdapterSpec.TeamOpposition());
        spec.getTeamOpposition().setLane(TeamOppositionAdapter.LANE_RED);
        spec.getTeamOpposition().setOpposeRoleCode("HUNTER");
        spec.getTeamOpposition().setOpposedCondition("opposed");
        TopicRouteEngine.State run = state();
        RpgAdapterContext unseen = ctx(journey, run, check, spec, BEFORE, dice(10));
        unseen.setPartyRoles(Arrays.asList(role(MEMBER, "NAVIGATOR")));
        Map<String, Object> observed = new TeamOppositionAdapter().apply(unseen);
        assertEquals(Boolean.TRUE, observed.get("applied"));
        assertEquals(Boolean.FALSE, observed.get("opposed"));
        assertFalse(run.getStatusConditions().contains("opposed"), "无对抗观察不得占 opposed");

        unseen.setPartyRoles(Arrays.asList(role(MEMBER, "NAVIGATOR"), role(8L, "HUNTER")));
        Map<String, Object> first = new TeamOppositionAdapter().apply(unseen);
        assertEquals(Boolean.TRUE, first.get("opposed"));
        assertEquals("opposed", first.get("opposedCondition"));
        assertTrue(run.getStatusConditions().contains("opposed"));
    }
}
