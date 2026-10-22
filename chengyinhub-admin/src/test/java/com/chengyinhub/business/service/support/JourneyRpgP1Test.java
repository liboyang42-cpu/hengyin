package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.rpg.FixedTopicWeatherProvider;
import com.chengyinhub.business.service.support.rpg.TopicEnvironmentService;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.chengyinhub.business.util.StateKeyRefs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F6 RPG P1 纯计算部分:灰度块校验、ENV_IN、环境解析、交易加值、发布闸收键与公开投影。 */
class JourneyRpgP1Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LEGACY = "{\"schemaVersion\":1,\"checkEnabled\":true,\"modCap\":4}";
    /** F2 head 上同一输入的规范化结果(冻结快照摘要的输入);F6 之后必须逐字相同。 */
    private static final String LEGACY_NORMALIZED = "{\"schemaVersion\":1,\"checkEnabled\":true,"
            + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":10},\"luck\":{\"enabled\":true,\"max\":3,\"init\":3},"
            + "\"tiers\":{\"easy\":{\"dc\":10,\"damage\":1},\"medium\":{\"dc\":13,\"damage\":2},\"hard\":{\"dc\":16,\"damage\":3}},"
            + "\"recovery\":{\"stationEndIfHurt\":2,\"stageRestHpFloor\":6,\"stageRestLuckFloor\":1,\"exhaustRestHp\":5},"
            + "\"fumbleExtraDamage\":1,\"modCap\":4}";

    // ==================== 灰度块 ====================

    @Test
    void legacyRulesNormalizeAndSerializeExactlyAsBefore() throws Exception {
        assertEquals(LEGACY_NORMALIZED, new JourneyRulesValidator().validateAndNormalize(LEGACY));
        JourneyRules rules = JourneyRules.parse(LEGACY);
        assertNull(rules.getRpg());
        assertFalse(JSON.writeValueAsString(rules).contains("rpg"), "冻结快照(POJO 序列化)不多出 rpg 字段");
        assertFalse(JourneyRpg.environmentEnabled(rules) || JourneyRpg.bargainEnabled(rules) || JourneyRpg.momentumEnabled(rules));
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JourneyRpg.seedRun(rules, state);
        assertTrue(state.getVariables().isEmpty() && state.getStatusConditions().isEmpty());
        assertEquals(Boolean.FALSE, JourneyRpg.view(rules, state).get("enabled"));
    }

    @Test
    void rpgBlockNormalizesDefaultsAndSurvivesFreezeRoundTrip() throws Exception {
        String raw = "{\"schemaVersion\":1,\"checkEnabled\":true,\"rpg\":{"
                + "\"environment\":{\"enabled\":true,\"keys\":[\"season\",\"weather\"]},"
                + "\"clocks\":[{\"var\":\"counter.alarm\",\"label\":\"警报\",\"segments\":4,\"kind\":\"danger\"}],"
                + "\"bargain\":{\"enabled\":true},\"momentum\":{\"enabled\":true},"
                + "\"aspects\":[{\"key\":\"night_owl\",\"label\":\"夜猫子\",\"kind\":\"facet\",\"desc\":\"越晚越清醒\"}]}}";
        JourneyRules rules = JourneyRules.parse(raw);
        JsonNode rpg = rules.getRpg();
        assertEquals("[\"weather\",\"season\"]", rpg.path("environment").path("keys").toString(), "按固定顺序输出");
        assertEquals(6, rpg.path("momentum").path("max").asInt());
        assertEquals(2, rpg.path("momentum").path("init").asInt());
        assertEquals(1, rpg.path("momentum").path("gainOnFail").asInt());
        assertEquals(3, rpg.path("momentum").path("maxSpend").asInt());
        assertTrue(rpg.path("clocks").get(0).path("visible").asBoolean());
        // 冻结:POJO 序列化后再 parse 得到同一份 rpg。
        JourneyRules frozen = JourneyRules.parse(JSON.writeValueAsString(rules));
        assertEquals(rpg, frozen.getRpg());
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JourneyRpg.seedRun(rules, state);
        assertEquals(2, state.getVariables().get(JourneyRpg.SYS_MOMENTUM).intValue());
        assertTrue(state.getStatusConditions().contains("night_owl"));
    }

    @Test
    void rpgBlockRejectsBadShapesAndOrphanSwitches() {
        for (String rpg : Arrays.asList(
                "{\"teams\":{}}",                                              // P2 不在范围
                "{\"environment\":{\"enabled\":true,\"keys\":[\"gps\"]}}",     // 只认四个环境键
                "{\"environment\":{\"enabled\":true,\"keys\":[]}}",
                "{\"clocks\":[{\"var\":\"clue.x\",\"label\":\"钟\",\"segments\":4}]}",
                "{\"clocks\":[{\"var\":\"counter.x\",\"label\":\"钟\",\"segments\":13}]}",
                "{\"clocks\":[{\"var\":\"counter.x\",\"label\":\"钟\",\"segments\":4},{\"var\":\"counter.x\",\"label\":\"钟2\",\"segments\":4}]}",
                "{\"momentum\":{\"enabled\":true,\"max\":11}}",
                "{\"momentum\":{\"enabled\":true,\"max\":3,\"init\":4}}",
                "{\"momentum\":{\"enabled\":true,\"reward\":1}}",
                "{\"aspects\":[{\"key\":\"Bad\",\"label\":\"x\"}]}",
                "{\"aspects\":[{\"key\":\"a\",\"label\":\"x\",\"kind\":\"curse\"}]}",
                "{\"aspects\":[{\"key\":\"a\"}]}")) {
            assertThrows(IllegalArgumentException.class, () -> new JourneyRulesValidator()
                    .validateAndNormalize("{\"schemaVersion\":1,\"checkEnabled\":true,\"rpg\":" + rpg + "}"), rpg);
        }
        // 交易 / 动量挂在检定上:只开了 stateEnabled 不许开。
        assertThrows(IllegalArgumentException.class, () -> new JourneyRulesValidator().validateAndNormalize(
                "{\"schemaVersion\":1,\"stateEnabled\":true,\"rpg\":{\"momentum\":{\"enabled\":true}}}"));
        assertThrows(IllegalArgumentException.class, () -> new JourneyRulesValidator().validateAndNormalize(
                "{\"schemaVersion\":1,\"rpg\":{\"aspects\":[{\"key\":\"a\",\"label\":\"x\"}]}}"));
    }

    // ==================== T07 ENV_IN ====================

    @Test
    void envInValidatesShapeAndEvaluatesUnknownAsFailOpen() throws Exception {
        JsonNode ok = JSON.readTree("{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[\"rain\",\"unknown\"]}");
        StoryVocabulary.validateCondition(ok, "c", null, (p, c) -> { });
        for (String bad : Arrays.asList(
                "{\"op\":\"ENV_IN\",\"key\":\"gps\",\"values\":[\"x\"]}",
                "{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[]}",
                "{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[\"Rain\"]}",
                "{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[\"rain\"],\"lat\":31.2}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> StoryVocabulary.validateCondition(JSON.readTree(bad), "c", null, (p, c) -> { }), bad);
        }
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JsonNode rainOnly = JSON.readTree("[{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[\"rain\"]}]");
        assertFalse(engine.conditionsPass(rainOnly, state), "没快照 = unknown");
        assertTrue(engine.conditionsPass(JSON.readTree("[" + ok + "]"), state), "作者可以显式写 unknown");
        state.getEnvironment().put("weather", "rain");
        assertTrue(engine.conditionsPass(rainOnly, state));
        assertTrue(engine.conditionsPass(JSON.readTree(
                "[{\"op\":\"NOT\",\"condition\":{\"op\":\"ENV_IN\",\"key\":\"daypart\",\"values\":[\"night\"]}}]"), state),
                "能与 F5 组合条件嵌套");
        // 环境快照随 state 序列化 / 复制;空快照不出现在 state_json。
        assertFalse(JSON.writeValueAsString(new TopicRouteEngine.State()).contains("environment"));
        TopicRouteEngine.State read = JSON.readValue(JSON.writeValueAsString(state), TopicRouteEngine.State.class);
        assertEquals("rain", read.getEnvironment().get("weather"));
        assertEquals("rain", state.copy().getEnvironment().get("weather"));
        assertTrue(StateKeyRefs.collect("{\"requires\":[" + ok + "]}").isEmpty(), "环境键不进键注册表");
    }

    // ==================== T06 环境解析 ====================

    @Test
    void environmentServiceUsesServerClockCachesWeatherAndRejectsGarbage() {
        Clock night = Clock.fixed(Instant.parse("2026-12-26T15:30:00Z"), ZoneOffset.UTC);   // 北京 23:30 周六 冬
        AtomicInteger calls = new AtomicInteger();
        TopicEnvironmentService service = new TopicEnvironmentService(city -> {
            calls.incrementAndGet();
            return "acid_rain";
        }, night);
        JourneyRules rules = JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,\"rpg\":{\"environment\":{\"enabled\":true}}}");
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setAddressName("杭州");
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        service.refresh(topic, rules, state);
        service.refresh(topic, rules, state);
        assertEquals(1, calls.get(), "同城 30 分钟内只问一次");
        Map<String, String> env = state.getEnvironment();
        assertEquals("unknown", env.get("weather"), "超出取值表 = unknown");
        assertEquals("night", env.get("daypart"));
        assertEquals("weekend", env.get("weekpart"));
        assertEquals("winter", env.get("season"));
        // 主题没写城市:不问天气源。
        com.chengyinhub.business.domain.CmsTopic noCity = new com.chengyinhub.business.domain.CmsTopic();
        TopicRouteEngine.State other = new TopicRouteEngine.State();
        service.refresh(noCity, rules, other);
        assertEquals("unknown", other.getEnvironment().get("weather"));
        assertEquals(1, calls.get());
        // 默认天气源:留空 = 未知,配置固定值 = 固定值。
        assertNull(new FixedTopicWeatherProvider("").currentWeather("杭州"));
        assertEquals("rain", new FixedTopicWeatherProvider(" rain ").currentWeather("杭州"));
        // 开关关着:一个字都不写。
        TopicRouteEngine.State off = new TopicRouteEngine.State();
        service.refresh(topic, JourneyRules.parse(LEGACY), off);
        assertTrue(off.getEnvironment().isEmpty());
    }

    // ==================== T09 交易加值 / 校验 / 投影 ====================

    @Test
    void bargainBonusZeroKeepsLegacyRollAndPositiveBonusBypassesModCap() throws Exception {
        JourneyRules rules = JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,\"modCap\":1}");
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"c\",\"tier\":\"medium\","
                + "\"mods\":[{\"when\":null,\"value\":3,\"label\":\"x\"}]}");
        PlayCheckReceipt legacy = GameplayCheckEngine.roll(check, rules, new TopicRouteEngine.State(), dice(10), false);
        PlayCheckReceipt zero = GameplayCheckEngine.roll(check, rules, new TopicRouteEngine.State(), dice(10), false, 0);
        assertEquals(JSON.writeValueAsString(legacy), JSON.writeValueAsString(zero));
        assertFalse(zero.getBreakdown().containsKey("bargain"));
        PlayCheckReceipt deal = GameplayCheckEngine.roll(check, rules, new TopicRouteEngine.State(), dice(10), false, 2);
        assertEquals(13, deal.getTotal().intValue(), "10 + 情境修正封顶 1 + 交易 2");
        assertTrue(deal.getSuccess());
        assertEquals(2, ((Number) deal.getBreakdown().get("bargain")).intValue());
    }

    @Test
    void bargainConfigValidatesCostsOnlyAndProjectsLabelWithoutEffects() {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        String ok = block("{\"label\":\"惊动了巡夜人\",\"bonus\":2,\"effects\":["
                + "{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":1,\"max\":4},"
                + "{\"op\":\"ADD_CONDITION\",\"value\":\"noticed\"}]}");
        String normalized = validator.validateAndNormalize(ok);
        String safe = AdvancedGamePublicProjection.safeConfigJson(normalized);
        assertTrue(safe.contains("\"bargain\":{\"label\":\"惊动了巡夜人\",\"bonus\":2}"), safe);
        assertFalse(safe.contains("TICK_CLOCK"), "代价效果不下发");
        assertTrue(StateKeyRefs.collect(normalized).contains("counter.alarm"), "代价写的键要进发布闸");
        for (String bad : Arrays.asList(
                "{\"label\":\"x\",\"bonus\":4,\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.a\"}]}",
                "{\"label\":\"\",\"bonus\":1,\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.a\"}]}",
                "{\"label\":\"x\",\"bonus\":1,\"effects\":[]}",
                "{\"label\":\"x\",\"bonus\":1,\"effects\":[{\"op\":\"GRANT_XP\",\"value\":10}]}",
                "{\"label\":\"x\",\"bonus\":1,\"effects\":[{\"op\":\"ADD_ITEM\",\"itemId\":\"coin\",\"quantity\":1}]}",
                "{\"label\":\"x\",\"bonus\":1,\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1}]}",
                "{\"label\":\"x\",\"bonus\":1,\"price\":5,\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.a\"}]}")) {
            assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(block(bad)), bad);
        }
        // sys.momentum 只读:条件里能读,效果里不能写。
        validator.validateAndNormalize("{\"schemaVersion\":1,\"requires\":[{\"var\":\"sys.momentum\",\"op\":\"GTE\",\"value\":2}]}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"INC\",\"var\":\"sys.momentum\",\"value\":1}]}"));
    }

    private static String block(String bargain) {
        return "{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"medium\",\"bargain\":" + bargain + "}}";
    }

    private static java.util.function.IntSupplier dice(Integer... values) {
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>(Arrays.asList(values));
        return queue::removeFirst;
    }
}
