package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

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

class PartyFactAdapterTest {

    @Test
    void existingActivityRoleFeedsRoleInWithoutInventingMembers() throws Exception {
        JourneyRules journey = rules(null);
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"medium\","
                + "\"mods\":[{\"when\":{\"op\":\"ROLE_IN\",\"values\":[\"NAVIGATOR\"]},\"value\":4,\"label\":\"领航\"}]}");
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setParty(new RpgAdapterSpec.Party());
        spec.getParty().setRequireRole(true);
        TopicRouteEngine.State withRole = state();
        RpgAdapterContext ok = ctx(journey, withRole, check, spec, BEFORE, dice(10));
        ok.setActorRole(role(MEMBER, "NAVIGATOR"));
        ok.setPartyRoles(Arrays.asList(role(MEMBER, "NAVIGATOR"), role(8L, "OBSERVER")));
        PlayCheckReceipt roll = new RpgAdapterPipeline(null, BEFORE).roll(ok);
        assertEquals("NAVIGATOR", withRole.getActorRoleCode());
        assertTrue(roll.getSuccess());
        assertEquals(14, roll.getTotal().intValue());

        TopicRouteEngine.State missing = state();
        RpgAdapterContext closed = ctx(journey, missing, check, spec, BEFORE, dice(10));
        closed.setPartyRoles(Collections.<com.chengyinhub.business.domain.PlayerActivityRole>emptyList());
        PlayCheckReceipt refused = new RpgAdapterPipeline(null, BEFORE).roll(closed);
        assertNull(missing.getActorRoleCode(), "角色源缺失 fail-closed,不发明 party");
        assertFalse(refused.getSuccess());
        assertEquals(10, refused.getTotal().intValue());
    }

    @Test
    void frozenSpecDoesNotBindActorRole() {
        TopicRouteEngine.State state = state();
        RpgAdapterContext context = ctx(rules(null), state, null, RpgAdapterSpec.inactive(), BEFORE, dice(10));
        context.setActorRole(role(MEMBER, "NAVIGATOR"));
        new PartyFactAdapter().apply(context);
        assertNull(state.getActorRoleCode());
    }
}
