package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预制人生 profile 建档的运行期契约。
 *
 * 两条要害:
 *   ① 加成走现成的 effects 形状,由旅程本局状态承接 —— 本类只负责把「选了哪个 key」
 *      交给旅程运行时,自己不算数值;
 *   ② 加成**绝不进客户端投影** —— 露出来就成了攻略表。
 */
class PlayKitProfileRuntimeTest {

    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;

    private GameRuntimeSessionMapper sessionMapper;
    private PlayAttemptLogMapper attemptMapper;
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    private AdvancedGameRuntimeServiceImpl service;
    private Date now = at(2026, 9, 17, 12, 0);

    private static Date at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        topicRouteRuntimeService = mock(ITopicRouteRuntimeService.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);

        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", topicRouteRuntimeService);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> now);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        activity.setStartDate(at(2026, 9, 1, 0, 0));
        activity.setEndDate(at(2026, 9, 30, 23, 59));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    private static final String PROFILE_CONFIG = "{\"schemaVersion\":1,\"profile\":{\"enabled\":true,"
            + "\"title\":\"出生登记\",\"lead\":\"填完这张表,你就有了身份\",\"xp\":7,"
            + "\"avatar\":{\"enabled\":true,\"required\":false},"
            + "\"questions\":["
            + "{\"key\":\"name\",\"label\":\"你叫什么\",\"kind\":\"text\",\"maxLength\":12,\"required\":true},"
            + "{\"key\":\"job\",\"label\":\"你做什么工作\",\"kind\":\"pick\",\"options\":["
            + "{\"key\":\"clerk\",\"label\":\"店员\",\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":2}]},"
            + "{\"key\":\"painter\",\"label\":\"画师\","
            + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.energy\",\"value\":1}]}]}]}}";

    @Test
    void 建档落答案_加成交给旅程运行时执行() {
        GameRuntimeSession session = running(PROFILE_CONFIG, "{}");
        arm(session);

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("name", "阿猫");
        answers.put("job", "clerk");
        Map<String, Object> view = act(session, "profile-1", "SUBMIT_PROFILE",
                "answers", answers, "avatarUrl", "https://cdn.example.com/a.png");

        assertEquals(7, view.get("score"), "建档完成给一次 xp");
        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("profile");
        assertEquals(true, kit.get("done"));
        assertEquals("https://cdn.example.com/a.png", kit.get("avatarUrl"));
        Map<?, ?> stored = (Map<?, ?>) kit.get("answers");
        assertEquals("阿猫", stored.get("name"));
        assertEquals("clerk", stored.get("job"));
        // 加成不在本类里算:只把选项 key 作为一次「选择」交给旅程运行时(它按作者变量写)
        verify(topicRouteRuntimeService).recordChoice(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "profile", "clerk");
        // 会话视图也要给同一份故事变量(与 /api/play/nodes 进度回包共用 StoryVars)
        Map<?, ?> vars = (Map<?, ?>) view.get("vars");
        assertEquals("阿猫", vars.get("name"));
        assertEquals("clerk", vars.get("job"));
    }

    @Test
    void 负控_加成绝不进客户端投影() {
        GameRuntimeSession session = running(PROFILE_CONFIG, "{}");
        arm(session);

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("name", "阿猫");
        answers.put("job", "painter");
        Map<String, Object> view = act(session, "profile-peek", "SUBMIT_PROFILE", "answers", answers);

        // 露出来就成了攻略表:effects / sys.luck / counter.energy 一个字都不该出现
        String dumped = String.valueOf(view);
        assertFalse(dumped.contains("effects"), dumped);
        assertFalse(dumped.contains("sys.luck"), dumped);
        assertFalse(dumped.contains("counter.energy"), dumped);
        // 但选项本身要在(玩家得看得见可选项)
        assertTrue(dumped.contains("画师"), dumped);
    }

    @Test
    void 负控_重复提交必须拒() {
        GameRuntimeSession session = running(PROFILE_CONFIG,
                "{\"profile\":{\"done\":true,\"answers\":{\"name\":\"阿猫\"}}}");
        arm(session);

        assertThrows(IllegalStateException.class,
                () -> act(session, "profile-again", "SUBMIT_PROFILE", "answers", new LinkedHashMap<>()));
    }

    @Test
    void 负控_必填没填必须拒() {
        GameRuntimeSession session = running(PROFILE_CONFIG, "{}");
        arm(session);

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("name", "");
        answers.put("job", "clerk");
        assertThrows(IllegalArgumentException.class,
                () -> act(session, "profile-blank", "SUBMIT_PROFILE", "answers", answers));
    }

    @Test
    void 负控_选了不存在的选项必须拒() {
        GameRuntimeSession session = running(PROFILE_CONFIG, "{}");
        arm(session);

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("name", "阿猫");
        answers.put("job", "astronaut");
        assertThrows(IllegalArgumentException.class,
                () -> act(session, "profile-bad", "SUBMIT_PROFILE", "answers", answers));
    }

    @Test
    void 旧选填配置的空白文字和空白选项也必须拒_运行投影同步必答() {
        String config = PROFILE_CONFIG.replace("\"required\":true", "\"required\":false")
                .replace("\"kind\":\"pick\"", "\"kind\":\"pick\",\"required\":false");
        GameRuntimeSession session = running(config, "{}");
        arm(session);
        for (String key : new String[] {"name", "job"}) {
            Map<String, Object> blank = new LinkedHashMap<>();
            blank.put("name", "阿猫");
            blank.put("job", "clerk");
            blank.put(key, "  ");
            assertThrows(IllegalArgumentException.class,
                    () -> act(session, "profile-legacy-blank-" + key, "SUBMIT_PROFILE", "answers", blank));
            blank.remove(key);
            assertThrows(IllegalArgumentException.class,
                    () -> act(session, "profile-legacy-missing-" + key, "SUBMIT_PROFILE", "answers", blank));
        }
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("name", "阿猫");
        answers.put("job", "clerk");
        Map<String, Object> view = act(session, "profile-legacy-valid", "SUBMIT_PROFILE", "answers", answers);
        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("profile");
        for (Object question : (java.util.List<?>) kit.get("questions")) {
            assertEquals(true, ((Map<?, ?>) question).get("required"));
        }
        assertEquals(false, ((Map<?, ?>) kit.get("avatar")).get("required"));
    }

    @Test
    void 负控_头像必填时不能空着手交() {
        String config = PROFILE_CONFIG.replace("\"required\":false", "\"required\":true");
        GameRuntimeSession session = running(config, "{}");
        arm(session);

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("name", "阿猫");
        answers.put("job", "clerk");
        assertThrows(IllegalArgumentException.class,
                () -> act(session, "profile-no-avatar", "SUBMIT_PROFILE", "answers", answers));
    }

    private static final String KACCHA_CONFIG = PROFILE_CONFIG.replace(
            "\"kind\":\"pick\",\"options\"",
            "\"kind\":\"pick\",\"override\":{\"requires\":[{\"op\":\"GTE\",\"var\":\"counter.instab\",\"value\":4}],"
                    + "\"option\":\"clerk\",\"voice\":\"这一栏已经填好了。\"},\"options\"");

    @Test
    void 咔哒_本局条件满足时选项被改写成作者定的那一项_加成也按改写后的走() {
        GameRuntimeSession session = running(KACCHA_CONFIG, "{}");
        arm(session);
        when(topicRouteRuntimeService.conditionsPass(org.mockito.ArgumentMatchers.eq(MEMBER_ID),
                org.mockito.ArgumentMatchers.eq(TOPIC_ID), org.mockito.ArgumentMatchers.eq(ACTIVITY_ID), any())).thenReturn(true);

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("name", "阿猫");
        answers.put("job", "painter");
        Map<String, Object> view = act(session, "profile-kaccha", "SUBMIT_PROFILE", "answers", answers);

        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("profile");
        assertEquals("clerk", ((Map<?, ?>) kit.get("answers")).get("job"));
        Map<?, ?> hit = (Map<?, ?>) ((java.util.List<?>) kit.get("overrides")).get(0);
        assertEquals("画师", hit.get("picked"));
        assertEquals("店员", hit.get("assigned"));
        assertEquals("这一栏已经填好了。", hit.get("voice"));
        verify(topicRouteRuntimeService).recordChoice(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "profile", "clerk");
    }

    @Test
    void 负控_咔哒条件不满足时照玩家选的记_视图不露改写配置() {
        GameRuntimeSession session = running(KACCHA_CONFIG, "{}");
        arm(session);
        when(topicRouteRuntimeService.conditionsPass(any(), any(), any(), any())).thenReturn(false);

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("name", "阿猫");
        answers.put("job", "painter");
        Map<String, Object> view = act(session, "profile-no-kaccha", "SUBMIT_PROFILE", "answers", answers);

        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("profile");
        assertEquals("painter", ((Map<?, ?>) kit.get("answers")).get("job"));
        String dumped = String.valueOf(view);
        assertFalse(dumped.contains("overrides"), dumped);
        assertFalse(dumped.contains("counter.instab"), dumped);
        assertFalse(dumped.contains("这一栏已经填好了"), dumped);
    }

    // ==================== 脚手架 ====================

    private void arm(GameRuntimeSession session) {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
    }

    private Map<String, Object> act(GameRuntimeSession session, String key, String action, Object... kv) {
        when(attemptMapper.selectByIdempotencyKey(key)).thenReturn(null);
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            payload.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return service.act(MEMBER_ID, SESSION_ID, session.getVersion(), key, action,
                payload.isEmpty() ? Collections.emptyMap() : payload);
    }

    private GameRuntimeSession running(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(SESSION_ID);
        session.setActivityId(ACTIVITY_ID);
        session.setTopicId(TOPIC_ID);
        session.setNodeId(NODE_ID);
        session.setTemplateId(TEMPLATE_ID);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(config);
        session.setStateJson(state);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setScore(0);
        session.setStartedAt(now);
        return session;
    }
}
