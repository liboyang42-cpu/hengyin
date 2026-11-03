package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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

class CharacterGrowthSourceAdapterTest {

    @Test
    void skillPointsAndFeatsProjectFromExistingStateAndCardReplaysOriginalRequest() throws Exception {
        JourneyRules journey = rules(null);
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setGrowth(new RpgAdapterSpec.Growth());
        spec.getGrowth().setSkillPointKey("counter.skill_points");
        spec.getGrowth().getFeatConditions().add("feat_night_owl");
        spec.getGrowth().getFeatConditions().add("feat_missing");
        TopicRouteEngine.State run = state();
        run.getStatusConditions().add("feat_night_owl");
        RpgAdapterContext first = ctx(journey, run,
                JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"),
                spec, BEFORE, dice(12));
        PlayCheckReceipt roll = new RpgAdapterPipeline(null, BEFORE).roll(first);
        Map<?, ?> growth = (Map<?, ?>) roll.getBreakdown().get("growth");
        assertEquals(2, ((Number) growth.get("skillPoints")).intValue());
        assertEquals(1, ((List<?>) growth.get("feats")).size());
        assertEquals("feat_night_owl", ((List<?>) growth.get("feats")).get(0));
        assertEquals(REQUEST, roll.getBreakdown().get("requestId"));

        TopicRouteAction original = new TopicRouteAction();
        original.setActionId(REQUEST);
        original.setReceiptId(REQUEST);
        original.setResponseJson(JSON.writeValueAsString(roll));
        Map<String, Object> live = new LinkedHashMap<String, Object>();
        live.put("requestId", "brand-new-retry");
        live.put("value", Integer.valueOf(99));
        RpgAdapterContext retry = ctx(journey, run.copy(), first.getCheck(), spec, BEFORE, dice(12));
        retry.setOriginalAction(original);
        retry.setLiveCharacterCard(live);
        retry.setOriginalRequestId(REQUEST);
        Map<String, Object> card = new CharacterGrowthSourceAdapter().readCard(retry);
        assertEquals(REQUEST, card.get("requestId"));
        assertEquals(Boolean.TRUE, card.get("replay"));
        assertFalse("brand-new-retry".equals(card.get("requestId")));
    }

    @Test
    void recoveryResetUsesPublishedFloorsAndOriginalSourceKey() {
        JourneyRules journey = rules(null);
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setGrowth(new RpgAdapterSpec.Growth());
        spec.getGrowth().setResetOnRecovery(true);
        spec.getGrowth().getResetKeys().add("counter.temp_buff");
        TopicRouteEngine.State run = state();
        enableInventory(run);
        run.getVariables().put("sys.hp", Integer.valueOf(2));
        run.getVariables().put("sys.luck", Integer.valueOf(0));
        run.getVariables().put("counter.temp_buff", Integer.valueOf(7));
        RpgAdapterContext context = ctx(journey, run, null, spec, BEFORE, dice(12));
        context.setRecoveryKind("stage-rest");
        CharacterGrowthSourceAdapter adapter = new CharacterGrowthSourceAdapter();
        adapter.resetIfRecovery(context);
        assertEquals(6, run.getVariables().get("sys.hp").intValue(), "只套已发布 stageRestHpFloor");
        assertEquals(1, run.getVariables().get("sys.luck").intValue());
        assertEquals(0, run.getVariables().get("counter.temp_buff").intValue());
        assertTrue(run.getThemeRuntime().path("sources").path("growth-reset:" + REQUEST).asBoolean());
        run.getVariables().put("counter.temp_buff", Integer.valueOf(3));
        adapter.resetIfRecovery(context);
        assertEquals(3, run.getVariables().get("counter.temp_buff").intValue(), "同一原请求不二归零");
    }

    @Test
    void readCardOfNeverSeenIdDoesNotOccupyGrowthResetThenUniqueResetApplies() {
        JourneyRules journey = rules(null);
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setGrowth(new RpgAdapterSpec.Growth());
        spec.getGrowth().setResetOnRecovery(true);
        spec.getGrowth().getResetKeys().add("counter.temp_buff");
        TopicRouteEngine.State run = state();
        enableInventory(run);
        run.getVariables().put("sys.hp", Integer.valueOf(2));
        run.getVariables().put("counter.temp_buff", Integer.valueOf(7));
        RpgAdapterContext unseen = ctx(journey, run, null, spec, BEFORE, dice(12));
        unseen.setRecoveryKind("stage-rest");
        unseen.setOriginalRequestId("c02-growth-class");
        CharacterGrowthSourceAdapter adapter = new CharacterGrowthSourceAdapter();
        Map<String, Object> card = adapter.readCard(unseen);
        assertEquals("c02-growth-class", card.get("requestId"));
        assertEquals(Boolean.FALSE, card.get("replay"));
        assertFalse(run.getThemeRuntime().path("sources").has("growth-reset:c02-growth-class"));
        assertEquals(7, run.getVariables().get("counter.temp_buff").intValue(), "未见 id 回读不得归零");
        assertEquals(2, run.getVariables().get("sys.hp").intValue());

        Map<String, Object> first = adapter.resetIfRecovery(unseen);
        assertEquals(Boolean.TRUE, first.get("applied"));
        assertEquals("c02-growth-class", first.get("requestId"));
        assertTrue(run.getThemeRuntime().path("sources").path("growth-reset:c02-growth-class").asBoolean());

        Map<String, Object> replay = adapter.resetIfRecovery(unseen);
        assertEquals(Boolean.FALSE, replay.get("applied"));
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c02-growth-class", replay.get("requestId"));
        assertEquals(1, run.getThemeRuntime().path("sources").size());
    }
}
