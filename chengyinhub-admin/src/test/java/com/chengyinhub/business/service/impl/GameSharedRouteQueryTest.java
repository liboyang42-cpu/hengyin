package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.GameStoryState;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Query-port boundary only; no real storage or caller query/entry integration acceptance. */
class GameSharedRouteQueryTest {
    private final ObjectMapper json = new ObjectMapper();
    private GameSessionRuntimeServiceImpl runtime;
    private GameSessionRuntimeMapper mapper;
    private CmsActivityMapper activities;
    private PlayTeamMapper teams;
    private GameSession session;
    private CmsActivity activity;
    private GameStoryState run, team;

    @BeforeEach void fixture() throws Exception {
        runtime = new GameSessionRuntimeServiceImpl();
        mapper = mock(GameSessionRuntimeMapper.class); activities = mock(CmsActivityMapper.class);
        teams = mock(PlayTeamMapper.class);
        ReflectionTestUtils.setField(runtime, "runtimeMapper", mapper);
        ReflectionTestUtils.setField(runtime, "activityMapper", activities);
        ReflectionTestUtils.setField(runtime, "playTeamMapper", teams);
        ReflectionTestUtils.setField(runtime, "clock", (AdvancedGameClock) () -> {
            throw new AssertionError("query must not evaluate or refresh gameplay time");
        });
        session = new GameSession(); session.setId(50L); session.setActivityId(70L);
        session.setTopicId(80L); session.setRevision(4); session.setStatus(GameSession.RUNNING);
        ObjectNode config = json.createObjectNode().put("playMode", "ROUTE").put("routeMode", "BRANCH_GRAPH");
        config.set("journeyRules", json.readTree(GenericRouteRuntimeServiceTest.rules()));
        ObjectNode gameplay = config.putObject("themeConfig").putObject("gameplayRuntime");
        gameplay.put("runtimeScope", "TEAM"); gameplay.putObject("survival").put("zeroHpPolicy", "WAIT");
        session.setModuleConfigSnapshotJson(config.toString());
        activity = new CmsActivity(); activity.setId(70L); activity.setTopicId(80L);
        activity.setStatus(1L); activity.setDelFlag(0);
        run = new GameStoryState(); run.setOwnerType("RUN"); run.setOwnerId(50L); run.setRevision(1);
        run.setVariablesJson("{\"operationStatus\":\"RUNNING\",\"authorityMode\":\"ONLINE\",\"authorityEpoch\":2}");
        TopicRouteEngine.State stored = new TopicRouteEngine.State();
        stored.setStatus("ACTIVE"); stored.setCurrentNodeId(11L); stored.setRecommendedNodeId(12L);
        stored.getNodeStates().put(11L, "PLAYABLE"); stored.getVariables().put("counter.temperature", 5);
        stored.getThemeRuntime().putObject("zeroHp").put("resolved", false).put("deadlineAt", 1L);
        team = new GameStoryState(); team.setOwnerType("TEAM"); team.setOwnerId(90L); team.setRevision(7);
        team.setVariablesJson(json.writeValueAsString(stored));
        when(mapper.selectSessionByActivity(70L)).thenReturn(session);
        when(activities.selectCmsActivityById(70L)).thenReturn(activity);
        when(teams.hasPaidRegistration(7L, 2, 70L)).thenReturn(1);
        when(mapper.selectActiveTeamId(70L, 7L)).thenReturn(90L);
        when(mapper.selectStoryState(50L, "RUN", 50L)).thenReturn(run);
        when(mapper.selectStoryState(50L, "TEAM", 90L)).thenReturn(team);
    }

