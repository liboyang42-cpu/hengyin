package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.BEFORE;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.JSON;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.REQUEST;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.ctx;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.dice;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.enableInventory;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.rules;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceSourceAdapterTest {

    @Test
    void originalAddItemAndGrantXpAreIdempotentOnSameRequestId() throws Exception {
        JourneyRules journey = rules(null);
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}");
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setResources(new RpgAdapterSpec.Resources());
        spec.getResources().setSuccessEffects(JSON.readTree("["
                + "{\"op\":\"ADD_ITEM\",\"itemId\":\"clue_page\",\"quantity\":1},"
                + "{\"op\":\"GRANT_XP\",\"value\":5,\"id\":\"page\"}]"));
        TopicRouteEngine.State run = state();
        enableInventory(run);
        RpgAdapterContext context = ctx(journey, run, check, spec, BEFORE, dice(12));
        context.setWriteResources(true);
        RpgAdapterPipeline pipeline = new RpgAdapterPipeline(null, BEFORE);
        PlayCheckReceipt first = pipeline.roll(context);
        assertTrue(first.getSuccess());
        assertEquals(REQUEST, first.getBreakdown().get("requestId"));
        String source = "story:1:clue_page";
        assertTrue(run.getThemeRuntime().path("sources").path(source).asBoolean());
        assertEquals(1, run.getThemeRuntime().path("pending").path(source).path("quantity").asInt());
        assertEquals(1, run.getStoryGrants().size(), "GRANT_XP 走原 storyGrants,不是第二钱包");

        PlayCheckReceipt replay = pipeline.roll(context);
        assertEquals(1, run.getThemeRuntime().path("pending").size(), "同一 originalRequestId 不二发物资");
        assertEquals(1, run.getStoryGrants().size());
        assertEquals(REQUEST, replay.getBreakdown().get("requestId"));
        assertEquals(0, ((Number) ((java.util.Map<?, ?>) replay.getBreakdown().get("resources")).get("sourcesDelta")).intValue());
        assertEquals(0, ((Number) ((java.util.Map<?, ?>) replay.getBreakdown().get("resources")).get("grantsDelta")).intValue());
    }

    @Test
    void frozenSpecDoesNotInitializeInventoryOrWriteGrants() throws Exception {
        TopicRouteEngine.State state = state();
        RpgAdapterContext context = ctx(rules(null), state,
                JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"),
                RpgAdapterSpec.inactive(), BEFORE, dice(12));
        context.setWriteResources(true);
        PlayCheckReceipt roll = new RpgAdapterPipeline(null, BEFORE).roll(context);
        assertTrue(state.getThemeRuntime().isEmpty() || !state.getThemeRuntime().has("pending"));
        assertTrue(state.getStoryGrants().isEmpty());
        assertFalse(roll.getBreakdown().containsKey("resources"));
    }
}
