package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.BEFORE;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.JSON;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.ctx;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.dice;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.enableInventory;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.rules;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C12 isolation: account/request switch must not rewrite the original
 * requestId; in-process restart requery is replay of the original source key;
 * missing requestId stays Java {@code null}, never {@code ""} and never writes
 * {@code growth-reset:} empty key.
 */
class C02AccountSwitchRestartIsolationTest {

    private static RpgAdapterSpec growthResetSpec() {
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setGrowth(new RpgAdapterSpec.Growth());
        spec.getGrowth().setResetOnRecovery(true);
        spec.getGrowth().getResetKeys().add("counter.temp_buff");
        return spec;
    }

    private static RpgAdapterContext recoveryContext(TopicRouteEngine.State run, String requestId, long memberId) {
        JourneyRules journey = rules(null);
        RpgAdapterContext context = ctx(journey, run, null, growthResetSpec(), BEFORE, dice(12));
        context.setRecoveryKind("stage-rest");
        context.setMemberId(Long.valueOf(memberId));
        context.setOriginalRequestId(requestId);
        run.getVariables().put("sys.hp", Integer.valueOf(2));
        run.getVariables().put("counter.temp_buff", Integer.valueOf(7));
        return context;
    }

    @Test
    void otherRequestDoesNotRewriteOriginalGrowthResetSource() {
        TopicRouteEngine.State run = state();
        enableInventory(run);
        CharacterGrowthSourceAdapter adapter = new CharacterGrowthSourceAdapter();
        RpgAdapterContext original = recoveryContext(run, "c02-growth-acct-1", 7L);
        Map<String, Object> first = adapter.resetIfRecovery(original);
        assertEquals(Boolean.TRUE, first.get("applied"));
        assertEquals("c02-growth-acct-1", first.get("requestId"));
        assertTrue(run.getThemeRuntime().path("sources").path("growth-reset:c02-growth-acct-1").asBoolean());

        RpgAdapterContext switched = recoveryContext(run, "c02-growth-acct-2", 9L);
        Map<String, Object> other = adapter.resetIfRecovery(switched);
        assertEquals(Boolean.TRUE, other.get("applied"));
        assertEquals("c02-growth-acct-2", other.get("requestId"));
        assertNotEquals(first.get("requestId"), other.get("requestId"));
        assertTrue(run.getThemeRuntime().path("sources").path("growth-reset:c02-growth-acct-1").asBoolean(),
                "换账号/换 request 不得抹掉原 source 键");
        assertEquals("c02-growth-acct-1", original.getOriginalRequestId());
    }

    @Test
    void restartRequeryReplaysOriginalSourceWithoutSecondWrite() {
        TopicRouteEngine.State run = state();
        enableInventory(run);
        CharacterGrowthSourceAdapter first = new CharacterGrowthSourceAdapter();
        RpgAdapterContext original = recoveryContext(run, "c02-growth-restart", 7L);
        Map<String, Object> applied = first.resetIfRecovery(original);
        assertEquals(Boolean.TRUE, applied.get("applied"));

        CharacterGrowthSourceAdapter restarted = new CharacterGrowthSourceAdapter();
        RpgAdapterContext recovered = recoveryContext(run, "c02-growth-restart", 7L);
        run.getVariables().put("counter.temp_buff", Integer.valueOf(3));
        Map<String, Object> replay = restarted.resetIfRecovery(recovered);
        assertEquals(Boolean.FALSE, replay.get("applied"));
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c02-growth-restart", replay.get("requestId"));
        assertEquals(3, run.getVariables().get("counter.temp_buff").intValue(), "重启回读不得二次归零");
        assertEquals(1, run.getThemeRuntime().path("sources").size());
    }

