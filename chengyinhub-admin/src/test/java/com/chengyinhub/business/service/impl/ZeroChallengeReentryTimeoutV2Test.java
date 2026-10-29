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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * CL-31 / F-R2S-06: re-entering a zero-HP challenge whose server deadline passed only records
 * one FAILED timeout result on the same session; it never inserts or restarts an attempt.
 * A still-running attempt is returned as is.
 */
class ZeroChallengeReentryTimeoutV2Test {
    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L, ACTIVITY_ID = 9L, TOPIC_ID = 19L, NODE_ID = 29L, TEMPLATE_ID = 39L;
    private static final String CONFIG = "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":["
            + "{\"id\":\"a\",\"label\":\"A\",\"weight\":10},{\"id\":\"b\",\"label\":\"B\",\"weight\":1}]}}";

    private GameRuntimeSessionMapper sessionMapper;
    private PlayTeamMapper teamMapper;
    private ITopicRouteRuntimeService route;
    private AdvancedGameRuntimeServiceImpl service;
    private GameRuntimeSession session;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        teamMapper = mock(PlayTeamMapper.class);
        route = mock(ITopicRouteRuntimeService.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "verifiedWeRunService", com.chengyinhub.business.it.R5WeRunFixture.service());
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        // 移植分支 A包当前资格按 activity→registration 锁读当前行(CurrentPlayRegistration)，夹具同 PersonalGameplayRuntimeServiceTest。
        CmsRegistrationMapper currentRegistrations = mock(CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(currentRegistrations);
        ReflectionTestUtils.setField(service, "registrationMapper", currentRegistrations);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID); node.setTopicId(TOPIC_ID); node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID); activity.setTopicId(TOPIC_ID); activity.setProductType(1);
        activity.setStartDate(new Date(NOW - 60_000)); activity.setEndDate(new Date(NOW + 3_600_000));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY_ID)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);

        session = new GameRuntimeSession();
        session.setId(51L); session.setActivityId(ACTIVITY_ID); session.setTopicId(TOPIC_ID); session.setNodeId(NODE_ID);
        session.setTemplateId(TEMPLATE_ID); session.setOwnerType(GameRuntimeSession.OWNER_PLAYER); session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(CONFIG);
        session.setStateJson("{\"originalRouteSessionId\":88,\"zeroChallengeInstance\":\"one\",\"challenge\":{\"instanceId\":\"one\"},\"draws\":[{\"id\":\"keep\"}]}");
        session.setStatus(GameRuntimeSession.STATUS_RUNNING); session.setVersion(3); session.setScore(5);
        session.setStartedAt(new Date(NOW - 120_000)); session.setPlayInstanceId("challenge:one");

        when(route.genericPlayer(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(true);
        when(route.canPlay(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(true);
        when(route.gameplaySessionId(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(88L);
        when(route.gameplayInstance(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn("challenge:one");
        when(route.zeroChallenge(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(
                new ObjectMapper().createObjectNode().put("instanceId", "one").put("deadlineAt", NOW + 60_000));
        when(sessionMapper.selectOwnerByInstance(ACTIVITY_ID, TOPIC_ID, NODE_ID, GameRuntimeSession.OWNER_PLAYER,
                MEMBER_ID, "challenge:one")).thenReturn(session);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
    }

    @Test
    void reentryAfterDeadlineRecordsOneFailedTimeoutWithoutRestartingTheAttempt() {
        session.setDeadlineAt(new Date(NOW - 1));
        when(route.recordZeroChallengeResult(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(new ObjectMapper().createObjectNode().put("instanceId", "one").put("retry", true).put("success", false));
        when(sessionMapper.updateStateCas(any())).thenReturn(1);

        Map<String, Object> view = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);

        assertEquals(51L, ((Number) view.get("sessionId")).longValue());
        assertEquals("FAILED", view.get("status"));
        assertEquals(GameRuntimeSession.STATUS_FAILED, session.getStatus());
        assertEquals(5, session.getScore(), "超时只记失败，不清空本局结果");
        assertTrue(session.getStateJson().contains("\"keep\""));
        verify(route).recordZeroChallengeResult(eq(MEMBER_ID), eq(TOPIC_ID), eq(ACTIVITY_ID), eq(NODE_ID),
                eq("challenge:one"), eq("timeout:51"), eq(false), isNull(), eq(true));
        verify(sessionMapper).updateStateCas(session);
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).restartTerminalCas(any());
    }

    @Test
    void reentryBeforeDeadlineReturnsTheRunningSessionAsIs() {
        session.setDeadlineAt(new Date(NOW + 30_000));
        String original = session.getStateJson();

        Map<String, Object> view = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);

        assertEquals(51L, ((Number) view.get("sessionId")).longValue());
        assertEquals("RUNNING", view.get("status"));
        assertEquals(3, view.get("version"));
        assertEquals(original, session.getStateJson());
        verify(route, never()).recordZeroChallengeResult(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).restartTerminalCas(any());
    }
}
