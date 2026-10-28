package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 预制人生 typeIn 限时打字的运行期契约。
 *
 * ★ 必须先开表(START_CHALLENGE):设备时钟玩家改得动,不先开表的提交一律判「还没开始」。
 * 判定 = 内容相等(按 caseSensitive)+ 用时 ≤ seconds,全在服务端。
 */
class PlayKitTypeInRuntimeTest {

    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;

    private GameRuntimeSessionMapper sessionMapper;
    private PlayAttemptLogMapper attemptMapper;
    private GameRuntimeSession session;
    private AdvancedGameRuntimeServiceImpl service;
    private Date now = new Date(1789017600000L);

    private static final String TYPE_IN_CONFIG = "{\"schemaVersion\":1,\"typeIn\":{\"enabled\":true,"
            + "\"title\":\"打出这行字\",\"target\":\"hello world\",\"seconds\":10,"
            + "\"caseSensitive\":false,\"tries\":0,\"xp\":5}}";

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(service, "templateMapper",
                mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper",
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper",
                mock(com.chengyinhub.business.mapper.PlayTeamMapper.class));
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> now);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @Test
    void 开表后按时打完算过_给分一次() {
        session = newSession(TYPE_IN_CONFIG);
        startChallenge("typein-1");
        advance(2000L);

        Map<String, Object> view = act("typein-submit-1", "HELLO WORLD", 2000L);

        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("typeIn");
        assertEquals(true, kit.get("passed"));
        assertEquals(true, kit.get("submitted"));
        assertEquals(1, kit.get("attempts"));
        assertEquals(5, view.get("score"));
        assertEquals("hello world", kit.get("target"), "target 就是题面,必须下发");
        assertEquals(false, kit.get("terminalSkin"), "没配终端样式就是普通台面");
    }

    @Test
    void 终端样式照配置下发_不影响判定() {
        String config = TYPE_IN_CONFIG.replace("\"xp\":5", "\"xp\":5,\"terminalSkin\":true");
        session = newSession(config);
        startChallenge("typein-t1");
        advance(2000L);
        Map<String, Object> view = act("typein-submit-t1", "hello world", 2000L);
        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("typeIn");
        assertEquals(true, kit.get("terminalSkin"));
        assertEquals(true, kit.get("passed"));
        String safe = com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(config);
        org.junit.jupiter.api.Assertions.assertTrue(safe.contains("\"terminalSkin\":true"), safe);
    }

    @Test
    void 负控_没开表就提交判还没开始() {
        session = newSession(TYPE_IN_CONFIG);
        when(attemptMapper.selectByIdempotencyKey("typein-early")).thenReturn(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> act("typein-early", "hello world", 1000L));
        assertTrue(e.getMessage().contains("还没开始"), e.getMessage());
    }

    @Test
    void 负控_超过seconds判超时() {
        session = newSession(TYPE_IN_CONFIG);
        startChallenge("typein-slow-start");
        advance(13000L);

        // 13 秒 > 10 秒 + 1.5 秒宽容量:真的超了,不是网络抖动
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> act("typein-slow", "hello world", 13000L));
        assertTrue(e.getMessage().contains("超时"), e.getMessage());
    }

    @Test
    void 负控_用时与服务器记录对不上必须拒() {
        session = newSession(TYPE_IN_CONFIG);
        startChallenge("typein-skew-start");
        advance(1000L);

        // 服务器只过了 1 秒,客户端报 5 秒 —— 这两个数不可能来自同一段真实经过的时间
        assertThrows(IllegalArgumentException.class, () -> act("typein-skew", "hello world", 5000L));
    }

    @Test
    void 负控_内容打错且次数用尽判不过并收摊() {
        String config = TYPE_IN_CONFIG.replace("\"tries\":0", "\"tries\":1");
        session = newSession(config);
        startChallenge("typein-once-start");
        advance(1500L);

        Map<String, Object> view = act("typein-wrong", "hello wrold", 1500L);
        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("typeIn");
        assertEquals(false, kit.get("passed"));
        assertEquals(true, kit.get("submitted"), "次数用尽要收摊,不能永远卡着");
        assertEquals(0, view.get("score"));

        assertThrows(IllegalStateException.class, () -> act("typein-again", "hello world", 1000L));
    }

    @Test
    void 负控_打错但还有次数时保留重来机会() {
        session = newSession(TYPE_IN_CONFIG);
        startChallenge("typein-retry-start");
        advance(1500L);

        Map<String, Object> first = act("typein-retry-wrong", "nihao", 1500L);
        Map<?, ?> firstKit = (Map<?, ?>) ((Map<?, ?>) first.get("playKit")).get("typeIn");
        assertEquals(false, firstKit.get("passed"));
        assertEquals(false, firstKit.get("submitted"), "tries=0 不限次,不该收摊");
        assertEquals(false, firstKit.get("started"), "打错后要重新开表");

        // 没重新开表就直接再交 —— 判还没开始
        assertThrows(IllegalStateException.class, () -> act("typein-no-start", "hello world", 1000L));

        startChallenge("typein-retry-start-2");
        advance(1000L);
        Map<String, Object> second = act("typein-retry-right", "hello world", 1000L);
        Map<?, ?> secondKit = (Map<?, ?>) ((Map<?, ?>) second.get("playKit")).get("typeIn");
        assertEquals(true, secondKit.get("passed"));
        assertEquals(2, secondKit.get("attempts"));
    }

    @Test
    void 负控_大小写敏感时打错大小写不算过() {
        String config = TYPE_IN_CONFIG.replace("\"caseSensitive\":false", "\"caseSensitive\":true");
        session = newSession(config);
        startChallenge("typein-case-start");
        advance(1000L);

        Map<String, Object> view = act("typein-case-wrong", "Hello world", 1000L);
        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("typeIn");
        assertEquals(false, kit.get("passed"));
    }

    // ==================== 脚手架 ====================

    private void startChallenge(String key) {
        when(attemptMapper.selectByIdempotencyKey(key)).thenReturn(null);
        service.act(MEMBER_ID, SESSION_ID, session.getVersion(), key, "START_CHALLENGE",
                Collections.singletonMap("game", "typeIn"));
    }

    private void advance(long ms) {
        now = new Date(now.getTime() + ms);
    }

    private void arm() {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenAnswer(inv -> session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
    }

    private Map<String, Object> act(String key, String text, long elapsedMs) {
        when(attemptMapper.selectByIdempotencyKey(key)).thenReturn(null);
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        payload.put("elapsedMs", elapsedMs);
        return service.act(MEMBER_ID, SESSION_ID, session.getVersion(), key, "SUBMIT_TYPE_IN", payload);
    }

    private GameRuntimeSession newSession(String config) {
        GameRuntimeSession fresh = new GameRuntimeSession();
        fresh.setId(SESSION_ID);
        fresh.setActivityId(ACTIVITY_ID);
        fresh.setTopicId(TOPIC_ID);
        fresh.setNodeId(NODE_ID);
        fresh.setTemplateId(TEMPLATE_ID);
        fresh.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        fresh.setOwnerId(MEMBER_ID);
        fresh.setConfigSnapshotJson(config);
        fresh.setStateJson("{}");
        fresh.setStatus(GameRuntimeSession.STATUS_RUNNING);
        fresh.setVersion(0);
        fresh.setScore(0);
        fresh.setStartedAt(now);
        arm();
        return fresh;
    }
}
