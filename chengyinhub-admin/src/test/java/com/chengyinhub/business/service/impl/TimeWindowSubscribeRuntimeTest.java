package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 时段限定的「开启提醒」订阅契约(action {@code SUBSCRIBE_TIME_WINDOW})。
 *
 * <p>修之前的状态:小程序那一头点了「开启提醒」、{@code wx.requestSubscribeMessage}
 * 也真的成功了,后端 act switch 却没有这个 case —— 直接落到 default 抛
 * 「高级玩法动作不支持」,玩家看见一句报错;会话视图的 timeWindow 段也不回读订阅状态,
 * 于是「开不开提醒」这件事在整条链路上根本没有落点。
 *
 * <p>★ 订阅只是**给玩家自己少看一次重复报错**,它不判定通关:时段限定的闸在 start()
 * 已经把守(见 AdvancedGameConfigValidator / requireWithinTimeWindow 的口径),
 * 最后一条用例就是钉住这一点。
 */
class TimeWindowSubscribeRuntimeTest {

    private static final long MEMBER_ID = 7L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long SESSION_ID = 51L;
    private static final Date NOW = new Date(1_775_000_000_000L);
    private static final String TMPL_ID = "abcdef-订阅模板-1";

    private static final String WINDOW_CONFIG = "{\"schemaVersion\":1,"
            + "\"timeWindow\":{\"enabled\":true,\"openFrom\":\"23:00\",\"openTo\":\"01:00\","
            + "\"eyebrow\":\"午夜档\",\"title\":\"零点开始\",\"subscribeTmplId\":\"" + TMPL_ID + "\"}}";

    /** 时段限定 + 扫码:扫码没做完,readyForBase 就该恒为 false —— 拿它当探针。 */
    private static final String WINDOW_AND_SCAN = "{\"schemaVersion\":1,"
            + "\"timeWindow\":{\"enabled\":true,\"openFrom\":\"00:00\",\"openTo\":\"23:59\"},"
            + "\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"reply\":\"欢迎来到这家店\",\"xp\":10}}";

    private GameRuntimeSessionMapper sessionMapper;
    private PlayAttemptLogMapper attemptMapper;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", mock(CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> NOW);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        // playBingoService 故意不设:九宫格读不到就等于没有这一段,本类只测时段限定
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @Test
    void 订阅记进会话状态并真的走CAS落库() {
        session(WINDOW_CONFIG, "{}");

        Map<String, Object> view = act("sub-1", "SUBSCRIBE_TIME_WINDOW", tmpl(TMPL_ID));

        assertEquals(Boolean.TRUE, timeWindow(view).get("subscribed"));
        ArgumentCaptor<GameRuntimeSession> persisted = ArgumentCaptor.forClass(GameRuntimeSession.class);
        verify(sessionMapper).updateStateCas(persisted.capture());
        assertTrue(persisted.getValue().getStateJson().contains("\"subscribed\":true"),
                "没写进 state JSON 就等于没落库:" + persisted.getValue().getStateJson());
        assertTrue(persisted.getValue().getStateJson().contains(TMPL_ID),
                "tmplId 要存进去备查:" + persisted.getValue().getStateJson());

        // 换一个新会话对象(库里重读)再问一次状态:订阅必须还在
        GameRuntimeSession reloaded = session(WINDOW_CONFIG, persisted.getValue().getStateJson());
        reloaded.setVersion(1);
        assertEquals(Boolean.TRUE, timeWindow(service.state(MEMBER_ID, SESSION_ID)).get("subscribed"));
    }

    @Test
    void 没订阅时timeWindow段回带false() {
        session(WINDOW_CONFIG, "{}");

        Map<String, Object> view = service.state(MEMBER_ID, SESSION_ID);

        assertEquals(Boolean.FALSE, timeWindow(view).get("subscribed"));
        // 原有字段一个都不能少
        assertEquals("23:00", timeWindow(view).get("openFrom"));
        assertEquals("01:00", timeWindow(view).get("openTo"));
        assertEquals(TMPL_ID, timeWindow(view).get("subscribeTmplId"));
        assertEquals("午夜档", timeWindow(view).get("eyebrow"));
        assertEquals("零点开始", timeWindow(view).get("title"));
    }

    @Test
    void 重复订阅是幂等的_version照常推进() {
        GameRuntimeSession session = session(WINDOW_CONFIG, "{}");

        Map<String, Object> first = act("sub-1", "SUBSCRIBE_TIME_WINDOW", tmpl(TMPL_ID));
        assertEquals(1, first.get("version"));

        // 玩家重进页面再点一次很正常:不该看见失败,也不该多出第二个计数
        session.setVersion(1);
        Map<String, Object> second = act("sub-2", "SUBSCRIBE_TIME_WINDOW", tmpl(TMPL_ID));
        assertEquals(2, second.get("version"));
        assertEquals(Boolean.TRUE, timeWindow(second).get("subscribed"));
        assertFalse(String.valueOf(second).contains("subscribedCount"),
                "订阅是个状态位,不是次数:" + second);
        verify(sessionMapper, times(2)).updateStateCas(any());
    }

