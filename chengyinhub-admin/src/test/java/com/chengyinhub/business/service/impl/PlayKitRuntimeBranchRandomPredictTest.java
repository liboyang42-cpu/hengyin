package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayPredictResult;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayPredictMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分支 / 抽卡 / 竞猜三段的运行时投影。
 *
 * <p>★ 这三段此前压根不在 playKit 里 —— 后端算得好好的,客户端那一层永远收不到,
 * 于是这三个玩法在线上打不开。不报错,只是不出现。这份用例就是那个缺口的负控。
 *
 * <p>另外两条是保密线:盲盒的物品清单和分支的整棵树**不许**下发。
 * 发了这两个玩法当场作废 —— 抽之前就看得见盒子里有什么、选之前就知道每条路通向哪。
 */
class PlayKitRuntimeBranchRandomPredictTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L;
    private static final long NODE_ID = 29L;
    private static final long SESSION_ID = 51L;

    private GameRuntimeSessionMapper sessionMapper;
    private PlayPredictMapper predictMapper;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        predictMapper = mock(PlayPredictMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", mock(CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "predictMapper", predictMapper);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
    }

    @Test
    void branchShipsOnlyTheStepYouAreStandingOn() {
        Map<String, Object> branch = kit(view(branchConfig(),
                "{\"branch\":{\"currentStepId\":\"start\",\"visitedStepIds\":[]}}"), "branch");

        assertNotNull(branch, "分支没进 playKit —— 客户端拿不到就打不开这个玩法");
        @SuppressWarnings("unchecked")
        Map<String, Object> step = (Map<String, Object>) branch.get("currentStep");
        assertEquals("start", step.get("id"));
        assertEquals("巷口有两条路。", step.get("body"));

        String json = String.valueOf(branch);
        assertFalse(json.contains("nextStepId"), "选了会去哪是玩家要猜的事,不能下发");
        assertFalse(json.contains("score"), "每条路值多少分同理");
        assertFalse(json.contains("另一条路的正文"), "整棵树下发等于把所有结局提前剧透");
    }

    @Test
    void randomShipsHowManyDrawsAndWhatYouGotButNeverTheBox() {
        Map<String, Object> random = kit(view(randomConfig(),
                "{\"draws\":[{\"id\":\"a\",\"label\":\"再来一杯\",\"content\":\"下次到店出示\",\"weight\":10}]}"),
                "random");

        assertNotNull(random, "盲盒没进 playKit");
        assertEquals(2, random.get("drawCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> drawn = (List<Map<String, Object>>) random.get("drawn");
        assertEquals(1, drawn.size());
        assertEquals("再来一杯", drawn.get(0).get("label"));

        String json = String.valueOf(random);
        assertFalse(json.contains("还没抽到的那件"), "没抽到的物品不能下发 —— 抽之前就看得见就不是盲盒了");
        assertFalse(json.contains("weight"), "权重更不能下发:它直接告诉玩家哪件最难抽");
    }

    @Test
    void predictShipsTheQuestionButTheAnswerDoesNotExistYet() {
        when(predictMapper.selectResult(eqNode(), any())).thenReturn(null);
        Map<String, Object> predict = kit(view(predictConfig(),
                "{\"predict\":{\"optionKey\":\"b\",\"playDay\":\"2026-04-01\"}}"), "predict");

        assertNotNull(predict, "竞猜没进 playKit");
        assertEquals("今天哪款卖得最多?", predict.get("question"));
        assertEquals("b", predict.get("myOptionKey"));
        assertFalse(predict.containsKey("settledOption"),
                "这一轮还没结算,屏上不能出现答案 —— 此刻它根本还不存在");
    }

    @Test
    void predictRevealsTheAnswerOnlyAfterTheMerchantSettles() {
        PlayPredictResult round = new PlayPredictResult();
        round.setNodeId(NODE_ID);
        round.setSettleStatus(PlayPredictResult.SETTLE_DONE);
        round.setSettledOption("b");
        when(predictMapper.selectResult(eqNode(), any())).thenReturn(round);

        Map<String, Object> predict = kit(view(predictConfig(),
                "{\"predict\":{\"optionKey\":\"b\",\"playDay\":\"2026-04-01\"}}"), "predict");

        assertEquals(PlayPredictResult.SETTLE_DONE, predict.get("settleStatus"));
        assertEquals("b", predict.get("settledOption"));
        assertEquals(Boolean.TRUE, predict.get("won"));
    }

    @Test
    void predictSaysVoidedInsteadOfWaitingForever() {
        PlayPredictResult round = new PlayPredictResult();
        round.setNodeId(NODE_ID);
        round.setSettleStatus(PlayPredictResult.SETTLE_VOID);
        when(predictMapper.selectResult(eqNode(), any())).thenReturn(round);

        Map<String, Object> predict = kit(view(predictConfig(),
                "{\"predict\":{\"optionKey\":\"b\",\"playDay\":\"2026-04-01\"}}"), "predict");

        assertEquals(PlayPredictResult.SETTLE_VOID, predict.get("settleStatus"),
                "商家超时没给答案的那一轮已经结束了,屏上不能永远停在「等揭晓」");
        assertFalse(predict.containsKey("settledOption"), "作废的轮次没有答案可给");
    }

    @Test
    void predictWithoutABetDoesNotEvenLookUpTheRound() {
        Map<String, Object> predict = kit(view(predictConfig(), "{}"), "predict");

        assertNotNull(predict);
        assertEquals("", predict.get("myOptionKey"));
        assertFalse(predict.containsKey("settleStatus"), "还没押就没有可揭晓的东西");
    }

    // ── 夹具 ──

    private static Long eqNode() { return org.mockito.ArgumentMatchers.eq(NODE_ID); }
    private static java.sql.Date any() { return org.mockito.ArgumentMatchers.any(java.sql.Date.class); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> kit(Map<String, Object> view, String game) {
        Map<String, Object> playKit = (Map<String, Object>) view.get("playKit");
        assertTrue(playKit != null, "playKit 整块都没有");
        return (Map<String, Object>) playKit.get(game);
    }

    private Map<String, Object> view(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(SESSION_ID);
        session.setNodeId(NODE_ID);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setConfigSnapshotJson(config);
        session.setStateJson(state);
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);
        return service.state(MEMBER_ID, SESSION_ID);
    }

    private String branchConfig() {
        return "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
                + "{\"id\":\"start\",\"terminal\":false,\"body\":\"巷口有两条路。\",\"options\":["
                + "{\"id\":\"left\",\"label\":\"往左\",\"nextStepId\":\"end\",\"score\":5}]},"
                + "{\"id\":\"end\",\"terminal\":true,\"outcomeCode\":\"DONE\","
                + "\"body\":\"另一条路的正文\",\"options\":[]}]}}";
    }

    private String randomConfig() {
        return "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":2,\"items\":["
                + "{\"id\":\"a\",\"label\":\"再来一杯\",\"content\":\"下次到店出示\",\"weight\":10},"
                + "{\"id\":\"b\",\"label\":\"还没抽到的那件\",\"weight\":1}]}}";
    }

    private String predictConfig() {
        return "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"今天哪款卖得最多?\","
                + "\"closeAtHour\":21,\"xp\":30,\"options\":["
                + "{\"key\":\"a\",\"label\":\"冰美式\"},{\"key\":\"b\",\"label\":\"燕麦拿铁\"}]}}";
    }
}
