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

    @Test void missingPersistedRunRejectsInsteadOfFallingBackToAnIndividualSession() {
        when(mapper.selectStoryState(50L, "RUN", 50L)).thenReturn(null);
        GameSessionException unavailable = assertThrows(GameSessionException.class, () -> runtime.querySharedRouteState(7L, 80L, 70L));
        assertEquals("GAME_CONFIG_INVALID", unavailable.getErrorCode());
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

    private ObjectNode originalContent(long source, long template, String title) {
        ObjectNode content = json.createObjectNode().put("contentSnapshotVersion", 1).put("templateId", template);
        content.putObject("nodePresentation").put("id", source).put("topicId", 80L).put("templateId", template);
        content.putObject("templatePresentation").put("title", title);
        return content;
    }

    @Test void originalContentQueryDoesNotRefreshTimersLockWriteOrShareMutableConfig() throws Exception {
        ObjectNode config = (ObjectNode) json.readTree(session.getModuleConfigSnapshotJson());
        config.putObject("nodeAnswers").set("11", originalContent(11L, 31L, "原题"));
        config.putObject("nodeConfigs").putObject("11"); config.put("journeyStory","");
        session.setModuleConfigSnapshotJson(config.toString());
        String oldRun = run.getVariablesJson(), oldTeam = team.getVariablesJson(), oldConfig = session.getModuleConfigSnapshotJson();
        ObjectNode first = (ObjectNode) runtime.querySharedRouteSnapshot(7L, 80L, 70L, 11L);
        assertEquals(2, first.path("authorityEpoch").asInt());
        assertEquals("game:50", first.path("runtimeInstance").asText());
        ((ObjectNode) first.path("nodeAnswers").path("11").path("templatePresentation")).put("title", "mutable caller");
        assertEquals("原题", runtime.querySharedRouteSnapshot(7L, 80L, 70L, 11L)
                .path("nodeAnswers").path("11").path("templatePresentation").path("title").asText());
        assertEquals(oldRun, run.getVariablesJson()); assertEquals(oldTeam, team.getVariablesJson());
        assertEquals(oldConfig, session.getModuleConfigSnapshotJson()); assertEquals(4, session.getRevision());
        verify(mapper, times(2)).selectSessionByActivity(70L); verify(mapper, times(2)).selectActiveTeamId(70L, 7L);
        verify(mapper, times(2)).selectStoryState(50L, "RUN", 50L); verify(mapper, times(2)).selectStoryState(50L, "TEAM", 90L);
        verify(teams, times(2)).hasPaidRegistration(7L, 2, 70L); verify(activities, times(2)).selectCmsActivityById(70L);
        verifyNoMoreInteractions(mapper, teams, activities);
    }

    private void mappedQuests(boolean active) throws Exception {
        ObjectNode config = (ObjectNode) json.readTree(session.getModuleConfigSnapshotJson());
        config.putObject("nodeAnswers").set("101", originalContent(101L, 31L, "第一原题"));
        config.with("nodeAnswers").set("102", originalContent(102L, 32L, "第二原题"));
        config.putObject("nodeConfigs").putObject("101"); config.with("nodeConfigs").putObject("102"); config.put("journeyStory","");
        ObjectNode gameplay = (ObjectNode) config.path("themeConfig").path("gameplayRuntime");
        gameplay.putArray("quests").addObject().put("questKey", "q1").put("nodeId", 101L).put("locationKey", "desk");
        gameplay.withArray("quests").addObject().put("questKey", "q2").put("nodeId", 102L).put("locationKey", "desk");
        session.setModuleConfigSnapshotJson(config.toString());
        ObjectNode control = (ObjectNode) json.readTree(run.getVariablesJson());
        control.putObject("bindings").putObject("desk").put("nodeId", 11L).put("version", 4L);
        run.setVariablesJson(control.toString());
        TopicRouteEngine.State state = json.readValue(team.getVariablesJson(), TopicRouteEngine.State.class);
        if (active) state.getThemeRuntime().putObject("gameplay").put("activeQuest", "q2");
        team.setVariablesJson(json.writeValueAsString(state));
        com.chengyinhub.business.domain.GameStationRun station = new com.chengyinhub.business.domain.GameStationRun();
        station.setNodeId(11L); when(mapper.selectStation(50L, 11L)).thenReturn(station);
    }

    @Test void currentOriginalQuestMappingDoesNotBecomeTheOtherSourcesContent() throws Exception {
        mappedQuests(true);
        com.fasterxml.jackson.databind.JsonNode snapshot = runtime.querySharedRouteSnapshot(7L, 80L, 70L, 11L);
        com.fasterxml.jackson.databind.JsonNode answer = com.chengyinhub.business.service.support.NodeJourneySnapshot.answerFromSnapshot(snapshot, 11L);
        assertEquals(102L, answer.path("nodePresentation").path("id").asLong());
        assertEquals(32L, answer.path("templateId").asLong());
        assertEquals("第二原题", answer.path("templatePresentation").path("title").asText());
        assertEquals(2, snapshot.path("authorityEpoch").asInt());
    }

    @Test void originalContentCarriesTheActiveQuestIdentityThroughTheRealAnswerGuard() throws Exception {
        mappedQuests(true);
        TopicRouteEngine.State state = json.readValue(team.getVariablesJson(), TopicRouteEngine.State.class);
        state.getThemeRuntime().with("gameplay").put("activeQuestReceiptId", 73L);
        team.setVariablesJson(json.writeValueAsString(state));
        String stored = team.getVariablesJson();
        var routes = mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        when(routes.queryNodeContentSnapshot(7L, 80L, 70L, 11L))
                .thenAnswer(call -> runtime.querySharedRouteSnapshot(7L, 80L, 70L, 11L));
        var context = new com.chengyinhub.web.service.play.PlayNodeContext();
        ReflectionTestUtils.setField(context, "topicRouteRuntimeService", routes);
        var original = new com.chengyinhub.web.service.play.PlayOriginalRun(null, 50L, "game:50:quest:73", 2);
        assertEquals(32L, context.frozenAnswer(7L, 80L, 70L, 11L, original).path("templateId").asLong());
        assertThrows(IllegalStateException.class, () -> context.frozenAnswer(7L, 80L, 70L, 11L,
                new com.chengyinhub.web.service.play.PlayOriginalRun(null, 50L, "game:50:quest:72", 2)));
        assertThrows(IllegalStateException.class, () -> context.frozenAnswer(7L, 80L, 70L, 11L,
                new com.chengyinhub.web.service.play.PlayOriginalRun(null, 50L, "game:50:quest:73", 1)));
        assertThrows(IllegalStateException.class, () -> context.frozenAnswer(7L, 80L, 70L, 11L,
                new com.chengyinhub.web.service.play.PlayOriginalRun(null, 50L, "", 2)));
        assertEquals(stored, team.getVariablesJson());
        verify(mapper, never()).updateStoryStateCas(any());
        verify(mapper, never()).selectSessionByActivityForUpdate(anyLong());
    }

    @Test void originalContentUsesTheSameActiveOperationIdentityWithoutRefreshingIt() throws Exception {
        mappedQuests(false);
        TopicRouteEngine.State state = json.readValue(team.getVariablesJson(), TopicRouteEngine.State.class);
        state.getThemeRuntime().putObject("activeOperation").put("nodeId", 11L)
                .put("sourceNodeId", 101L).put("receiptId", 74L).put("epoch", 2);
        team.setVariablesJson(json.writeValueAsString(state));
        String stored = team.getVariablesJson();
        var snapshot = runtime.querySharedRouteSnapshot(7L, 80L, 70L, 11L);
        assertEquals("game:50:operation:74", snapshot.path("runtimeInstance").asText());
        assertEquals(2, snapshot.path("authorityEpoch").asInt());
        assertEquals(31L, snapshot.path("nodeAnswers").path("11").path("templateId").asLong());
        assertEquals(stored, team.getVariablesJson());
        verify(mapper, never()).updateStoryStateCas(any());
    }

    @Test void ambiguousMappedSourceIsRejectedInsteadOfSelectingAFutureQuest() throws Exception {
        mappedQuests(false);
        GameSessionException denied = assertThrows(GameSessionException.class,
                () -> runtime.querySharedRouteSnapshot(7L, 80L, 70L, 11L));
        assertEquals("GAME_ROUTE_LOCKED", denied.getErrorCode());
        verify(mapper, never()).selectStation(anyLong(), anyLong());
    }

    @Test void contentQueryKeepsMissingRunAndFrozenTeamGuards() {
        when(mapper.selectStoryState(50L, "RUN", 50L)).thenReturn(null);
        assertEquals("GAME_CONFIG_INVALID", assertThrows(GameSessionException.class,
                () -> runtime.querySharedRouteSnapshot(7L, 80L, 70L, 11L)).getErrorCode());
        when(mapper.selectStoryState(50L, "RUN", 50L)).thenReturn(run);
        when(mapper.selectActiveTeamId(70L, 7L)).thenReturn(91L);
        assertEquals("GAME_TEAM_REQUIRED", assertThrows(GameSessionException.class,
                () -> runtime.querySharedRouteSnapshot(7L, 80L, 70L, 11L)).getErrorCode());
        verify(mapper, never()).updateStoryStateCas(any());
        verify(mapper, never()).insertStoryState(any());
    }

    @Test void sharedClueLabelsUseOriginalCompleteDeclarationsInsteadOfLiveRegistry() throws Exception {
        ObjectNode config = (ObjectNode) json.readTree(session.getModuleConfigSnapshotJson());
        com.chengyinhub.business.domain.ThemeStateKey clue = new com.chengyinhub.business.domain.ThemeStateKey();
        clue.setStateKey("clue.original"); clue.setKeyType("flag"); clue.setScope("run"); clue.setLabel("原线索");
        clue.setTopicId(80L);
        com.chengyinhub.business.service.support.JourneyInitialState.freezeKeys(config, java.util.List.of(clue),
                com.chengyinhub.business.domain.JourneyRules.parse(config.path("journeyRules").toString()),80L);
        assertEquals(1, config.path("stateKeySnapshotVersion").asInt());
        session.setModuleConfigSnapshotJson(config.toString());
        com.chengyinhub.business.mapper.ThemeStateKeyMapper live = mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class);
        ReflectionTestUtils.setField(runtime, "themeStateKeyMapper", live);
        when(live.selectByTopicId(80L)).thenThrow(new AssertionError("clue labels cannot reinterpret an original session"));
        java.util.Map<?, ?> labels = ReflectionTestUtils.invokeMethod(runtime, "clueLabels", session);
        assertNotNull(labels); assertEquals("原线索", labels.get("clue.original"));
        verifyNoInteractions(live);
        assertEquals(config.toString(), session.getModuleConfigSnapshotJson());
    }
}
