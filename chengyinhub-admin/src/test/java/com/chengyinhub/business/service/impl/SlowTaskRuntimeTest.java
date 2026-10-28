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
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨日慢任务(Figma「🌙 低压游戏循环 UX」)的运行期契约。
 *
 * 这块唯一会算错的地方是**跨日判定**,而算错的两个方向都很糟:
 *   · 判松了 → 当天就能领,「隔天解锁」这个玩法的全部意义没了;
 *   · 判紧了 → 睡一觉起来还领不了,玩家会觉得系统在赖账。
 */
class SlowTaskRuntimeTest {

    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;

    private static final String CONFIG = "{\"schemaVersion\":1,\"slowTask\":{\"enabled\":true,"
            + "\"title\":\"把这条街留到明天\",\"waitDays\":1,\"xp\":30,"
            + "\"unlockText\":\"明天此刻,这家店的老板会在门口摆一张椅子。\"}}";

    private GameRuntimeSessionMapper sessionMapper;
    private PlayAttemptLogMapper attemptMapper;
    private CmsMemberTemplateMapper templateMapper;
    private AdvancedGameRuntimeServiceImpl service;
    private Date now = at(2026, 8, 27, 23, 0);

    private static Date at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);

        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", mock(PlayTeamMapper.class));
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "weChatMiniSessionService", mock(IWeChatMiniSessionService.class));
        ReflectionTestUtils.setField(service, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> now);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) b -> 0);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID); node.setTopicId(TOPIC_ID); node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID); activity.setTopicId(TOPIC_ID);
        activity.setStartDate(at(2026, 8, 1, 0, 0));
        activity.setEndDate(at(2026, 9, 30, 23, 59));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson(CONFIG);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @Test
    void 开始之后当天不能领() {
        GameRuntimeSession s = running("{}");
        arm(s);

        act(s, "start-1", "START_SLOW_TASK");
        s.setVersion(1);
        s.setStateJson("{\"slowTask\":{\"startedDate\":\"2026-08-27\"}}");

        // 判松了 = 「隔天解锁」当场作废
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> act(s, "claim-early", "CLAIM_SLOW_TASK"));
        assertTrue(e.getMessage().contains("再等"), e.getMessage());
    }

    @Test
    void 睡一觉起来就能领_按天差不按24小时() {
        // 23:00 开始,次日 08:00 来领 —— 按小时算只有 9 小时,按天算是隔天。
        // 玩家理解的「隔天」是睡一觉起来,不是整整 24 小时,后者会让人觉得系统在赖账。
        GameRuntimeSession s = running("{\"slowTask\":{\"startedDate\":\"2026-08-27\"}}");
        arm(s);
        now = at(2026, 8, 28, 8, 0);

        Map<String, Object> view = act(s, "claim-1", "CLAIM_SLOW_TASK");

        assertEquals(30, view.get("score"));
        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("slowTask");
        assertEquals(true, kit.get("claimed"));
    }

    @Test
    void 负控_领过之后不能再领() {
        GameRuntimeSession s = running("{\"slowTask\":{\"startedDate\":\"2026-08-26\",\"claimed\":true}}");
        arm(s);
        now = at(2026, 8, 28, 8, 0);

        assertThrows(IllegalStateException.class, () -> act(s, "claim-2", "CLAIM_SLOW_TASK"));
    }

    @Test
    void 负控_没开始就领要被拒() {
        GameRuntimeSession s = running("{}");
        arm(s);
        assertThrows(IllegalStateException.class, () -> act(s, "claim-3", "CLAIM_SLOW_TASK"));
    }

    @Test
    void 重复开始是幂等的_不能靠反复点重置等待期() {
        GameRuntimeSession s = running("{\"slowTask\":{\"startedDate\":\"2026-08-26\"}}");
        arm(s);
        now = at(2026, 8, 27, 10, 0);

        act(s, "start-again", "START_SLOW_TASK");

        // 起始日期必须还是 26 号;被重置成 27 号的话,玩家每天点一次就永远领不到
        assertTrue(String.valueOf(s.getStateJson()).contains("2026-08-26"), s.getStateJson());
    }

    @Test
    void 负控_解锁内容在领取之前绝不下发() {
        GameRuntimeSession s = running("{\"slowTask\":{\"startedDate\":\"2026-08-27\"}}");
        arm(s);

        Map<String, Object> view = service.state(MEMBER_ID, SESSION_ID);

        // 提前给 = 把"明天的发现"今天就送出去,这个玩法的全部意义就没了
        assertFalse(String.valueOf(view).contains("老板会在门口摆一张椅子"), String.valueOf(view));
        Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("slowTask");
        assertEquals(1L, ((Number) kit.get("daysLeft")).longValue());
    }

    @Test
    void 领取之后才给解锁内容() {
        GameRuntimeSession s = running("{\"slowTask\":{\"startedDate\":\"2026-08-26\",\"claimed\":true}}");
        arm(s);
        now = at(2026, 8, 28, 8, 0);

        Map<String, Object> view = service.state(MEMBER_ID, SESSION_ID);

        assertTrue(String.valueOf(view).contains("老板会在门口摆一张椅子"), String.valueOf(view));
    }

    @Test
    void 负控_未领取慢任务不得放行基础通关() {
        GameRuntimeSession s = running("{\"slowTask\":{\"startedDate\":\"2026-08-27\"}}");
        arm(s);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(s);

        Map<String, Object> view = service.state(MEMBER_ID, SESSION_ID);
        assertEquals(false, view.get("readyForBase"), "等待中的慢任务不能绕过玩法闸");
        assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));

        s.setStateJson("{\"slowTask\":{\"startedDate\":\"2026-08-27\",\"claimed\":true}}");
        assertEquals(true, service.state(MEMBER_ID, SESSION_ID).get("readyForBase"));
    }

    @Test
    void 上海自然日不受JVM默认时区影响() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            GameRuntimeSession s = running("{\"slowTask\":{\"startedDate\":\"2026-08-27\"}}");
            arm(s);
            now = atShanghai(2026, 8, 28, 0, 30);

            Map<String, Object> view = act(s, "claim-shanghai-midnight", "CLAIM_SLOW_TASK");
            Map<?, ?> kit = (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("slowTask");
            assertEquals(true, kit.get("claimed"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // ==================== 脚手架 ====================

    private void arm(GameRuntimeSession session) {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
    }

    private Map<String, Object> act(GameRuntimeSession s, String key, String action) {
        when(attemptMapper.selectByIdempotencyKey(key)).thenReturn(null);
        return service.act(MEMBER_ID, SESSION_ID, s.getVersion(), key, action, Collections.emptyMap());
    }

    private GameRuntimeSession running(String state) {
        GameRuntimeSession s = new GameRuntimeSession();
        s.setId(SESSION_ID);
        s.setActivityId(ACTIVITY_ID);
        s.setTopicId(TOPIC_ID);
        s.setNodeId(NODE_ID);
        s.setTemplateId(TEMPLATE_ID);
        s.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        s.setOwnerId(MEMBER_ID);
        s.setConfigSnapshotJson(CONFIG);
        s.setStateJson(state);
        s.setStatus(GameRuntimeSession.STATUS_RUNNING);
        s.setVersion(0);
        s.setScore(0);
        s.setStartedAt(now);
        return s;
    }

    private static Date atShanghai(int y, int mo, int d, int h, int mi) {
        return at(y, mo, d, h, mi);
    }
}
