package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayNote;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayNoteMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 1 成败汇合点:act() 把判定型玩法的对/错汇报给旅程运行时写 sys.passed.{nodeId}。
 *
 * <p>正控:答对 → recordPassed(true);答错 → recordPassed(false)。
 * 负控:非判定动作(留言)一次都不写 —— 判据不是「没判错 = 判对」,而是「动作属于判定型且成败有值」。
 */
class AdvancedGamePassedHookTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;

    private static final String QA_CONFIG = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\","
            + "\"title\":\"题\",\"options\":[{\"id\":\"a\",\"label\":\"甲\",\"correct\":false},"
            + "{\"id\":\"b\",\"label\":\"乙\",\"correct\":true}],\"xp\":5}}";
    private static final String HIDDEN_CONFIG = "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,"
            + "\"title\":\"找\",\"imageUrl\":\"https://x/y.png\","
            + "\"hotspots\":[{\"id\":\"h1\",\"label\":\"一\",\"x\":0.2,\"y\":0.2,\"r\":0.1},"
            + "{\"id\":\"h2\",\"label\":\"二\",\"x\":0.8,\"y\":0.8,\"r\":0.1},"
            + "{\"id\":\"h3\",\"label\":\"三\",\"x\":0.5,\"y\":0.5,\"r\":0.1}],\"xp\":5}}";
    private static final String NOTE_CONFIG = "{\"schemaVersion\":1,\"note\":{\"enabled\":true,"
            + "\"title\":\"留一句\",\"prompt\":\"写点什么\",\"maxLength\":40}}";

    private GameRuntimeSessionMapper sessionMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private PlayTeamMapper teamMapper;
    private PlayAttemptLogMapper attemptMapper;
    private PlayNoteMapper playNoteMapper;
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        teamMapper = mock(PlayTeamMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        playNoteMapper = mock(PlayNoteMapper.class);
        topicRouteRuntimeService = mock(ITopicRouteRuntimeService.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "playNoteMapper", playNoteMapper);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", topicRouteRuntimeService);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(playNoteMapper.insert(any(PlayNote.class))).thenReturn(1);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @Test
    void correctQaWritesPassedTrue() {
        GameRuntimeSession session = runningSession(QA_CONFIG, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionId", "b");

        service.act(MEMBER_ID, 51L, 0, "qa-right", "SUBMIT_QA", payload);

        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "qa-right", true);
    }

    @Test
    void wrongQaWritesPassedFalse() {
        GameRuntimeSession session = runningSession(QA_CONFIG, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionId", "a");

        service.act(MEMBER_ID, 51L, 0, "qa-wrong", "SUBMIT_QA", payload);

        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "qa-wrong", false);
    }

    @Test
    void hiddenObjectHitWritesPassedTrue() {
        GameRuntimeSession session = runningSession(HIDDEN_CONFIG, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("x", 0.2);
        hit.put("y", 0.2);

        service.act(MEMBER_ID, 51L, 0, "hidden-hit", "SUBMIT_HIDDEN_OBJECT", hit);

        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "hidden-hit", true);
    }

    /** 负控:留言是非判定型动作,不写 sys.passed(既不是 1 也不是 0)。 */
    @Test
    void nonJudgedNoteWritesNothing() {
        GameRuntimeSession session = runningSession(NOTE_CONFIG, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "到此一游");

        service.act(MEMBER_ID, 51L, 0, "note-1", "SUBMIT_NOTE", payload);

        verify(topicRouteRuntimeService, never())
                .recordPassed(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
    }

    /** 旅程运行时缺失时留言照旧成功(非旅程主题零变化,不炸)。 */
    @Test
    void missingJourneyRuntimeKeepsJudgedActionWorking() {
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", null);
        GameRuntimeSession session = runningSession(QA_CONFIG, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionId", "b");

        assertDoesNotThrow(() -> service.act(MEMBER_ID, 51L, 0, "qa-no-journey", "SUBMIT_QA", payload));
    }

    private GameRuntimeSession runningSession(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(51L);
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
        session.setStartedAt(new Date(NOW));
        return session;
    }
}