    @Test void persistedProjectionDoesNotRefreshExpiredTimersLockOrWrite() {
        String oldRun = run.getVariablesJson(), oldTeam = team.getVariablesJson();
        for (int i = 0; i < 2; i++) {
            TopicRouteState view = runtime.querySharedRouteState(7L, 80L, 70L);
            assertEquals(50L, view.getSessionId()); assertEquals(4, view.getVersion());
            assertEquals("BRANCH_GRAPH", view.getRouteMode()); assertEquals("ACTIVE", view.getStatus());
            assertEquals(11L, view.getCurrentNodeId()); assertEquals(12L, view.getRecommendedNodeId());
            assertEquals("PLAYABLE", view.getNodeStates().get(11L));
            assertEquals(5, view.getAttributes().get(0).get("value"));
        }
        assertEquals(oldRun, run.getVariablesJson()); assertEquals(oldTeam, team.getVariablesJson());
        assertEquals(4, session.getRevision()); assertEquals(1, run.getRevision()); assertEquals(7, team.getRevision());
        verify(mapper, times(2)).selectSessionByActivity(70L);
        verify(mapper, times(2)).selectActiveTeamId(70L, 7L);
        verify(mapper, times(2)).selectStoryState(50L, "RUN", 50L);
        verify(mapper, times(2)).selectStoryState(50L, "TEAM", 90L);
        verify(teams, times(2)).hasPaidRegistration(7L, 2, 70L);
        verify(activities, times(2)).selectCmsActivityById(70L);
        verifyNoMoreInteractions(mapper, teams, activities);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void absentOrNonSharedSessionReturnsNullWithoutInitialization(boolean nonShared) {
        if (nonShared) session.setModuleConfigSnapshotJson("{\"playMode\":\"ROUTE\",\"themeConfig\":{}}");
        else when(mapper.selectSessionByActivity(70L)).thenReturn(null);
        assertNull(runtime.querySharedRouteState(7L, 80L, 70L));
        verify(mapper).selectSessionByActivity(70L);
        verifyNoMoreInteractions(mapper); verifyNoInteractions(teams, activities);
    }

    @Test void missingPersistedRunDoesNotInventAnOnlineLedger() {
        when(mapper.selectStoryState(50L, "RUN", 50L)).thenReturn(null);
        assertNull(runtime.querySharedRouteState(7L, 80L, 70L));
        verify(mapper).selectSessionByActivity(70L); verify(mapper).selectActiveTeamId(70L, 7L);
        verify(mapper).selectStoryState(50L, "RUN", 50L);
        verify(teams).hasPaidRegistration(7L, 2, 70L); verify(activities).selectCmsActivityById(70L);
        verifyNoMoreInteractions(mapper, teams, activities);
    }

    @Test void mismatchedTopicRejectsBeforePlayerOrOwnerReads() {
        GameSessionException denied = assertThrows(GameSessionException.class, () -> runtime.querySharedRouteState(7L, 81L, 70L));
        assertEquals("GAME_FORBIDDEN_SCOPE", denied.getErrorCode());
        verify(mapper).selectSessionByActivity(70L);
        verifyNoMoreInteractions(mapper); verifyNoInteractions(teams, activities);
    }

    @Test void currentPaidPlayerIsRequiredBeforeAnySharedLedgerRead() {
        when(teams.hasPaidRegistration(7L, 2, 70L)).thenReturn(0);
        GameSessionException denied = assertThrows(GameSessionException.class, () -> runtime.querySharedRouteState(7L, 80L, 70L));
        assertEquals("GAME_FORBIDDEN_SCOPE", denied.getErrorCode());
        verify(mapper).selectSessionByActivity(70L); verify(activities).selectCmsActivityById(70L);
        verify(teams).hasPaidRegistration(7L, 2, 70L);
        verifyNoMoreInteractions(mapper, teams, activities);
    }

    @Test void currentTeamIsRequiredBeforeAnySharedLedgerRead() {
        when(mapper.selectActiveTeamId(70L, 7L)).thenReturn(null);
        GameSessionException denied = assertThrows(GameSessionException.class, () -> runtime.querySharedRouteState(7L, 80L, 70L));
        assertEquals("GAME_TEAM_REQUIRED", denied.getErrorCode());
        verify(mapper).selectSessionByActivity(70L); verify(mapper).selectActiveTeamId(70L, 7L);
        verify(activities).selectCmsActivityById(70L); verify(teams).hasPaidRegistration(7L, 2, 70L);
        verifyNoMoreInteractions(mapper, teams, activities);
    }

    @Test void currentPlayerCannotReadAnotherTeamsFrozenOwner() {
        when(mapper.selectActiveTeamId(70L, 7L)).thenReturn(91L);
        GameSessionException denied = assertThrows(GameSessionException.class, () -> runtime.querySharedRouteState(7L, 80L, 70L));
        assertEquals("GAME_TEAM_REQUIRED", denied.getErrorCode());
        verify(mapper).selectSessionByActivity(70L); verify(mapper).selectActiveTeamId(70L, 7L);
        verify(mapper).selectStoryState(50L, "RUN", 50L); verify(mapper).selectStoryState(50L, "TEAM", 91L);
        verify(activities).selectCmsActivityById(70L); verify(teams).hasPaidRegistration(7L, 2, 70L);
        verifyNoMoreInteractions(mapper, teams, activities);
    }

    @Test void cancelledActivityStillRejectsWithoutReadingSharedLedgers() {
        activity.setCancelTime(new Date(0));
        GameSessionException denied = assertThrows(GameSessionException.class, () -> runtime.querySharedRouteState(7L, 80L, 70L));
        assertEquals("GAME_ACTIVITY_CANCELLED", denied.getErrorCode());
        verify(mapper).selectSessionByActivity(70L); verify(activities).selectCmsActivityById(70L);
        verifyNoMoreInteractions(mapper, activities); verifyNoInteractions(teams);
    }
}
