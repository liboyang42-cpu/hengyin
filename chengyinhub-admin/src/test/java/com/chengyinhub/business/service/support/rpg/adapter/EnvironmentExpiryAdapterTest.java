package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.GameplayCheckEngine;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.rpg.TopicEnvironmentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.AFTER;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.BEFORE;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.JSON;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.REQUEST;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.ctx;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.dice;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.expirySpec;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.rainModCheck;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.rainService;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.rules;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首个可跑旅程:作者配置环境失效 → 玩家检定用服务器环境事实 → 过期不得成功 →
 * 角色卡回读原 requestId(不是新重试)。
 */
class EnvironmentExpiryAdapterTest {
    private static final String ENV = "{\"environment\":{\"enabled\":true}}";

    @Test
    void authorExpiryUsesServerFactsThenBlocksSuccessAndReplaysOriginalRequestId() throws Exception {
        JourneyRules journey = rules(ENV);
        JsonNode check = rainModCheck();
        TopicRouteEngine.State live = state();
        RpgAdapterPipeline pipeline = new RpgAdapterPipeline(rainService(BEFORE), BEFORE);
        RpgAdapterContext first = ctx(journey, live, check, expirySpec(), BEFORE, dice(10));
        PlayCheckReceipt roll = pipeline.roll(first);
        assertEquals("rain", live.getEnvironment().get("weather"), "检定用主题城市的服务器天气,不是客户端");
        assertEquals("afternoon", live.getEnvironment().get("daypart"));
        assertTrue(roll.getSuccess(), "雨幕修正 4 + 骰 10 ≥ medium 13");
        assertEquals(14, roll.getTotal().intValue());
        assertEquals(REQUEST, roll.getBreakdown().get("requestId"));
        Map<?, ?> card = (Map<?, ?>) roll.getBreakdown().get("characterCard");
        assertEquals(REQUEST, card.get("requestId"));
        assertEquals(2, ((Number) ((Map<?, ?>) ((List<?>) card.get("attributes")).get(0)).get("value")).intValue());

        TopicRouteAction original = new TopicRouteAction();
        original.setActionId(REQUEST);
        original.setReceiptId(REQUEST);
        original.setResponseJson(JSON.writeValueAsString(roll));

        TopicRouteEngine.State expired = live.copy();
        RpgAdapterPipeline late = new RpgAdapterPipeline(rainService(AFTER), AFTER);
        RpgAdapterContext retry = ctx(journey, expired, check, expirySpec(), AFTER, dice(10));
        retry.setClientWeather("rain");
        retry.setClientNowMillis(Long.valueOf(BEFORE.millis()));
        retry.setOriginalAction(original);
        retry.setLiveCharacterCard(mutatedCard(9));
        PlayCheckReceipt blocked = late.roll(retry);
        assertEquals(EnvironmentExpiryAdapter.SENTINEL_EXPIRED, expired.getEnvironment().get("weather"));
        assertFalse(blocked.getSuccess(), "过期后 ENV_IN rain 不得再给成功,假天气与客户端时钟无效");
        assertEquals(10, blocked.getTotal().intValue());
        Map<?, ?> envFact = (Map<?, ?>) blocked.getBreakdown().get("environment");
        assertEquals(Boolean.TRUE, envFact.get("expired"));
        assertEquals("rain", envFact.get("ignoredClientWeather"));
        assertEquals(BEFORE.millis(), ((Number) envFact.get("ignoredClientNowMillis")).longValue());
        Map<?, ?> readback = late.growth().readCard(retry);
        assertEquals(REQUEST, readback.get("requestId"), "回读必须是原请求,不得新开 retry id");
        assertEquals(Boolean.TRUE, readback.get("replay"));
        assertEquals(2, ((Number) ((Map<?, ?>) ((List<?>) readback.get("attributes")).get(0)).get("value")).intValue(),
                "角色卡是原回执快照,不是重试当下的 9");
        assertNotEquals("retry:" + REQUEST, readback.get("requestId"));
    }

    @Test
    void fakeWeatherAndClientClockNeverRefreshSnapshot() {
        AtomicInteger asks = new AtomicInteger();
        TopicEnvironmentService service = new TopicEnvironmentService(city -> {
            asks.incrementAndGet();
            return "clear";
        }, BEFORE);
        EnvironmentExpiryAdapter adapter = new EnvironmentExpiryAdapter(service, BEFORE);
        TopicRouteEngine.State state = state();
        RpgAdapterContext context = ctx(rules(ENV), state, null, expirySpec(), BEFORE, dice(10));
        context.setClientWeather("rain");
        context.setClientNowMillis(Long.valueOf(0L));
        adapter.apply(context);
        assertEquals("clear", state.getEnvironment().get("weather"));
        assertEquals(1, asks.get(), "只问 TopicWeatherProvider,不读客户端天气");
        assertNull(context.getFacts().get("clientWeather"));
    }

