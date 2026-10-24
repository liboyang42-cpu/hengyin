package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

        java.util.Map<String, Object> replay = pipeline.resources().writeOriginal(context);
        assertEquals(1, run.getThemeRuntime().path("pending").size(), "同一 originalRequestId 不二发物资");
        assertEquals(1, run.getStoryGrants().size());
        assertEquals(REQUEST, first.getBreakdown().get("requestId"));
        assertEquals(0, ((Number) replay.get("sourcesDelta")).intValue());
        assertEquals(0, ((Number) replay.get("grantsDelta")).intValue());
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

    @Test
    void emptyEffectsOfPresentNeverSeenIdDoesNotOccupyStorySourceThenUniqueWriteApplies() throws Exception {
        TopicRouteEngine.State run = state();
        enableInventory(run);
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setResources(new RpgAdapterSpec.Resources());
        spec.getResources().setSuccessEffects(JSON.readTree("[]"));
        RpgAdapterContext unseen = ctx(rules(null), run,
                JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"),
                spec, BEFORE, dice(12));
        unseen.setWriteResources(true);
        unseen.setOriginalRequestId("c02-resource-class");
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setSuccess(Boolean.TRUE);
        unseen.setReceipt(receipt);

        ResourceSourceAdapter adapter = new ResourceSourceAdapter();
        Map<String, Object> empty = adapter.writeOriginal(unseen);
        assertEquals(Boolean.FALSE, empty.get("applied"));
        assertEquals(Boolean.TRUE, empty.get("emptyEffects"));
        assertEquals("c02-resource-class", empty.get("requestId"));
        assertFalse(run.getThemeRuntime().path("sources").has("story:1:clue_page"),
                "空效果观察不得占 story:1:clue_page");
        assertTrue(run.getStoryGrants().isEmpty());

        spec.getResources().setSuccessEffects(JSON.readTree(
                "[{\"op\":\"ADD_ITEM\",\"itemId\":\"clue_page\",\"quantity\":1}]"));
        Map<String, Object> first = adapter.writeOriginal(unseen);
        assertEquals(Boolean.TRUE, first.get("applied"));
        assertEquals("c02-resource-class", first.get("requestId"));
        assertEquals(1, ((Number) first.get("sourcesDelta")).intValue());
        assertTrue(run.getThemeRuntime().path("sources").path("story:1:clue_page").asBoolean());

        Map<String, Object> replay = adapter.writeOriginal(unseen);
        assertEquals(Boolean.TRUE, replay.get("applied"));
        assertEquals("c02-resource-class", replay.get("requestId"));
        assertEquals(0, ((Number) replay.get("sourcesDelta")).intValue(), "同一 originalRequestId 不二发物资");
        assertEquals(1, run.getThemeRuntime().path("sources").size());
    }
}