    @Test
    void missingRequestIdStaysJavaNullNeverEmptyGrowthResetKey() throws Exception {
        TopicRouteEngine.State run = state();
        enableInventory(run);
        CharacterGrowthSourceAdapter adapter = new CharacterGrowthSourceAdapter();
        RpgAdapterContext missing = recoveryContext(run, null, 7L);
        Map<String, Object> fact = adapter.resetIfRecovery(missing);
        assertEquals(Boolean.FALSE, fact.get("applied"));
        assertEquals("WAITING_SOURCE", fact.get("status"));
        assertNull(fact.get("requestId"));
        assertNull(missing.getOriginalRequestId());
        assertFalse(run.getThemeRuntime().path("sources").has("growth-reset:"));
        assertEquals(7, run.getVariables().get("counter.temp_buff").intValue(), "缺 requestId 不得写地板/归零");
        assertEquals(2, run.getVariables().get("sys.hp").intValue());

        missing.setOriginalRequestId("  ");
        assertNull(missing.getOriginalRequestId());
        Map<String, Object> blank = adapter.resetIfRecovery(missing);
        assertNull(blank.get("requestId"));
        assertEquals("WAITING_SOURCE", blank.get("status"));
        assertTrue(run.getThemeRuntime().path("sources").isEmpty()
                || !run.getThemeRuntime().path("sources").has("growth-reset:"));

        Map<String, Object> card = adapter.readCard(missing);
        assertNull(card.get("requestId"));
        assertNotEquals("", card.get("requestId"));

        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setResources(new RpgAdapterSpec.Resources());
        spec.getResources().setSuccessEffects(JSON.readTree(
                "[{\"op\":\"ADD_ITEM\",\"itemId\":\"clue_page\",\"quantity\":1}]"));
        RpgAdapterContext resources = ctx(rules(null), run,
                JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"),
                spec, BEFORE, dice(12));
        resources.setWriteResources(true);
        resources.setOriginalRequestId(null);
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setSuccess(Boolean.TRUE);
        resources.setReceipt(receipt);
        Map<String, Object> unwritten = new ResourceSourceAdapter().writeOriginal(resources);
        assertEquals(Boolean.FALSE, unwritten.get("applied"));
        assertEquals("WAITING_SOURCE", unwritten.get("status"));
        assertNull(unwritten.get("requestId"));
        assertFalse(run.getThemeRuntime().path("sources").has("story:1:clue_page"));
    }

    @Test
    void readCardOfNeverSeenIdDoesNotOccupyGrowthResetThenUniqueResetApplies() {
        TopicRouteEngine.State run = state();
        enableInventory(run);
        CharacterGrowthSourceAdapter adapter = new CharacterGrowthSourceAdapter();
        RpgAdapterContext unseen = recoveryContext(run, "c02-growth-never", 7L);
        Map<String, Object> card = adapter.readCard(unseen);
        assertEquals("c02-growth-never", card.get("requestId"));
        assertEquals(Boolean.FALSE, card.get("replay"));
        assertFalse(run.getThemeRuntime().path("sources").has("growth-reset:c02-growth-never"));
        assertEquals(7, run.getVariables().get("counter.temp_buff").intValue(), "未见 id 回读不得归零");
        assertEquals(2, run.getVariables().get("sys.hp").intValue());

        Map<String, Object> first = adapter.resetIfRecovery(unseen);
        assertEquals(Boolean.TRUE, first.get("applied"));
        assertEquals("c02-growth-never", first.get("requestId"));
        assertTrue(run.getThemeRuntime().path("sources").path("growth-reset:c02-growth-never").asBoolean());

        Map<String, Object> replay = adapter.resetIfRecovery(unseen);
        assertEquals(Boolean.FALSE, replay.get("applied"));
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c02-growth-never", replay.get("requestId"));
        assertEquals(1, run.getThemeRuntime().path("sources").size());
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
        unseen.setOriginalRequestId("c02-resource-never");
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setSuccess(Boolean.TRUE);
        unseen.setReceipt(receipt);

        ResourceSourceAdapter adapter = new ResourceSourceAdapter();
        Map<String, Object> empty = adapter.writeOriginal(unseen);
        assertEquals(Boolean.FALSE, empty.get("applied"));
        assertEquals(Boolean.TRUE, empty.get("emptyEffects"));
        assertEquals("c02-resource-never", empty.get("requestId"));
        assertFalse(run.getThemeRuntime().path("sources").has("story:1:clue_page"),
                "空效果观察不得占 story:1:clue_page");
        assertTrue(run.getStoryGrants().isEmpty());

        spec.getResources().setSuccessEffects(JSON.readTree(
                "[{\"op\":\"ADD_ITEM\",\"itemId\":\"clue_page\",\"quantity\":1}]"));
        Map<String, Object> first = adapter.writeOriginal(unseen);
        assertEquals(Boolean.TRUE, first.get("applied"));
        assertEquals("c02-resource-never", first.get("requestId"));
        assertEquals(1, ((Number) first.get("sourcesDelta")).intValue());
        assertTrue(run.getThemeRuntime().path("sources").path("story:1:clue_page").asBoolean());

        Map<String, Object> replay = adapter.writeOriginal(unseen);
        assertEquals(Boolean.TRUE, replay.get("applied"));
        assertEquals("c02-resource-never", replay.get("requestId"));
        assertEquals(0, ((Number) replay.get("sourcesDelta")).intValue(), "同一 originalRequestId 不二发物资");
        assertEquals(1, run.getThemeRuntime().path("sources").size());
    }
}