    @Test
    void expiredWindowDoesNotFailOpenAsUnknownEvenIfAuthorListedUnknown() throws Exception {
        JourneyRules journey = rules(ENV);
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"medium\","
                + "\"mods\":[{\"when\":{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[\"rain\",\"unknown\"]},"
                + "\"value\":4,\"label\":\"雨或未知\"}]}");
        TopicRouteEngine.State state = state();
        RpgAdapterPipeline pipeline = new RpgAdapterPipeline(rainService(AFTER), AFTER);
        RpgAdapterContext context = ctx(journey, state, check, expirySpec(), AFTER, dice(10));
        PlayCheckReceipt roll = pipeline.roll(context);
        assertEquals(EnvironmentExpiryAdapter.SENTINEL_EXPIRED, state.getEnvironment().get("weather"));
        assertFalse(roll.getSuccess(), "expired 哨兵不能被 unknown fail-open 放行");
        assertEquals(10, roll.getTotal().intValue());
    }

    @Test
    void frozenLegacyRunKeepsF2BreakdownAndDoesNotAskWeather() throws Exception {
        AtomicInteger asks = new AtomicInteger();
        TopicEnvironmentService service = new TopicEnvironmentService(city -> {
            asks.incrementAndGet();
            return "rain";
        }, BEFORE);
        JourneyRules legacy = rules(null);
        JsonNode check = rainModCheck();
        TopicRouteEngine.State state = state();
        PlayCheckReceipt baseline = GameplayCheckEngine.roll(check, legacy, state.copy(), dice(10), false, 0);
        RpgAdapterPipeline pipeline = new RpgAdapterPipeline(service, BEFORE);
        RpgAdapterContext context = ctx(legacy, state, check, RpgAdapterSpec.inactive(), BEFORE, dice(10));
        context.setClientWeather("rain");
        PlayCheckReceipt adapted = pipeline.roll(context);
        assertEquals(JSON.writeValueAsString(baseline.getBreakdown()), JSON.writeValueAsString(adapted.getBreakdown()));
        assertEquals(6, adapted.getBreakdown().size(), "明细字段集合与 F2 一致:die/skill/situational/total/dc/natAuto");
        assertFalse(adapted.getBreakdown().containsKey("environment"));
        assertTrue(state.getEnvironment().isEmpty());
        assertEquals(0, asks.get());
        assertFalse(adapted.getSuccess());
    }

    @Test
    void weatherProviderFailureIsUnknownUntilExpiryThenExpired() throws Exception {
        TopicEnvironmentService down = new TopicEnvironmentService(city -> {
            throw new IllegalStateException("provider down");
        }, BEFORE);
        TopicRouteEngine.State open = state();
        RpgAdapterSpec spec = expirySpec();
        spec.getEnvironmentExpiry().setExpiresAtEpochMillis(AFTER.millis() + 1L);
        new EnvironmentExpiryAdapter(down, BEFORE).apply(ctx(rules(ENV), open, null, spec, BEFORE, dice(10)));
        assertEquals("unknown", open.getEnvironment().get("weather"));
        TopicRouteEngine.State closed = state();
        new EnvironmentExpiryAdapter(down, AFTER).apply(ctx(rules(ENV), closed, null, expirySpec(), AFTER, dice(10)));
        assertEquals(EnvironmentExpiryAdapter.SENTINEL_EXPIRED, closed.getEnvironment().get("weather"));
    }

    @Test
    void inactiveExpiryDoesNotOccupyWeatherThenUniqueApplyWritesServerFact() {
        EnvironmentExpiryAdapter adapter = new EnvironmentExpiryAdapter(rainService(BEFORE), BEFORE);
        TopicRouteEngine.State idle = state();
        Map<String, Object> frozen = adapter.apply(
                ctx(rules(ENV), idle, null, RpgAdapterSpec.inactive(), BEFORE, dice(10)));
        assertEquals(Boolean.FALSE, frozen.get("applied"));
        assertTrue(idle.getEnvironment().isEmpty(), "inactive 观察不得占 weather");

        TopicRouteEngine.State live = state();
        Map<String, Object> first = adapter.apply(ctx(rules(ENV), live, null, expirySpec(), BEFORE, dice(10)));
        assertEquals(Boolean.TRUE, first.get("applied"));
        assertEquals("rain", live.getEnvironment().get("weather"));
        assertEquals("rain", adapter.apply(ctx(rules(ENV), live, null, expirySpec(), BEFORE, dice(10)))
                .get("serverSnapshot") == null ? null
                : live.getEnvironment().get("weather"));
    }

    private static Map<String, Object> mutatedCard(int skillPoints) {
        Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("stateKey", "counter.skill_points");
        row.put("value", Integer.valueOf(skillPoints));
        Map<String, Object> card = new java.util.LinkedHashMap<String, Object>();
        card.put("attributes", java.util.Collections.singletonList(row));
        card.put("requestId", "retry-should-not-win");
        return card;
    }
}