    @Test
    void 幂等键重放不再推进版本() {
        GameRuntimeSession original = session(WINDOW_CONFIG, "{}");
        PlayAttemptLog logged = new PlayAttemptLog();
        logged.setMemberId(MEMBER_ID);
        logged.setAttemptType(8);
        logged.setIdempotencyKey("sub-1");
        logged.setRequestFingerprint(ReflectionTestUtils.invokeMethod(service, "commandFingerprint", original,
                MEMBER_ID, original.getVersion(), "sub-1", "SUBSCRIBE_TIME_WINDOW", tmpl(TMPL_ID)));
        logged.setResultJson("{\"sessionId\":" + SESSION_ID + ",\"version\":1}");
        when(attemptMapper.selectByIdempotencyKey("sub-1")).thenReturn(logged);

        assertEquals(1, act("sub-1", "SUBSCRIBE_TIME_WINDOW", tmpl(TMPL_ID)).get("version"));
        verify(sessionMapper, times(0)).updateStateCas(any());
    }

    @Test
    void 负控_没开时段限定的会话不认这个动作() {
        session("{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\","
                + "\"reply\":\"欢迎\",\"xp\":10}}", "{}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> act("sub-x", "SUBSCRIBE_TIME_WINDOW", tmpl(TMPL_ID)));
        assertTrue(e.getMessage().contains("时段限定"), e.getMessage());
        verify(sessionMapper, times(0)).updateStateCas(any());
    }

    @Test
    void 负控_模板id超长要拒_与配置校验同一口径() {
        session(WINDOW_CONFIG, "{}");
        String tooLong = new String(new char[65]).replace('\0', 'a');

        assertThrows(IllegalArgumentException.class,
                () -> act("sub-long", "SUBSCRIBE_TIME_WINDOW", tmpl(tooLong)));
        verify(sessionMapper, times(0)).updateStateCas(any());
    }

    @Test
    void 负控_不带tmplId也只记订阅状态_不许凭空造出一个模板id() {
        session(WINDOW_CONFIG, "{}");

        act("sub-bare", "SUBSCRIBE_TIME_WINDOW", Collections.emptyMap());

        ArgumentCaptor<GameRuntimeSession> persisted = ArgumentCaptor.forClass(GameRuntimeSession.class);
        verify(sessionMapper).updateStateCas(persisted.capture());
        assertTrue(persisted.getValue().getStateJson().contains("\"subscribed\":true"),
                "没给 tmplId 也要把订阅这件事记下来:" + persisted.getValue().getStateJson());
        assertFalse(persisted.getValue().getStateJson().contains("tmplId"),
                "客户端没给 tmplId,状态里就不该冒出一个模板 id:" + persisted.getValue().getStateJson());
    }

    @Test
    void 铁律_订阅不改变通关判定() {
        GameRuntimeSession session = session(WINDOW_AND_SCAN, "{}");

        assertEquals(Boolean.FALSE, act("tw-1", "SUBSCRIBE_TIME_WINDOW", tmpl(TMPL_ID)).get("readyForBase"),
                "点个提醒不该把这一站判成做完");
        session.setVersion(1);
        session.setStateJson("{\"scan\":{\"scanned\":true}}");

        assertEquals(Boolean.TRUE, act("tw-2", "SUBSCRIBE_TIME_WINDOW", tmpl(TMPL_ID)).get("readyForBase"),
                "通关与否只看扫码做没做,订阅状态不参与");
    }

    // ==================== 脚手架 ====================

    private GameRuntimeSession session(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(SESSION_ID);
        session.setActivityId(0L);
        session.setTopicId(TOPIC_ID);
        session.setNodeId(NODE_ID);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(config);
        session.setStateJson(state);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setScore(0);
        session.setStartedAt(NOW);
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);
        return session;
    }

    private Map<String, Object> tmpl(String tmplId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tmplId", tmplId);
        return payload;
    }

    private Map<String, Object> act(String key, String action, Map<String, Object> payload) {
        GameRuntimeSession session = sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID);
        return service.act(MEMBER_ID, SESSION_ID, session.getVersion(), key, action, payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> timeWindow(Map<String, Object> view) {
        Object playKit = view.get("playKit");
        assertNotNull(playKit, "playKit 整块都没有");
        Object section = ((Map<String, Object>) playKit).get("timeWindow");
        assertTrue(section instanceof Map, "timeWindow 没进 playKit:" + playKit);
        return (Map<String, Object>) section;
    }
}
