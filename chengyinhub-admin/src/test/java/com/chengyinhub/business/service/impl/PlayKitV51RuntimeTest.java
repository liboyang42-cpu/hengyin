package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Figma v5.1 新玩法的运行期契约。
 *
 * 重点在**判定权归属**:盲品答案、计步步数、城市签选签,三件事都必须由服务端说了算。
 * 每条都配了负控 —— 这类错误不会抛异常,只会静默让玩法作废或让人刷分。
 */
class PlayKitV51RuntimeTest {

    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;

    private GameRuntimeSessionMapper sessionMapper;
    private CmsMemberTemplateMapper templateMapper;
    private PlayAttemptLogMapper attemptMapper;
    private IWeChatMiniSessionService weChatMiniSessionService;
    private IWxContentSecurityService wxContentSecurityService;
    private AdvancedGameRuntimeServiceImpl service;
    /** 测试可拨的"服务端时钟" */
    private Date now = at(2026, 8, 26, 12, 0);

    private static Date at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        weChatMiniSessionService = mock(IWeChatMiniSessionService.class);
        wxContentSecurityService = mock(IWxContentSecurityService.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);

        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        CmsRegistrationMapper registrations=mock(CmsRegistrationMapper.class);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.install(activityMapper,registrations);
        ReflectionTestUtils.setField(service, "registrationMapper", registrations);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "weChatMiniSessionService", weChatMiniSessionService);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", wxContentSecurityService);
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
        // 活动窗口开得足够宽:本类会把服务端时钟拨到 23:30 与次日 00:30 去验跨夜时段,
        // 活动窗口若只开一小时,测的就变成"活动结束"而不是时段门禁了
        activity.setStartDate(at(2026, 8, 1, 0, 0));
        activity.setEndDate(at(2026, 9, 30, 23, 59));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);
        when(wxContentSecurityService.checkText(anyString(), any())).thenReturn(null);
    }

    // ==================== 盲品 ====================

    private static final String BLIND_CONFIG = "{\"schemaVersion\":1,\"blindTaste\":{\"enabled\":true,"
            + "\"title\":\"闭上眼\",\"xp\":15,"
            + "\"options\":[{\"key\":\"A\",\"label\":\"桂花乌龙\"},{\"key\":\"B\",\"label\":\"茉莉雪芽\"}],"
            + "\"answerKey\":\"B\"}}";

    @Test
    void 盲品答对给分_答错不给分且可以重答() {
        GameRuntimeSession session = running(BLIND_CONFIG, "{}");
        arm(session);

        Map<String, Object> wrong = act(session, "wrong-1", "SUBMIT_BLIND_TASTE", "key", "A");
        assertEquals(0, wrong.get("score"), "答错不能给分");

        // 答错之后必须还能再答 —— 用户拍板:可重答,答对才给分
        session.setVersion(1);
        Map<String, Object> right = act(session, "right-1", "SUBMIT_BLIND_TASTE", "key", "B");
        assertEquals(15, right.get("score"));
        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) right.get("playKit")).get("blindTaste");
        assertEquals(true, kit.get("solved"));
        assertEquals(2, kit.get("attempts"));
    }

    @Test
    void 负控_答对之后再提交必须拒绝() {
        // 不拦住 = 同一题的 xp 能反复领
        GameRuntimeSession session = running(BLIND_CONFIG, "{\"blindTaste\":{\"solved\":true}}");
        arm(session);

        assertThrows(IllegalStateException.class,
                () -> act(session, "again-1", "SUBMIT_BLIND_TASTE", "key", "B"));
    }

    @Test
    void 负控_不存在的选项必须拒绝() {
        GameRuntimeSession session = running(BLIND_CONFIG, "{}");
        arm(session);

        assertThrows(IllegalArgumentException.class,
                () -> act(session, "bad-1", "SUBMIT_BLIND_TASTE", "key", "Z"));
    }

    @Test
    void 负控_返回给客户端的视图里不能出现正确答案() {
        GameRuntimeSession session = running(BLIND_CONFIG, "{}");
        arm(session);

        Map<String, Object> view = act(session, "peek-1", "SUBMIT_BLIND_TASTE", "key", "A");

        // 整个视图序列化后都不该带 answerKey —— 带了这道题当场作废
        assertFalse(String.valueOf(view).contains("answerKey"), String.valueOf(view));
    }

    // ==================== 计步 ====================

    private static final String STEPS_CONFIG = "{\"schemaVersion\":1,"
            + "\"steps\":{\"enabled\":true,\"goal\":6000,\"xp\":20}}";

    /** 造一份 4286 步的微信运动密文,并让 mock 的 session service 返回配套的 key */
    private void armWeRun(int steps) {
        when(weChatMiniSessionService.miniAppId()).thenReturn(WeRunFixtures.APP_ID);
        when(weChatMiniSessionService.exchange(anyString()))
                .thenReturn(new IWeChatMiniSessionService.WeChatMiniSession("openid-1", WeRunFixtures.sessionKey()));
        lastCipher = WeRunFixtures.encryptSteps(steps,now.getTime()/1000L);
    }

    private String lastCipher;

    @Test
    void 计步达标只给一次分() {
        GameRuntimeSession session = running(STEPS_CONFIG, "{}");
        arm(session);
        armWeRun(6200);

        Map<String, Object> first = act(session, "steps-1", "SUBMIT_STEPS",
                "code", "js-code", "encryptedData", lastCipher, "iv", WeRunFixtures.iv());
        assertEquals(20, first.get("score"));

        // 步数还会继续涨,再同步一次不能再领一次
        session.setVersion(1);
        session.setStateJson("{\"steps\":{\"reached\":true,\"todaySteps\":6200}}");
        session.setScore(20);
        armWeRun(7000);
        Map<String, Object> second = act(session, "steps-2", "SUBMIT_STEPS",
                "code", "js-code", "encryptedData", lastCipher, "iv", WeRunFixtures.iv());
        assertEquals(20, second.get("score"), "达标分只能领一次");
    }

    @Test
    void 负控_客户端自报的步数一律不采信() {
        GameRuntimeSession session = running(STEPS_CONFIG, "{}");
        arm(session);
        armWeRun(10);   // 解密出来只有 10 步

        Map<String, Object> view = act(session, "steps-fake", "SUBMIT_STEPS",
                "code", "js-code", "encryptedData", lastCipher, "iv", WeRunFixtures.iv(),
                "todaySteps", 99999, "steps", 99999, "reached", true);

        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("steps");
        assertEquals(10, kit.get("todaySteps"), "只认服务端解密出来的数");
        assertEquals(false, kit.get("reached"));
        assertEquals(0, view.get("score"));
    }

    @Test
    void staleWeRunDayCannotEarnScoreOrWriteSession() {
        GameRuntimeSession session=running(STEPS_CONFIG,"{}");arm(session);armWeRun(6200);
        lastCipher=WeRunFixtures.encryptSteps(6200,now.getTime()/1000L-86400L);
        assertThrows(IllegalArgumentException.class,()->act(session,"stale-day","SUBMIT_STEPS",
                "code","js-code","encryptedData",lastCipher,"iv",WeRunFixtures.iv()));
        verify(sessionMapper,never()).updateStateCas(any(GameRuntimeSession.class));
    }

    @Test
    void futureWeRunTimestampCannotEarnScore() {
        GameRuntimeSession session=running(STEPS_CONFIG,"{}");arm(session);armWeRun(6200);
        lastCipher=WeRunFixtures.encryptSteps(6200,now.getTime()/1000L+60L);
        assertThrows(IllegalArgumentException.class,()->act(session,"future-day","SUBMIT_STEPS",
                "code","js-code","encryptedData",lastCipher,"iv",WeRunFixtures.iv()));
    }

    // ==================== 今日城市签 ====================

    private static final String SIGN_CONFIG = "{\"schemaVersion\":1,\"dailySign\":{\"enabled\":true,"
            + "\"signer\":\"猫向导\",\"poems\":[[\"今天的签\"],[\"另一条签\"],[\"第三条签\"]]}}";

    @Test
    void 城市签同一天重复领是幂等的() {
        GameRuntimeSession session = running(SIGN_CONFIG, "{}");
        arm(session);

        Map<String, Object> first = act(session, "sign-1", "CLAIM_DAILY_SIGN");
        Map<?, ?> firstKit = (Map<?, ?>) ((Map<?, ?>) first.get("playKit")).get("dailySign");
        Object firstLines = firstKit.get("lines");

        // 用户重进页面再点一次很正常,不该看见失败,也不该换一条签
        session.setVersion(1);
        session.setStateJson(String.valueOf(session.getStateJson()));
        Map<String, Object> second = act(session, "sign-2", "CLAIM_DAILY_SIGN");
        Map<?, ?> secondKit = (Map<?, ?>) ((Map<?, ?>) second.get("playKit")).get("dailySign");
        assertEquals(firstLines, secondKit.get("lines"));
    }

    @Test
    void 负控_签文池不能整池下发() {
        GameRuntimeSession session = running(SIGN_CONFIG, "{}");
        arm(session);

        Map<String, Object> view = act(session, "sign-peek", "CLAIM_DAILY_SIGN");

        String dumped = String.valueOf(view);
        assertFalse(dumped.contains("poems"), dumped);
        // 三条里只应出现被选中的那一条
        int appeared = 0;
        for (String poem : new String[]{"今天的签", "另一条签", "第三条签"}) {
            if (dumped.contains(poem)) appeared++;
        }
        assertEquals(1, appeared, "只能给已领的那一条:" + dumped);
    }

    // ==================== 时段限定 ====================

    private static final String WINDOW_CONFIG = "{\"schemaVersion\":1,"
            + "\"timeWindow\":{\"enabled\":true,\"openFrom\":\"23:00\",\"openTo\":\"01:00\"}}";

    @Test
    void 负控_不在开放时段不许开局() {
        now = at(2026, 8, 26, 12, 0);   // 中午,窗口是 23:00–01:00
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(WINDOW_CONFIG));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        assertTrue(e.getMessage().contains("还没到开放时间"), e.getMessage());
    }

    @Test
    void 跨夜窗口在零点两侧都算开放() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(WINDOW_CONFIG));
        when(sessionMapper.insert(any())).thenAnswer(invocation -> {
            ((GameRuntimeSession) invocation.getArgument(0)).setId(SESSION_ID);
            return 1;
        });

        now = at(2026, 8, 26, 23, 30);   // 零点前
        assertEquals("RUNNING", service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null).get("status"));

        now = at(2026, 8, 27, 0, 30);    // 零点后,仍在同一个窗口里
        assertEquals("RUNNING", service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null).get("status"));
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

    private CmsMemberTemplate template(String config) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson(config);
        return template;
    }
}
