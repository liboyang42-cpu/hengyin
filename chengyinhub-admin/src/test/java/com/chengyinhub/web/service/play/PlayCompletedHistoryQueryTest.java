package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayJourneyReadMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiPlayProgressController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayCompletedHistoryQueryTest {
    private static final long MEMBER = 7L, TOPIC = 10L, ACTIVITY = 20L, SESSION = 30L, NODE = 40L;
    private final ObjectMapper json = new ObjectMapper();
    private final PlayHistoryQueryApplication application = new PlayHistoryQueryApplication();
    private final TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
    private final PlayJourneyReadMapper receipts = mock(PlayJourneyReadMapper.class);
    private final GameRuntimeSessionMapper games = mock(GameRuntimeSessionMapper.class);
    private final PlayCompletionFacts facts = mock(PlayCompletionFacts.class);
    private final ICmsTopicService topics = mock(ICmsTopicService.class);
    private final ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
    private TopicRouteSession session;
    private ObjectNode snapshot;
    private ObjectNode state;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(application, "historySessionMapper", sessions);
        ReflectionTestUtils.setField(application, "historyReadMapper", receipts);
        ReflectionTestUtils.setField(application, "historyGameMapper", games);
        ReflectionTestUtils.setField(application, "playCompletionFacts", facts);
        ReflectionTestUtils.setField(application, "cmsTopicService", topics);
        ReflectionTestUtils.setField(application, "topicRouteRuntimeService", runtime);
        session = new TopicRouteSession(); session.setId(SESSION); session.setMemberId(MEMBER);
        session.setActivityId(ACTIVITY); session.setTopicId(TOPIC); session.setPlayInstanceId("replay:original");
        session.setConfigVersion(8L); session.setVersion(3); session.setStatus(TopicRouteSession.STATUS_RUNNING);
        when(sessions.selectAccessibleById(SESSION, MEMBER)).thenReturn(session);
        CmsTopic topic = new CmsTopic(); topic.setId(TOPIC);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsTopicNode delisted = new CmsTopicNode(); delisted.setId(NODE); delisted.setTopicId(TOPIC);
        delisted.setStatus(0L); delisted.setName("new author edit must not appear");
        when(facts.historyReadableNode(NODE, topic)).thenReturn(delisted);
        snapshot = json.createObjectNode();
        snapshot.putObject("journeyRules"); snapshot.putObject("nodeConfigs"); snapshot.put("journeyStory", "");
        ObjectNode answer = snapshot.putObject("nodeAnswers").putObject(String.valueOf(NODE));
        answer.put("contentSnapshotVersion", 1); answer.putNull("templateId");
        answer.put("questionAnswer", "SECRET ANSWER");
        answer.putObject("nodePresentation").put("id", NODE).put("topicId", TOPIC)
                .put("chapterId", 50L).put("name", "original node").put("description", "original prose");
        answer.putObject("templatePresentation").put("storyText", "original story");
        ArrayNode chapters = snapshot.putArray("thoughtChapters");
        ObjectNode chapter = chapters.addObject().put("chapterId", 50L);
        chapter.putArray("nodeIds").add(NODE); chapter.putArray("requiredBefore");
        ArrayNode blocks = chapter.putArray("blocks");
        blocks.addObject().put("type", "text").put("content", "before").put("secret", "never expose");
        blocks.addObject().put("type", "node").put("nodeId", NODE).put("locationRequired", true);
        blocks.addObject().put("type", "text").put("content", "after");
        blocks.addObject().put("type", "thought").put("thoughtKey", "unearned");
        blocks.addObject().put("type", "text").put("beat", "enter").put("field", "a").put("content", "SECRET DIALOG ANSWER");
        blocks.addObject().put("type", "text").put("content", "unselected branch")
                .putObject("when").put("op", "HAS_TAG").put("value", "unearned");
        ObjectNode future = chapters.addObject().put("chapterId", 51L);
        future.putArray("nodeIds").add(NODE + 1); future.putArray("requiredBefore").add(NODE);
        future.putArray("blocks").addObject().put("type", "text").put("content", "FUTURE CONTENT");
        state = json.createObjectNode(); state.putArray("completedNodeIds").add(NODE);
        state.putArray("tags"); state.putObject("variables");
    }

    private AjaxResult read() {
        session.setConfigSnapshotJson(snapshot.toString()); session.setStateJson(state.toString());
        return application.history(MEMBER, ACTIVITY, TOPIC, SESSION);
    }

    private JsonNode data(AjaxResult response) {
        assertTrue(response.isSuccess(), response.toString());
        return json.valueToTree(response.get("data"));
    }

    @Test
    void controllerUsesAuthenticatedActorAndOriginalSessionArguments() {
        ApiPlayProgressController controller = spy(new ApiPlayProgressController());
        PlayHistoryQueryApplication queries = mock(PlayHistoryQueryApplication.class);
        ReflectionTestUtils.setField(controller, "playHistoryQueryApplication", queries);
        doReturn(MEMBER).when(controller).getAppUserId();
        AjaxResult expected = AjaxResult.success();
        when(queries.history(MEMBER, ACTIVITY, TOPIC, SESSION)).thenReturn(expected);
        assertSame(expected, controller.history(ACTIVITY, TOPIC, SESSION));
        verify(queries).history(MEMBER, ACTIVITY, TOPIC, SESSION);
        verifyNoMoreInteractions(queries);
    }

    @Test
    void frozenCompletedChapterIsSeparateFromPlayableNodesAndFutureSecrets() {
        JsonNode result = data(read());
        assertTrue(result.path("readOnly").asBoolean());
        assertEquals(SESSION, result.path("routeSessionId").asLong());
        assertEquals(8L, result.path("configVersion").asLong());
        assertEquals(1, result.path("chapters").size());
        assertEquals(50L, result.path("chapters").get(0).path("chapterId").asLong());
        assertEquals(3, result.path("chapters").get(0).path("blocks").size());
        assertEquals("after", result.path("chapters").get(0).path("blocks").get(2).path("content").asText());
        assertFalse(result.path("chapters").get(0).path("blocks").get(1).has("locationRequired"));
        assertEquals("original node", result.path("nodes").get(0).path("name").asText());
        assertEquals("original story", result.path("nodes").get(0).path("storyText").asText());
        assertFalse(result.toString().contains("SECRET"));
        assertFalse(result.toString().contains("FUTURE"));
        assertFalse(result.toString().contains("unearned"));
        assertFalse(result.toString().contains("new author edit"));
        assertFalse(result.toString().contains("unselected branch"));
    }

    @Test
    void historyWireIdentitiesRemainExactDecimalStringsBeyondJavascriptIntegerRange() throws Exception {
        long large = 9007199254740993L;
        session.setId(large); session.setConfigVersion(large + 2);
        session.setConfigSnapshotJson(snapshot.toString()); session.setStateJson(state.toString());
        when(sessions.selectAccessibleById(large, MEMBER)).thenReturn(session);
        GameRuntimeSession game = game("replay:original", MEMBER, GameRuntimeSession.STATUS_COMPLETED);
        game.setId(large + 4);
        when(games.selectPlayerSessionsByTopic(ACTIVITY, TOPIC, MEMBER)).thenReturn(List.of(game));
        JsonNode result = data(application.history(MEMBER, ACTIVITY, TOPIC, large));
        assertEquals("9007199254740993", result.path("routeSessionId").textValue());
        assertEquals("9007199254740995", result.path("configVersion").textValue());
        assertEquals(String.valueOf(ACTIVITY), result.path("activityId").textValue());
        assertEquals(String.valueOf(TOPIC), result.path("topicId").textValue());
        assertEquals(String.valueOf(NODE), result.path("nodes").get(0).path("nodeId").textValue());
        assertEquals("50", result.path("nodes").get(0).path("chapterId").textValue());
        assertEquals("50", result.path("chapters").get(0).path("chapterId").textValue());
        assertEquals(String.valueOf(NODE), result.path("chapters").get(0).path("blocks").get(1).path("nodeId").textValue());
        assertEquals("9007199254740997", result.path("nodes").get(0).path("results").get(0).path("gameSessionId").textValue());
        assertEquals(result, json.readTree(json.writeValueAsString(result)));
    }

    @Test
    void ownerAndExplicitOriginalSessionScopeFailClosed() {
        assertFalse(application.history(null, ACTIVITY, TOPIC, SESSION).isSuccess());
        assertFalse(application.history(MEMBER, ACTIVITY, TOPIC, null).isSuccess());
        assertFalse(application.history(MEMBER + 1, ACTIVITY, TOPIC, SESSION).isSuccess());
        assertFalse(application.history(MEMBER, ACTIVITY + 1, TOPIC, SESSION).isSuccess());
        assertFalse(application.history(MEMBER, ACTIVITY, TOPIC + 1, SESSION).isSuccess());
        session.setMemberId(MEMBER + 1);
        assertFalse(read().isSuccess());
        verifyNoInteractions(topics, facts, games, receipts, runtime);
    }

    @Test
    void onlyEarnedThoughtAndExactTagAreReadable() {
        state.with("variables").put("thought.unearned", 0);
        state.withArray("tags").add("unearned");
        JsonNode result = data(read());
        assertEquals(5, result.path("chapters").get(0).path("blocks").size());
        assertTrue(result.toString().contains("unselected branch"));
        assertFalse(result.toString().contains("SECRET"));
        state.putArray("completedNodeIds");
        assertTrue(data(read()).path("chapters").isEmpty());
    }

    @Test
    void originalReceiptsAndCompletedGamesExcludeOtherRunOwnerAndRawPayload() {
        TopicRouteAction original = receipt(MEMBER, SESSION);
        TopicRouteAction foreignOwner = receipt(MEMBER + 1, SESSION);
        TopicRouteAction foreignRun = receipt(MEMBER, SESSION + 1);
        when(receipts.selectActionsBySession(SESSION)).thenReturn(List.of(original, foreignOwner, foreignRun));
        GameRuntimeSession game = game("replay:original", MEMBER, GameRuntimeSession.STATUS_COMPLETED);
        when(games.selectPlayerSessionsByTopic(ACTIVITY, TOPIC, MEMBER)).thenReturn(List.of(game,
                game("replay:new", MEMBER, 1), game("replay:original", MEMBER + 1, 1), game("replay:original", MEMBER, 0)));
        JsonNode results = data(read()).path("nodes").get(0).path("results");
        assertEquals(2, results.size());
        assertTrue(results.get(0).path("passed").asBoolean());
        assertEquals(91, results.get(1).path("score").asInt());
        assertFalse(results.toString().contains("SECRET"));
        assertFalse(results.toString().contains("stateJson"));
    }

    @Test
    void governanceIncompleteBlocksOpeningAndEmptyChaptersCannotUnlockHistory() {
        ObjectNode chapter = (ObjectNode) snapshot.path("thoughtChapters").get(0);
        chapter.withArray("blocks").addObject().put("type", "node").put("nodeId", NODE + 1);
        assertTrue(data(read()).path("chapters").isEmpty());
        chapter.putArray("blocks"); chapter.putArray("nodeIds");
        assertTrue(data(read()).path("chapters").isEmpty());
        chapter.put("opening", true);
        assertTrue(data(read()).path("chapters").isEmpty());
        state.withArray("tags").add("sys.opening.done");
        assertEquals(1, data(read()).path("chapters").size());
        when(facts.topicHistoryBlocked(any())).thenReturn(true);
        assertFalse(read().isSuccess());
    }

    @Test
    void oldOrInvalidSnapshotsNeverReadCurrentProse() {
        snapshot.remove(List.of("thoughtChapters", "nodeAnswers"));
        JsonNode old = data(read());
        assertFalse(old.path("narrativeAvailable").asBoolean());
        assertTrue(old.path("chapters").isEmpty()); assertTrue(old.path("nodes").isEmpty());
        session.setConfigSnapshotJson("broken"); session.setStateJson(state.toString());
        assertFalse(application.history(MEMBER, ACTIVITY, TOPIC, SESSION).isSuccess());
        verifyNoInteractions(runtime);
    }

    @Test
    void repeatedReadsDoNotMutateStateOrCallRuntimeCommandsRewardsOrMapperWrites() {
        String originalState = state.toString(), originalSnapshot = snapshot.toString();
        assertEquals(data(read()), data(read()));
        assertEquals(originalState, session.getStateJson()); assertEquals(originalSnapshot, session.getConfigSnapshotJson());
        assertEquals(3, session.getVersion()); assertEquals(TopicRouteSession.STATUS_RUNNING, session.getStatus());
        verify(sessions, times(2)).selectAccessibleById(SESSION, MEMBER);
        verify(receipts, times(2)).selectActionsBySession(SESSION);
        verify(games, times(2)).selectPlayerSessionsByTopic(ACTIVITY, TOPIC, MEMBER);
        verifyNoMoreInteractions(sessions, receipts, games);
        verifyNoInteractions(runtime);
    }

    @Test
    void actualQuestStartReceiptLinksCompletedTaskInstanceAndOnlySavedDisplayOutcomes() {
        freezeQuest("original", NODE);
        TopicRouteAction start = gameplayReceipt("QUEST_START", "gameplay:start-original", "activeQuest", "original");
        GameRuntimeSession original = game("quest:2cde2a34046d0fee0d66df4de5211328", MEMBER, 1);
        original.setStateJson("""
                {"gameplayDecision":{"outcome":"PASS","selection":{"answer":"SECRET"}},
                 "coinFlip":{"flipped":true,"side":"HEADS","action":"SECRET AUTHOR CONFIG"},
                 "diceRoll":{"rolled":true,"pips":[3,5],"sum":8,"answer":"SECRET"},
                 "draws":[{"id":"picked","label":"Original card","content":"Original selected result","answer":"SECRET"}],
                 "qa":{"correctAnswer":"SECRET"},"originalNodeContent":{"questionAnswer":"SECRET"}}
                """);
        when(receipts.selectActionsBySession(SESSION)).thenReturn(List.of(start));
        when(games.selectPlayerSessionsByTopic(ACTIVITY, TOPIC, MEMBER)).thenReturn(List.of(original));
        JsonNode results = data(read()).path("nodes").get(0).path("results");
        assertEquals(1, results.size());
        JsonNode outcome = results.get(0).path("outcomes");
        assertEquals("PASS", outcome.path("outcome").asText());
        assertEquals("HEADS", outcome.path("coinSide").asText());
        assertEquals(8, outcome.path("diceRoll").path("sum").asInt());
        assertEquals(2, outcome.path("diceRoll").path("pips").size());
        assertEquals("Original selected result", outcome.path("draws").get(0).path("content").asText());
        assertFalse(results.toString().contains("SECRET"));
        assertFalse(results.toString().contains("selection"));
        verifyNoInteractions(runtime);
    }

    @Test
    void taskLinkExcludesAnotherInstanceOwnerNodeAndUnfinishedGame() {
        freezeQuest("original", NODE);
        TopicRouteAction start = gameplayReceipt("QUEST_START", "gameplay:start-original", "activeQuest", "original");
        TopicRouteAction foreign = gameplayReceipt("QUEST_START", "gameplay:start-other", "activeQuest", "original");
        foreign.setSessionId(SESSION + 1);
        TopicRouteAction foreignOwner = gameplayReceipt("QUEST_START", "gameplay:start-other", "activeQuest", "original");
        foreignOwner.setMemberId(MEMBER + 1);
        GameRuntimeSession wrongNode = game("quest:2cde2a34046d0fee0d66df4de5211328", MEMBER, 1);
        wrongNode.setNodeId(NODE + 1);
        when(receipts.selectActionsBySession(SESSION)).thenReturn(List.of(start, foreign, foreignOwner));
        when(games.selectPlayerSessionsByTopic(ACTIVITY, TOPIC, MEMBER)).thenReturn(List.of(
                game("quest:bcfaca5be6af4ab506f43f6403a1d0ce", MEMBER, 1),
                game("quest:2cde2a34046d0fee0d66df4de5211328:suffix", MEMBER, 1),
                game("quest:2cde2a34046d0fee0d66df4de5211328", MEMBER + 1, 1),
                game("quest:2cde2a34046d0fee0d66df4de5211328", MEMBER, 0), wrongNode));
        assertTrue(data(read()).path("nodes").get(0).path("results").isEmpty());
        start.setResponseJson("{\"sessionId\":31,\"settlement\":{\"activeQuest\":\"original\"}}");
        when(games.selectPlayerSessionsByTopic(ACTIVITY, TOPIC, MEMBER)).thenReturn(List.of(
                game("quest:2cde2a34046d0fee0d66df4de5211328", MEMBER, 1)));
        assertTrue(data(read()).path("nodes").get(0).path("results").isEmpty());
    }

    @Test
    void operationStartAndChallengeUseTheirExactSavedLinkage() {
        snapshot.putObject("themeConfig").putObject("gameplayRuntime").putArray("facilities")
                .addObject().put("facilityKey", "original").put("operationNodeId", NODE);
        TopicRouteAction start = gameplayReceipt("OPERATION_START", "gameplay:operation-original", "activeOperation", "original");
        GameRuntimeSession operation = game("operation:aaa5dbf3f442177666307d1ce9721d80", MEMBER, 1);
        GameRuntimeSession challenge = game("challenge:original-episode", MEMBER, 1);
        challenge.setStateJson("{\"originalRouteSessionId\":30,\"zeroChallengeInstance\":\"original-episode\"}");
        GameRuntimeSession foreign = game("challenge:another-episode", MEMBER, 1);
        foreign.setStateJson("{\"originalRouteSessionId\":31,\"zeroChallengeInstance\":\"another-episode\"}");
        when(receipts.selectActionsBySession(SESSION)).thenReturn(List.of(start));
        when(games.selectPlayerSessionsByTopic(ACTIVITY, TOPIC, MEMBER)).thenReturn(List.of(operation, challenge, foreign));
        assertEquals(2, data(read()).path("nodes").get(0).path("results").size());
    }

    @Test
    void realGameplayResponseWrapperMapsZeroNodeReceiptToFrozenTaskWithoutExposingView() {
        freezeQuest("original", NODE);
        TopicRouteAction settled = gameplayReceipt("QUEST_RESOLVE", "gameplay:resolve", "questKey", "original");
        ObjectNode view = (ObjectNode) parse(settled.getResponseJson());
        ((ObjectNode) view.path("settlement")).put("eventId", "gameplay:resolve").put("used", 2).put("actions", 3)
                .putObject("grants").put("SECRET ITEM", 1);
        view.put("hp", 99).put("passed", false).putObject("resultCard").put("future", "SECRET FUTURE");
        settled.setResponseJson(view.toString());
        TopicRouteAction fact = receipt(MEMBER, SESSION); fact.setOutcomeCode("TASK_FACT");
        when(receipts.selectActionsBySession(SESSION)).thenReturn(List.of(fact, settled));
        JsonNode results = data(read()).path("nodes").get(0).path("results");
        assertEquals(2, results.size());
        assertTrue(results.get(0).path("passed").asBoolean());
        assertEquals("QUEST_RESOLVE", results.get(1).path("outcomeCode").asText());
        assertEquals(2, results.get(1).path("used").asInt());
        assertEquals(3, results.get(1).path("actions").asInt());
        assertFalse(results.get(1).has("passed"));
        assertFalse(results.toString().contains("SECRET"));
        assertFalse(results.toString().contains("hp"));
    }

    @Test
    void productionTerminalDecisionShowsEarnedBranchPrefixWithoutUnchosenOrFutureContent() {
        ObjectNode chapter = (ObjectNode) snapshot.path("thoughtChapters").get(0);
        chapter.withArray("nodeIds").add(NODE + 2);
        ArrayNode blocks = chapter.withArray("blocks");
        blocks.addObject().put("type", "node").put("nodeId", NODE + 2);
        blocks.addObject().put("type", "text").put("content", "UNCHOSEN SECRET");
        snapshot.put("routeMode", "BRANCH_GRAPH").put("startNodeId", NODE);
        snapshot.putArray("terminalNodeIds").add(NODE);
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.State saved = engine.advance(snapshot.toString(), engine.initial(snapshot.toString()),
                new NodeOutcome(NODE, "COMPLETED", "original-terminal"), 1L).getState();
        state = json.valueToTree(saved);
        JsonNode result = data(read());
        assertEquals(1, result.path("chapters").size());
        assertTrue(result.path("chapters").get(0).path("complete").asBoolean());
        assertEquals(3, result.path("chapters").get(0).path("blocks").size());
        assertTrue(result.toString().contains("after"));
        assertFalse(result.toString().contains("UNCHOSEN"));
        assertFalse(result.toString().contains("FUTURE"));
        state.putArray("decisionLog"); // A terminal status/allDone alone is not chapter evidence.
        assertTrue(data(read()).path("chapters").isEmpty());
    }

    @Test
    void productionSavedBranchExitReadsPastChapterWhileNextChapterRemainsFuture() {
        ObjectNode chapter = (ObjectNode) snapshot.path("thoughtChapters").get(0);
        chapter.withArray("nodeIds").add(NODE + 2);
        chapter.withArray("blocks").addObject().put("type", "node").put("nodeId", NODE + 2);
        chapter.withArray("blocks").addObject().put("type", "text").put("content", "UNCHOSEN SECRET");
        snapshot.put("routeMode", "BRANCH_GRAPH").put("startNodeId", NODE);
        snapshot.putArray("terminalNodeIds").add(NODE + 1);
        ObjectNode edge = snapshot.putArray("edges").addObject().put("id", "chosen-edge")
                .put("fromNodeId", NODE).put("toNodeId", NODE + 1).put("priority", 1);
        edge.putObject("trigger").put("type", "CHOICE").put("outcomeCode", "COMPLETED");
        TopicRouteEngine engine = new TopicRouteEngine();
        state = json.valueToTree(engine.advance(snapshot.toString(), engine.initial(snapshot.toString()),
                new NodeOutcome(NODE, "COMPLETED", "original-exit"), 1L).getState());
        JsonNode result = data(read());
        assertEquals("ACTIVE", state.path("status").asText());
        assertEquals(1, result.path("chapters").size());
        assertFalse(result.toString().contains("UNCHOSEN"));
        assertFalse(result.toString().contains("FUTURE"));
        edge.put("id", "different-edge"); // An unmatched route log cannot grant history.
        assertTrue(data(read()).path("chapters").isEmpty());
    }

    @Test
    void persistedEndedChapterAllowsAnyNOptionalNodesButNeverUnlocksFutureChapter() {
        ObjectNode chapter = (ObjectNode) snapshot.path("thoughtChapters").get(0);
        chapter.withArray("nodeIds").add(NODE + 2);
        chapter.withArray("blocks").addObject().put("type", "node").put("nodeId", NODE + 2);
        chapter.withArray("blocks").addObject().put("type", "text").put("content", "OPTIONAL SECRET");
        ObjectNode binding = snapshot.putObject("themeConfig").putArray("chapters").addObject().put("index", 0);
        binding.set("binding", chapter.deepCopy());
        state.putObject("themeRuntime").putObject("chapters").putObject("0").put("ended", true);
        JsonNode result = data(read());
        assertEquals(1, result.path("chapters").size());
        assertFalse(result.toString().contains("OPTIONAL"));
        assertFalse(result.toString().contains("FUTURE"));
        binding.with("binding").putArray("nodeIds").add(NODE); // Unrelated binding cannot prove completion.
        assertTrue(data(read()).path("chapters").isEmpty());
    }

    private void freezeQuest(String key, long nodeId) {
        snapshot.putObject("themeConfig").putObject("gameplayRuntime").putArray("quests")
                .addObject().put("questKey", key).put("nodeId", nodeId).put("actKey", "first").put("settlementSlot", key);
    }

    private TopicRouteAction gameplayReceipt(String code, String actionId, String field, String key) {
        TopicRouteAction receipt = new TopicRouteAction(); receipt.setMemberId(MEMBER); receipt.setSessionId(SESSION);
        receipt.setNodeId(0L); receipt.setActionId(actionId); receipt.setReceiptId("gameplay-receipt:original");
        receipt.setOutcomeCode(code);
        // gameplayActionInternal persists themeViewOf + settlement; the HTTP AjaxResult envelope is not stored.
        ObjectNode response = json.createObjectNode().put("sessionId", SESSION).put("topicId", TOPIC)
                .put("activityId", ACTIVITY).put("runtimeScope", "PLAYER").put("version", 4).put("status", "ACTIVE");
        response.putObject("settlement").put(field, key);
        if ("QUEST_START".equals(code)) response.put("activeQuest", key);
        if ("OPERATION_START".equals(code)) response.putObject("activeOperation").put("operationKey", key).put("nodeId", NODE);
        receipt.setResponseJson(response.toString()); return receipt;
    }

    private JsonNode parse(String text) {
        try { return json.readTree(text); }
        catch (Exception invalid) { throw new AssertionError(invalid); }
    }

    private TopicRouteAction receipt(long owner, long run) {
        TopicRouteAction receipt = new TopicRouteAction(); receipt.setMemberId(owner); receipt.setSessionId(run);
        receipt.setNodeId(NODE); receipt.setReceiptId("original-receipt"); receipt.setOutcomeCode("PASSED");
        receipt.setResponseJson("{\"nodeId\":40,\"passed\":true}");
        return receipt;
    }

    private GameRuntimeSession game(String instance, long owner, int status) {
        GameRuntimeSession game = new GameRuntimeSession(); game.setId(60L); game.setActivityId(ACTIVITY);
        game.setTopicId(TOPIC); game.setNodeId(NODE); game.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        game.setOwnerId(owner); game.setPlayInstanceId(instance); game.setStatus(status); game.setScore(91);
        game.setStateJson("{\"answer\":\"SECRET\"}"); return game;
    }
}
