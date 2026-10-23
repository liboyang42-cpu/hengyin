package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayPredictResult;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayPredictMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「客户端发什么 / 服务端认什么」的跨语言合同。
 *
 * <p>★ 为什么必须跨语言共读一份夹具:字段名对不上时服务端**不报错** ——
 * 读不到 value 就按 0 判,玩家永远通不过。小程序那边自己写一套断言、后端这边再写一套,
 * 两边都会对着各自的假设绿。所以这里读的是小程序仓里那份
 * {@code tests/fixtures/playkit-server-actions.json},payload 一个字都不改地丢进 act()。
 *
 * <p>这条用例只证明「服务端认得这些字段」,不证明客户端真的发了 ——
 * 后者由同一份夹具在 playkit-server-action-wiring.test.js 里钉住。
 */
class PlaykitActionPayloadContractTest {

    private static final long NOW = 1_775_000_000_000L;       // 2026-04-01 前后,固定时钟
    private static final long MEMBER_ID = 7L;
    private static final long NODE_ID = 29L;
    private static final long SESSION_ID = 51L;
    private static final long TOPIC_ID = 10L; // 真实局必属某主题；C包新动作按主题自玩资格锁读

    private final ObjectMapper mapper = new ObjectMapper();
    private GameRuntimeSessionMapper sessionMapper;
    private PlayAttemptLogMapper attemptMapper;
    private PlayPredictMapper predictMapper;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        predictMapper = mock(PlayPredictMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "predictMapper", predictMapper);
        // 扫码要拿节点上的核销码来比对 —— 码绑在路线层,不在玩法配置里
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        com.chengyinhub.business.domain.CmsTopicNode node = new com.chengyinhub.business.domain.CmsTopicNode();
        node.setId(NODE_ID);
        node.setCheckinCode("CY-NODE-29");
        node.setTopicId(TOPIC_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        // 抽卡按权重抽:固定成 0 号,免得用例随机红
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        when(predictMapper.selectResult(any(), any())).thenReturn(new PlayPredictResult());
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @Test
    void everyClientPayloadIsUnderstoodByTheServer() throws Exception {
        JsonNode fixture = mapper.readTree(new String(Files.readAllBytes(Paths.get("..",
                "chengyinhub-xcx", "tests", "fixtures", "playkit-server-actions.json")),
                StandardCharsets.UTF_8));
        int checked = 0;
        for (JsonNode one : fixture.path("cases")) {
            String type = one.path("type").asText();
            String action = one.path("action").asText();
            String label = one.path("game").asText() + " / " + type + ":" + action;
            /* 同一个动作可能有好几种形态(问答三种模式共用 SUBMIT_QA),
               夹具用 scenario 区分;没写 scenario 的按 type:action 找。 */
            String key = one.path("scenario").asText("");
            if (key.isEmpty()) key = type + ":" + action;
            Setup setup = SETUPS.get(key);
            /* 猜图(pricepair)故意没有服务端场景:后端那一段是**比价**
               (两件商品比价格、连对算 streak),而原型那一屏是「四张图挑正确的那张」,
               两者不是同一个玩法。接线之前得先定哪一个是猜图,不能靠这条用例糊过去。 */
            if (setup == null) continue;
            run(label, setup, one.path("serverAction").asText(), one.path("payload"));
            checked++;
        }
        assertEquals(SETUPS.size(), checked, "夹具里少了场景,或者 SETUPS 里有没被用上的死条目");
    }

    /** 一条动作要先摆好的场地:配置 + 初始状态 + 跑完之后该看见什么。 */
    private interface Setup {
        String config();
        String state();
        void verify(Map<String, Object> view);
    }

    private static final Map<String, Setup> SETUPS = new LinkedHashMap<>();

    static {
        SETUPS.put("estimate:submit", setup(
                "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"question\":\"多少年\","
                        + "\"answer\":42,\"min\":0,\"max\":400,\"tolerance\":0,\"maxAttempts\":3,\"xp\":10}}",
                "{}",
                (view) -> assertEquals("HIT", kit(view, "estimate").get("tier"),
                        "估 42、答案也是 42,该判 HIT —— 判不中多半是 value 这个字段名没对上")));

        SETUPS.put("hidden:submit", setup(
                "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,\"imageUrl\":\"https://a/b.png\","
                        + "\"hotspots\":[{\"id\":\"h1\",\"x\":0.3,\"y\":0.4,\"r\":0.1}],\"xp\":10}}",
                "{}",
                (view) -> assertEquals(1, ((java.util.List<?>) kit(view, "hiddenObject").get("foundIds")).size(),
                        "点中了却没记进 foundIds")));

        SETUPS.put("branch:choose", setup(
                "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
                        + "{\"id\":\"start\",\"terminal\":false,\"options\":[{\"id\":\"go\",\"label\":\"继续\","
                        + "\"nextStepId\":\"end\"}]},"
                        + "{\"id\":\"end\",\"terminal\":true,\"outcomeCode\":\"DONE\",\"options\":[]}]}}",
                "{\"branch\":{\"currentStepId\":\"start\",\"visitedStepIds\":[\"start\"]}}",
                (view) -> assertEquals("end",
                        ((Map<?, ?>) view.get("branch")).get("currentStepId"), "分支没推进")));

        SETUPS.put("random:draw", setup(
                "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":["
                        + "{\"id\":\"a\",\"label\":\"再来一杯\",\"weight\":1}]}}",
                "{\"draws\":[]}",
                (view) -> assertEquals(1, ((java.util.List<?>) view.get("draws")).size(), "没抽出东西")));

        SETUPS.put("coinflip:flip", setup(
                "{\"schemaVersion\":1,\"coinFlip\":{\"enabled\":true,\"xp\":10,"
                        + "\"heads\":{\"label\":\"正面\",\"action\":\"这杯店家请\"},"
                        + "\"tails\":{\"label\":\"反面\",\"action\":\"这杯你请\"}}}",
                "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "coinFlip").get("flipped"), "没抛")));

        SETUPS.put("diceroll:roll", setup(
                "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"diceCount\":1,\"xp\":10,"
                        + "\"faces\":[\"一\",\"二\",\"三\",\"四\",\"五\",\"六\"]}}",
                "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "diceRoll").get("rolled"), "没掷")));

        String reaction = "{\"schemaVersion\":1,\"reaction\":{\"enabled\":true,\"rounds\":3,"
                + "\"goalMs\":320,\"xp\":10}}";
        SETUPS.put("reaction:start", setup(reaction, "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "reaction").get("started"), "没开表")));
        SETUPS.put("reaction:submit", setup(reaction, started("reaction", 12_000L),
                (view) -> assertEquals(Boolean.TRUE, kit(view, "reaction").get("submitted"), "没收下成绩")));

        String shake = "{\"schemaVersion\":1,\"ballShake\":{\"enabled\":true,\"goal\":30,"
                + "\"timed\":false,\"xp\":10}}";
        SETUPS.put("ballshake:start", setup(shake, "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "ballShake").get("started"), "没开表")));
        SETUPS.put("ballshake:submit", setup(shake, started("ballShake", 12_000L),
                (view) -> assertEquals(Boolean.TRUE, kit(view, "ballShake").get("submitted"), "没收下撞击数")));

        String quiet = "{\"schemaVersion\":1,\"quietHold\":{\"enabled\":true,\"seconds\":15,\"xp\":10}}";
        SETUPS.put("quiethold:start", setup(quiet, "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "quietHold").get("started"), "没开表")));
        /* ★ 秒 → 毫秒那一条就在这儿把关:客户端报 15(秒),翻译成 15000(毫秒)。
           漏翻的话服务端拿 15 毫秒跟 15 秒的目标比,判定恒为没坚持住,而且不报错。 */
        SETUPS.put("quiethold:submit", setup(quiet, started("quietHold", 20_000L),
                (view) -> assertEquals(Boolean.TRUE, kit(view, "quietHold").get("passed"),
                        "安静 15 秒该算过 —— 没过多半是秒/毫秒没换算")));

        /* 喊一嗓子(现场感契约 §4):组件自己攒的就是毫秒,这一屏没有秒→毫秒换算 ——
           要防的是字段名漂移:heldMs 写成别的,服务端按 0 判,永远差那么几秒。 */
        String shout = "{\"schemaVersion\":1,\"shout\":{\"enabled\":true,\"seconds\":5,\"xp\":10}}";
        SETUPS.put("shout:start", setup(shout, "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "shout").get("started"), "没开表")));
        SETUPS.put("shout:submit", setup(shout, started("shout", 8_000L),
                (view) -> assertEquals(Boolean.TRUE, kit(view, "shout").get("passed"),
                        "连续喊满 5.2 秒、目标 5 秒该算过 —— 没过多半是 heldMs 没对上")));

        String countdown = "{\"schemaVersion\":1,\"countdown\":{\"enabled\":true,\"seconds\":30,"
                + "\"doneText\":\"时间到。\",\"xp\":10}}";
        SETUPS.put("countdown:start", setup(countdown, "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "countdown").get("started"), "没开表")));
        SETUPS.put("countdown:finish", setup(countdown, started("countdown", 40_000L),
                (view) -> assertEquals(Boolean.TRUE, kit(view, "countdown").get("submitted"), "没到点")));

        String qaType = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\","
                + "\"title\":\"这座桥建于哪一年?\",\"answerText\":\"1908\",\"maxTries\":3,\"xp\":10}}";
        SETUPS.put("qa:type", setup(qaType, "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "qa").get("passed"),
                        "答对了该判过 —— 判不过多半是 input 这个字段名没对上")));

        SETUPS.put("scan:scanned", setup(
                "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\","
                        + "\"reply\":\"欢迎来到这家店\",\"xp\":10}}",
                "{}",
                (view) -> {
                    Map<String, Object> scan = kit(view, "scan");
                    assertEquals(Boolean.TRUE, scan.get("scanned"), "没记成扫过");
                    // 扫完之后回复才下发 —— 扫之前给,这一站的「到店」就没有意义了
                    assertEquals("欢迎来到这家店", scan.get("reply"));
                }));

        String qaPick = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\","
                + "\"title\":\"象征什么?\",\"maxTries\":2,\"xp\":10,\"options\":["
                + "{\"id\":\"a\",\"label\":\"丰收\",\"correct\":true,\"fb\":\"对了,是麦穗。\"},"
                + "{\"id\":\"b\",\"label\":\"远航\",\"correct\":false}]}}";
        SETUPS.put("qa:pick", setup(qaPick, "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "qa").get("passed"),
                        "选中了正确那条该判过 —— 判不过多半是 optionId 这个字段名没对上")));

        String stopwatch = "{\"schemaVersion\":1,\"stopwatch\":{\"enabled\":true,\"targetSeconds\":10,"
                + "\"toleranceMs\":300,\"tries\":3,\"xp\":10}}";
        SETUPS.put("stopwatch:start", setup(stopwatch, "{}",
                (view) -> assertEquals(Boolean.TRUE, kit(view, "stopwatch").get("started"), "没开表")));
        SETUPS.put("stopwatch:submit", setup(stopwatch, started("stopwatch", 12_000L),
                (view) -> assertEquals(Boolean.TRUE, kit(view, "stopwatch").get("passed"),
                        "停在 9.88 秒、目标 10±0.3 该算准 —— 没准多半是字段名没对上")));
    }

    /** 挑战类要先 START_CHALLENGE 才收成绩,这里直接把开表时刻摆进状态里。
     *  开表多久之前有讲究:服务端会拿「你报的成绩」和「服务器这边真的过去了多久」对一遍,
     *  所以场地必须给得比成绩长,否则被判「对不上」—— 那是夹具摆得不对,不是代码错。 */
    private static String started(String game, long msAgo) {
        return "{\"" + game + "\":{\"startedAtMs\":" + (NOW - msAgo) + ",\"attempts\":0}}";
    }

    private static Setup setup(String config, String state, java.util.function.Consumer<Map<String, Object>> check) {
        return new Setup() {
            public String config() { return config; }
            public String state() { return state; }
            public void verify(Map<String, Object> view) { check.accept(view); }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> kit(Map<String, Object> view, String game) {
        Map<String, Object> playKit = (Map<String, Object>) view.get("playKit");
        assertNotNull(playKit, "playKit 整块都没有");
        Map<String, Object> one = (Map<String, Object>) playKit.get(game);
        assertNotNull(one, game + " 没进 playKit");
        return one;
    }

    private void run(String label, Setup setup, String serverAction, JsonNode payloadNode) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(SESSION_ID);
        session.setNodeId(NODE_ID);
        session.setTopicId(TOPIC_ID);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setConfigSnapshotJson(setup.config());
        session.setStateJson(setup.state());
        session.setStartedAt(new Date(NOW - 60_000L));
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);

        Map<String, Object> payload = mapper.convertValue(payloadNode, Map.class);
        Map<String, Object> view;
        try {
            view = service.act(MEMBER_ID, SESSION_ID, 0, "k-" + label, serverAction, payload);
        } catch (RuntimeException e) {
            fail(label + " 被服务端拒了:" + e.getMessage() + " —— 客户端发的字段名与服务端对不上");
            return;
        }
        setup.verify(view);
    }
}
