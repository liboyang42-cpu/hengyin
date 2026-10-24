package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.support.ThemeRuntimeEngine;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Common projection regression; no Spring, storage, provider or command-admission acceptance. */
class TopicRouteOpeningTerminalProjectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final ThemeRuntimeEngine themeEngine = new ThemeRuntimeEngine();
    private final TopicRouteNarrativeProgress projection = new TopicRouteNarrativeProgress(
            topics, json, new TopicRouteEngine(), new TopicCompletionRulePolicy(), themeEngine);

    @AfterEach void noDatabaseReadForFrozenOpening() {
        verifyNoInteractions(topics);
    }

    @ParameterizedTest
    @CsvSource({"ABANDONED, ENDED, 2", "COMPLETED, COMPLETED, 1"})
    void persistedTerminalCannotBeRevivedByUnfinishedOpening(
            String reason, String expectedState, int persistedStatus) throws Exception {
        TopicRouteEngine.State state = state();
        // Use the same actual terminal producer as themeAction(END), then its persisted status.
        ObjectNode config = json.createObjectNode();
        themeEngine.terminal(config, state, JourneyRules.parse(null), reason, 1234L);
        assertEquals(expectedState, state.getStatus());
        assertTrue(state.getThemeRuntime().has("result"));
        TopicRouteSession session = session(state, persistedStatus);
        TopicRouteState view = view(session, state);
        // A legacy linear projection can also carry derived live progress before appendOpening.
        view.setStatus("ACTIVE"); view.setCurrentNodeId(2L); view.setRecommendedNodeId(2L);
        String originalState = json.writeValueAsString(state);
        String originalSnapshot = session.getConfigSnapshotJson();

        projection.appendOpening(view, session, state);

        assertEquals(expectedState, view.getStatus()); // Old projection returns ACTIVE here.
        assertNull(view.getCurrentNodeId());
        assertNull(view.getRecommendedNodeId());
        assertEquals(TopicRouteState.COMPLETED, view.getNodeStates().get(7L));
        assertEquals(TopicRouteState.HIDDEN, view.getNodeStates().get(1L));
        assertEquals(TopicRouteState.HIDDEN, view.getNodeStates().get(2L));
        assertFalse(view.getNodeStates().containsValue(TopicRouteState.PLAYABLE));
        assertOpeningMetadata(view, false);
        assertEquals(77L, view.getSessionId());
        assertEquals(6, view.getVersion());
        assertEquals(originalState, json.writeValueAsString(state));
        assertEquals(originalState, session.getStateJson());
        assertEquals(originalSnapshot, session.getConfigSnapshotJson());
        assertEquals(persistedStatus, session.getStatus());
        assertEquals(6, session.getVersion());
    }

    @Test void runningOpeningStillProjectsOnlyItsNextPlayableNode() throws Exception {
        TopicRouteEngine.State state = state();
        TopicRouteSession session = session(state, TopicRouteSession.STATUS_RUNNING);
        TopicRouteState view = view(session, state);
        String originalState = session.getStateJson();

        projection.appendOpening(view, session, state);

        assertEquals("ACTIVE", view.getStatus());
        assertEquals(1L, view.getCurrentNodeId());
        assertEquals(1L, view.getRecommendedNodeId());
        assertEquals(TopicRouteState.PLAYABLE, view.getNodeStates().get(1L));
        assertEquals(TopicRouteState.HIDDEN, view.getNodeStates().get(2L));
        assertEquals(TopicRouteState.COMPLETED, view.getNodeStates().get(7L));
        assertOpeningMetadata(view, false);
        assertEquals(originalState, json.writeValueAsString(state));
        assertEquals(originalState, session.getStateJson());
    }

    @Test void completedRouteAwaitingOpeningConfirmationStillHasRunningOpeningProjection() throws Exception {
        TopicRouteEngine.State state = state();
        state.setStatus(TopicRouteState.COMPLETED);
        state.setCurrentNodeId(null); state.setRecommendedNodeId(null);
        state.getCompletedNodeIds().add(1L);
        state.getNodeStates().put(1L, TopicRouteState.COMPLETED);
        TopicRouteSession session = session(state, TopicRouteSession.STATUS_RUNNING);
        TopicRouteState view = view(session, state);
        String originalState = session.getStateJson();

        projection.appendOpening(view, session, state);

        assertEquals("ACTIVE", view.getStatus());
        assertNull(view.getCurrentNodeId());
        assertNull(view.getRecommendedNodeId());
        assertFalse(view.getNodeStates().containsValue(TopicRouteState.PLAYABLE));
        assertOpeningMetadata(view, true);
        assertEquals(TopicRouteSession.STATUS_RUNNING, session.getStatus());
        assertEquals(TopicRouteState.COMPLETED, state.getStatus());
        assertEquals(originalState, json.writeValueAsString(state));
        assertEquals(originalState, session.getStateJson());
    }

    private TopicRouteEngine.State state() {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.setStatus("ACTIVE"); state.setCurrentNodeId(2L); state.setRecommendedNodeId(2L);
        state.getVariables().put("sys.hp", 3); state.getVariables().put("sys.luck", 2);
        state.getVariables().put("sys.actions", 1);
        state.getCompletedNodeIds().add(7L);
        state.getNodeStates().put(7L, TopicRouteState.COMPLETED);
        state.getNodeStates().put(1L, TopicRouteState.HIDDEN);
        state.getNodeStates().put(2L, TopicRouteState.PLAYABLE);
        return state;
    }

    private TopicRouteSession session(TopicRouteEngine.State state, int status) throws Exception {
        ObjectNode snapshot = json.createObjectNode();
        ObjectNode opening = snapshot.putObject("openingChapter");
        opening.put("chapterId", 90L).put("name", "original opening");
        var blocks = opening.putArray("blocks");
        blocks.addObject().put("type", "text").put("content", "visible intro");
        blocks.addObject().put("type", "text").put("content", "hidden without tag")
                .putObject("when").put("value", "tag.secret");
        blocks.addObject().put("type", "node").put("nodeId", 1L);
        blocks.addObject().put("type", "text").put("content", "after opening node");
        TopicRouteSession session = new TopicRouteSession();
        session.setId(77L); session.setVersion(6); session.setStatus(status);
        session.setConfigSnapshotJson(snapshot.toString());
        session.setStateJson(json.writeValueAsString(state));
        return session;
    }

    private TopicRouteState view(TopicRouteSession session, TopicRouteEngine.State state) {
        TopicRouteState view = new TopicRouteState();
        view.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        view.setSessionId(session.getId()); view.setVersion(session.getVersion());
        view.setStatus(state.getStatus()); view.setCurrentNodeId(state.getCurrentNodeId());
        view.setRecommendedNodeId(state.getRecommendedNodeId());
        view.setNodeStates(new LinkedHashMap<>(state.getNodeStates()));
        return view;
    }

    private void assertOpeningMetadata(TopicRouteState view, boolean ready) {
        var opening = json.valueToTree(view.getOpening());
        assertEquals(90L, opening.path("chapterId").asLong());
        assertEquals("original opening", opening.path("name").asText());
        assertFalse(opening.path("complete").asBoolean(true));
        assertEquals(ready, opening.path("ready").asBoolean());
        assertFalse(opening.path("themeComplete").asBoolean(true));
        assertEquals("FULL", opening.path("rewardPolicy").asText());
        assertTrue(opening.path("blocks").toString().contains("visible intro"));
        assertFalse(opening.path("blocks").toString().contains("hidden without tag"));
        assertEquals(ready, opening.path("blocks").toString().contains("after opening node"));
    }
}
