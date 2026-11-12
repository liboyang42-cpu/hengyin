package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.CurrentPlayTestFixtures;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production act/QA/progress/receipt wiring with mock storage; not SQL or client acceptance. */
class AccusationRuntimeReceiptTest {
    private static final long MEMBER = 41L, TOPIC = 71L, ACTIVITY = 81L, NODE = 900403L, SESSION = 51L;
    private static final long NOW = 1_800_000_000_000L;
    private static final String INSTANCE = "ct07-receipt-run";
    private static final String RECORD_AS = "线索未齐，我选择现在结案（作者自写）";
    private final ObjectMapper mapper = new ObjectMapper();
    private AdvancedGameRuntimeServiceImpl service;
    private GameRuntimeSessionMapper sessions;
    private CmsTopicNodeMapper nodes;
    private PlayerNodeProgressMapper progress;
    private PlayAttemptLogMapper attempts;
    private ITopicRouteRuntimeService route;
    private GameRuntimeSession stored;
    private final Map<String, PlayAttemptLog> receipts = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        sessions = mock(GameRuntimeSessionMapper.class);
        nodes = mock(CmsTopicNodeMapper.class);
        progress = mock(PlayerNodeProgressMapper.class);
        attempts = mock(PlayAttemptLogMapper.class);
        route = mock(ITopicRouteRuntimeService.class);
        when(route.canPlay(MEMBER, TOPIC, ACTIVITY, NODE)).thenReturn(true);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CurrentPlayTestFixtures.install(activities, registrations);
        service = new AdvancedGameRuntimeServiceImpl();
        AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessions);
        ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        ReflectionTestUtils.setField(service, "progressMapper", progress);
        ReflectionTestUtils.setField(service, "attemptMapper", attempts);
        ReflectionTestUtils.setField(service, "activityMapper", activities);
        ReflectionTestUtils.setField(service, "registrationMapper", registrations);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setProductType(1);
        activity.setStartDate(new Date(NOW - 60_000));
        activity.setEndDate(new Date(NOW + 3_600_000));
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(node(900401L), node(900402L), node(NODE)));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(done(900401L), done(900402L)));

        stored = new GameRuntimeSession();
        stored.setId(SESSION);
        stored.setConfigSnapshotJson(config(false).toString());
        stored.setActivityId(ACTIVITY);
        stored.setTopicId(TOPIC);
        stored.setNodeId(NODE);
        stored.setTemplateId(91L);
        stored.setPlayInstanceId(INSTANCE);
        stored.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        stored.setOwnerId(MEMBER);
        stored.setStateJson("{}");
        stored.setStatus(GameRuntimeSession.STATUS_RUNNING);
        stored.setVersion(0);
        stored.setScore(0);
        stored.setStartedAt(new Date(NOW));
        // Reads return detached objects so an unpersisted proposed score cannot masquerade as a write.
        when(sessions.selectAccessibleById(SESSION, MEMBER)).thenAnswer(call -> copy(stored));
        when(sessions.updateStateCas(any())).thenAnswer(call -> {
            stored = copy(call.getArgument(0));
            stored.setVersion(stored.getVersion() + 1);
            return 1;
        });
        when(attempts.selectByIdempotencyKey(anyString())).thenAnswer(call -> receipts.get(call.getArgument(0)));
        when(attempts.insertPlayAttemptLog(any())).thenAnswer(call -> {
            PlayAttemptLog log = call.getArgument(0);
            assertNull(receipts.putIfAbsent(log.getIdempotencyKey(), log));
            return 1;
        });
    }

    @Test
    void routeAccessStillRejectsBeforeAccusationOrReceipt() {
        when(route.canPlay(MEMBER, TOPIC, ACTIVITY, NODE)).thenReturn(false);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> submit("route-locked", "suspect_01"));
        assertEquals("请先完成上一站", failure.getMessage());
        verify(progress, never()).selectPlayerNodeProgressList(any());
        assertNoWrite();
    }

    @Test
    void normalCorrectUsesOriginalQaAndScopedProgressBeforeStoringReceipt() throws Exception {
        JsonNode receipt = submit("normal-correct", "suspect_01");
        assertDecision(receipt, "CORRECT", true, false, true);
        assertEquals("待填：正确指认结局文案", receipt.at("/accusation/screen").asText());
        assertEquals(10, stored.getScore());
        assertTrue(mapper.readTree(stored.getStateJson()).at("/qa/passed").asBoolean());
        assertFalse(mapper.readTree(stored.getStateJson()).at("/accusation/early").asBoolean());
        assertStoredReceipt(receipt);
        verify(progress).selectPlayerNodeProgressList(argThat(probe ->
                Long.valueOf(MEMBER).equals(probe.getMemberId()) && Long.valueOf(TOPIC).equals(probe.getTopicId())
                        && Long.valueOf(ACTIVITY).equals(probe.getActivityId()) && INSTANCE.equals(probe.getPlayInstanceId())));
        verify(nodes).selectCmsTopicNodeList(argThat(probe -> Long.valueOf(TOPIC).equals(probe.getTopicId())
                && Long.valueOf(1).equals(probe.getStatus())));
        InOrder order = inOrder(progress, sessions, attempts);
        order.verify(progress).selectPlayerNodeProgressList(any());
        order.verify(sessions).updateStateCas(any());
        order.verify(attempts).insertPlayAttemptLog(any());
        verify(route).recordPassed(eq(MEMBER), eq(TOPIC), eq(ACTIVITY), eq(NODE), anyString(), eq(true));
    }

    @Test
    void normalWrongExhaustsOriginalQaAttemptAndStoresSeparateLoseScreen() throws Exception {
        JsonNode receipt = submit("normal-wrong", "suspect_02");
        assertDecision(receipt, "FAILED", false, false, true);
        assertEquals("待填：错误指认结局文案", receipt.at("/accusation/screen").asText());
        // 原QA耗尽次数后可收场；能收场不代表答对，失败结局与零分仍保留。
        assertTrue(receipt.at("/readyForBase").asBoolean());
        assertTrue(mapper.readTree(stored.getStateJson()).at("/qa/finished").asBoolean());
        assertEquals(0, stored.getScore());
        assertFalse(mapper.readTree(stored.getStateJson()).at("/qa/passed").asBoolean());
        assertStoredReceipt(receipt);
        verify(route).recordMistake(MEMBER, TOPIC, ACTIVITY, NODE);
        verify(route).recordPassed(eq(MEMBER), eq(TOPIC), eq(ACTIVITY), eq(NODE), anyString(), eq(false));
    }

    @Test
    void earlyCorrectKeepsAuthorRecordAsAndIgnoresClientVerdictAndProgressClaims() throws Exception {
        stored.setConfigSnapshotJson(config(true).toString());
        // Current-node and duplicate rows cannot replace the missing other node.
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(done(NODE), done(900401L), done(900401L)));
        JsonNode receipt = submit("early-correct", "suspect_01");
        assertDecision(receipt, "CORRECT", true, true, true);
        assertEquals(RECORD_AS, receipt.at("/accusation/result/recordCode").asText());
        assertEquals(10, stored.getScore());
        assertStoredReceipt(receipt);
    }

    @Test
    void progressQueryFailureDoesNotPersistVerdictScoreOrSideEffects() {
        when(progress.selectPlayerNodeProgressList(any())).thenThrow(new IllegalStateException("progress unavailable"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> submit("unknown-progress", "suspect_01"));
        assertTrue(failure.getMessage().contains("结果待确认"));
        assertNoWrite();
    }

    @Test
    void nodeQueryFailureDoesNotInventProgressOrPersistATerminalReceipt() {
        when(nodes.selectCmsTopicNodeList(any())).thenThrow(new IllegalStateException("nodes unavailable"));
        assertThrows(IllegalStateException.class, () -> submit("unknown-nodes", "suspect_01"));
        verifyNoInteractions(progress);
        assertNoWrite();
    }

    @Test
    void disallowedEarlyAccusationDoesNotRelaxTheGateOrPersistRewards() {
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(done(900401L)));
        assertThrows(IllegalArgumentException.class, () -> submit("early-refused", "suspect_01"));
        assertNoWrite();
    }

    @Test
    void sameRequestReplaysStoredEarlyVerdictWithoutRejudgingOrRepeatingSideEffects() throws Exception {
        stored.setConfigSnapshotJson(config(true).toString());
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(done(900401L)));
        JsonNode original = submit("replay", "suspect_01");
        String savedState = stored.getStateJson();
        // The persisted command remains authoritative even when progress lookup is now unavailable.
        when(progress.selectPlayerNodeProgressList(any())).thenThrow(new IllegalStateException("now unavailable"));
        JsonNode replay = submit("replay", "suspect_01");
        assertEquals(mapper.readTree(mapper.writeValueAsString(original)),
                mapper.readTree(mapper.writeValueAsString(replay)));
        assertDecision(replay, "CORRECT", true, true, true);
        assertEquals(RECORD_AS, replay.at("/accusation/result/recordCode").asText());
        assertEquals(savedState, stored.getStateJson());
        assertEquals(10, stored.getScore());
        assertEquals(1, stored.getVersion());
        verify(progress).selectPlayerNodeProgressList(any());
        verify(sessions).updateStateCas(any());
        verify(attempts).insertPlayAttemptLog(any());
        verify(route).recordPassed(eq(MEMBER), eq(TOPIC), eq(ACTIVITY), eq(NODE), anyString(), eq(true));
        verify(route).recordChoice(MEMBER, TOPIC, ACTIVITY, NODE, "qa", "suspect_01");
        verify(route, never()).recordMistake(anyLong(), anyLong(), anyLong(), anyLong());
        assertThrows(ServiceException.class, () -> submit("replay", "suspect_02"));
        assertStoredReceipt(replay);
    }

    private ObjectNode config(boolean earlyAllowed) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/questo-theme-presets/T08-multi-clue-investigation/nodes/node-03.json")) {
            assertNotNull(input);
            ObjectNode config = (ObjectNode) mapper.readTree(input);
            // Exercise bounded wrong endings and nonzero reward using the original, unchanged QA answers.
            ((ObjectNode) config.path("qa")).put("maxTries", 1).put("xp", 10);
            ((ObjectNode) config.path("accusation").path("earlyAccuse"))
                    .put("allowed", earlyAllowed).put("recordAs", RECORD_AS);
            return config;
        }
    }

    private JsonNode submit(String key, String option) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("optionId", option);
        payload.put("correct", false);
        payload.put("early", false);
        payload.put("verdict", "CLIENT_OVERRIDE");
        payload.put("clueGateSatisfied", true);
        return mapper.valueToTree(service.act(MEMBER, SESSION, 0, key, "SUBMIT_QA", payload));
    }

    private void assertDecision(JsonNode receipt, String verdict, boolean correct, boolean early, boolean terminal) {
        JsonNode result = receipt.at("/accusation/result");
        assertEquals(verdict, result.path("verdict").asText());
        assertEquals(correct, result.path("correct").asBoolean());
        assertEquals(early, result.path("early").asBoolean());
        assertEquals(terminal, result.path("terminal").asBoolean());
    }

    private void assertStoredReceipt(JsonNode receipt) throws Exception {
        assertEquals(1, receipts.size());
        PlayAttemptLog log = receipts.values().iterator().next();
        assertEquals(MEMBER, log.getMemberId());
        assertEquals(TOPIC, log.getTopicId());
        assertEquals(NODE, log.getNodeId());
        assertEquals(ACTIVITY, log.getActivityId());
        assertTrue(log.getRequestFingerprint().matches("[0-9a-f]{64}"));
        assertEquals(mapper.readTree(mapper.writeValueAsString(receipt)), mapper.readTree(log.getResultJson()));
    }

    private void assertNoWrite() {
        verify(sessions, never()).updateStateCas(any());
        verify(attempts, never()).insertPlayAttemptLog(any());
        verify(route, never()).recordPassed(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
        verify(route, never()).recordMistake(anyLong(), anyLong(), anyLong(), anyLong());
        verify(route, never()).recordChoice(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(route, never()).recordOutcome(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyInt());
        assertEquals("{}", stored.getStateJson());
        assertEquals(GameRuntimeSession.STATUS_RUNNING, stored.getStatus());
        assertEquals(0, stored.getScore());
        assertEquals(0, stored.getVersion());
        assertNull(stored.getCompletedAt());
        assertTrue(receipts.isEmpty());
    }

    private GameRuntimeSession copy(GameRuntimeSession row) {
        return mapper.convertValue(row, GameRuntimeSession.class);
    }

    private CmsTopicNode node(long id) {
        CmsTopicNode row = new CmsTopicNode();
        row.setId(id);
        row.setTopicId(TOPIC);
        row.setStatus(1L);
        return row;
    }

    private PlayerNodeProgress done(long nodeId) {
        PlayerNodeProgress row = new PlayerNodeProgress();
        row.setMemberId(MEMBER);
        row.setTopicId(TOPIC);
        row.setActivityId(ACTIVITY);
        row.setPlayInstanceId(INSTANCE);
        row.setNodeId(nodeId);
        row.setStatus(1);
        return row;
    }
}
