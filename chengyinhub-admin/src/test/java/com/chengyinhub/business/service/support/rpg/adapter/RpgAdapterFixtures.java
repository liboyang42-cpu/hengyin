package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.service.support.ThemeRuntimeEngine;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.rpg.TopicEnvironmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.function.IntSupplier;

final class RpgAdapterFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long MEMBER = 7L;
    static final long TOPIC = 10L;
    static final long NODE = 1L;
    static final String REQUEST = "check:1:ask_arong:roll";
    /** 2026-10-22 14:00 北京,与 F6 P1 夹具同一瞬间。 */
    static final Clock BEFORE = Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC);
    static final Clock AFTER = Clock.fixed(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC);
    static final long EXPIRES_AT = Instant.parse("2026-10-22T07:00:00Z").toEpochMilli();

    private RpgAdapterFixtures() {}

    static JourneyRules rules(String rpg) {
        String extra = rpg == null ? "" : ",\"rpg\":" + rpg;
        return JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"attributes\":[{\"key\":\"counter.skill_points\",\"label\":\"技能点\",\"initial\":0,\"min\":0,\"max\":20}],"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":10},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":3},"
                + "\"modCap\":4" + extra + "}");
    }

    static JsonNode rainModCheck() throws Exception {
        return JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"medium\","
                + "\"mods\":[{\"when\":{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[\"rain\"]},"
                + "\"value\":4,\"label\":\"雨幕\"}]}");
    }

    static CmsTopic topic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setAddressName("上海市徐汇区");
        return topic;
    }

    static TopicRouteEngine.State state() {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.setCurrentNodeId(NODE);
        state.getVariables().put("sys.hp", Integer.valueOf(10));
        state.getVariables().put("sys.luck", Integer.valueOf(3));
        state.getVariables().put("counter.skill_points", Integer.valueOf(2));
        return state;
    }

    static void enableInventory(TopicRouteEngine.State state) {
        new ThemeRuntimeEngine().initialize(JSON.createObjectNode().put("capacity", 8), state);
    }

    static TopicEnvironmentService rainService(Clock clock) {
        return new TopicEnvironmentService(city -> "rain", clock);
    }

    static IntSupplier dice(int... values) {
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        for (int value : values) queue.add(Integer.valueOf(value));
        return queue::removeFirst;
    }

    static PlayerActivityRole role(long memberId, String code) {
        PlayerActivityRole role = new PlayerActivityRole();
        role.setMemberId(Long.valueOf(memberId));
        role.setRoleCode(code);
        return role;
    }

    static RpgAdapterContext ctx(JourneyRules rules, TopicRouteEngine.State state, JsonNode check,
                                 RpgAdapterSpec spec, Clock clock, IntSupplier dice) {
        RpgAdapterContext context = new RpgAdapterContext();
        context.setTopic(topic());
        context.setRules(rules);
        context.setState(state);
        context.setCheck(check);
        context.setSpec(spec);
        context.setOriginalRequestId(REQUEST);
        context.setNodeId(Long.valueOf(NODE));
        context.setMemberId(Long.valueOf(MEMBER));
        context.setDice(dice);
        return context;
    }

    static RpgAdapterSpec expirySpec() {
        RpgAdapterSpec spec = new RpgAdapterSpec();
        RpgAdapterSpec.EnvironmentExpiry expiry = new RpgAdapterSpec.EnvironmentExpiry();
        expiry.getKeys().add("weather");
        expiry.setExpiresAtEpochMillis(EXPIRES_AT);
        spec.setEnvironmentExpiry(expiry);
        RpgAdapterSpec.Growth growth = new RpgAdapterSpec.Growth();
        growth.setSkillPointKey("counter.skill_points");
        spec.setGrowth(growth);
        return spec;
    }
}
