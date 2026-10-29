package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.AFTER;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.BEFORE;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.EXPIRES_AT;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.JSON;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.ctx;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.dice;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.enableInventory;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.rules;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentStateDurationAdapterTest {

    @Test
    void expiredEquipmentAndStatusDoNotFeedExistingCheckConsumers() throws Exception {
        JourneyRules journey = rules("{\"environment\":{\"enabled\":true}}");
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"medium\","
                + "\"mods\":[{\"when\":{\"op\":\"ITEM_EQUIPPED\",\"itemId\":\"lantern\"},\"value\":4,\"label\":\"提灯\"},"
                + "{\"when\":{\"op\":\"HAS_CONDITION\",\"value\":\"soaked\"},\"value\":4,\"label\":\"湿透\"}]}");
        TopicRouteEngine.State live = state();
        enableInventory(live);
        live.getThemeRuntime().with("inventory").put("lantern", 1);
        live.getEquipped().put("hand", "lantern");
        live.getStatusConditions().add("soaked");
        RpgAdapterSpec spec = new RpgAdapterSpec();
        RpgAdapterSpec.EquipmentDuration lamp = new RpgAdapterSpec.EquipmentDuration();
        lamp.setItemId("lantern");
        lamp.setSlot("hand");
        lamp.setExpiresAtEpochMillis(EXPIRES_AT);
        spec.getEquipmentDurations().add(lamp);
        RpgAdapterSpec.StatusDuration wet = new RpgAdapterSpec.StatusDuration();
        wet.setCondition("soaked");
        wet.setExpiresAtEpochMillis(EXPIRES_AT);
        spec.getStatusDurations().add(wet);

        RpgAdapterPipeline early = new RpgAdapterPipeline(null, BEFORE);
        PlayCheckReceipt on = early.roll(ctx(journey, live.copy(), check, spec, BEFORE, dice(10)));
        assertTrue(on.getSuccess());
        assertEquals(14, on.getTotal().intValue(), "期限未到:装备与状态仍喂现有 mods");

        TopicRouteEngine.State lateState = live.copy();
        RpgAdapterContext late = ctx(journey, lateState, check, spec, AFTER, dice(10));
        late.setClientNowMillis(Long.valueOf(BEFORE.millis()));
        PlayCheckReceipt off = new RpgAdapterPipeline(null, AFTER).roll(late);
        assertFalse(lateState.getEquipped().containsValue("lantern"));
        assertFalse(lateState.getStatusConditions().contains("soaked"));
        assertFalse(off.getSuccess(), "服务器时钟过期后 ITEM_EQUIPPED / HAS_CONDITION 不再加值");
        assertEquals(10, off.getTotal().intValue());
    }

    @Test
    void frozenSpecLeavesEquippedAndConditionsAlone() throws Exception {
        TopicRouteEngine.State state = state();
        state.getEquipped().put("hand", "lantern");
        state.getStatusConditions().add("soaked");
        RpgAdapterContext context = ctx(rules(null), state, JSON.readTree(
                "{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"),
                RpgAdapterSpec.inactive(), AFTER, dice(10));
        new EquipmentStateDurationAdapter(AFTER).apply(context);
        assertEquals("lantern", state.getEquipped().get("hand"));
        assertTrue(state.getStatusConditions().contains("soaked"));
    }

    @Test
    void unusedDurationDoesNotUnequipThenUniqueExpiryClears() throws Exception {
        TopicRouteEngine.State run = state();
        run.getEquipped().put("hand", "lantern");
        run.getStatusConditions().add("soaked");
        RpgAdapterSpec spec = new RpgAdapterSpec();
        RpgAdapterSpec.EquipmentDuration lamp = new RpgAdapterSpec.EquipmentDuration();
        lamp.setItemId("lantern");
        lamp.setSlot("hand");
        lamp.setExpiresAtEpochMillis(EXPIRES_AT);
        spec.getEquipmentDurations().add(lamp);
        RpgAdapterSpec.StatusDuration wet = new RpgAdapterSpec.StatusDuration();
        wet.setCondition("soaked");
        wet.setExpiresAtEpochMillis(EXPIRES_AT);
        spec.getStatusDurations().add(wet);
        RpgAdapterContext early = ctx(rules(null), run, JSON.readTree(
                "{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"), spec, BEFORE, dice(10));
        java.util.Map<String, Object> observed = new EquipmentStateDurationAdapter(BEFORE).apply(early);
        assertEquals(Boolean.TRUE, observed.get("applied"));
        assertEquals("lantern", run.getEquipped().get("hand"), "未到期观察不得卸下");
        assertTrue(run.getStatusConditions().contains("soaked"));

        RpgAdapterContext late = ctx(rules(null), run, JSON.readTree(
                "{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"), spec, AFTER, dice(10));
        java.util.Map<String, Object> first = new EquipmentStateDurationAdapter(AFTER).apply(late);
        assertEquals(Boolean.TRUE, first.get("applied"));
        assertFalse(run.getEquipped().containsValue("lantern"));
        assertFalse(run.getStatusConditions().contains("soaked"));
    }
}
