package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.route.NodeOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TopicRouteEngineTest {

    private static final String GRAPH = "{" +
            "\"schemaVersion\":1," +
            "\"startNodeId\":1," +
            "\"terminalNodeIds\":[4]," +
            "\"variables\":{\"clues\":0}," +
            "\"edges\":[" +
            "{\"id\":\"history\",\"fromNodeId\":1,\"toNodeId\":2,\"priority\":10," +
            " \"trigger\":{\"type\":\"PREFERENCE_RESULT\",\"outcomeCode\":\"HISTORY\"}," +
            " \"effects\":[{\"op\":\"INC\",\"var\":\"clues\",\"value\":1}]} ," +
            "{\"id\":\"art\",\"fromNodeId\":1,\"toNodeId\":3,\"priority\":10," +
            " \"trigger\":{\"type\":\"PREFERENCE_RESULT\",\"outcomeCode\":\"ART\"}}," +
            "{\"id\":\"merge-b\",\"fromNodeId\":2,\"toNodeId\":4,\"priority\":0," +
            " \"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}," +
            "{\"id\":\"merge-c\",\"fromNodeId\":3,\"toNodeId\":4,\"priority\":0," +
            " \"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}";

    @Test
    void twoSplitOneMergeUsesOnlyTheTrustedOutcomeAndCompletesAtTerminal() {
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.State state = engine.initial(GRAPH);

        TopicRouteEngine.Decision first = engine.advance(GRAPH, state, "PREFERENCE_RESULT",
                new NodeOutcome(1L, "HISTORY", "receipt-1"), 7L);
        assertEquals(2L, first.getState().getCurrentNodeId());
        assertEquals("COMPLETED", first.getState().getNodeStates().get(1L));
        assertEquals("PLAYABLE", first.getState().getNodeStates().get(2L));
        assertEquals("HIDDEN", first.getState().getNodeStates().get(3L));
        assertEquals(1, first.getState().getVariables().get("clues").intValue());

        TopicRouteEngine.Decision second = engine.advance(GRAPH, first.getState(),
                new NodeOutcome(2L, "COMPLETED", "receipt-2"), 7L);
        assertEquals(4L, second.getState().getCurrentNodeId());

        TopicRouteEngine.Decision terminal = engine.advance(GRAPH, second.getState(),
                new NodeOutcome(4L, "COMPLETED", "receipt-3"), 7L);
        assertEquals("COMPLETED", terminal.getState().getStatus());
        assertNull(terminal.getState().getCurrentNodeId());
    }

    @Test
    void seededWeightedChoiceIsReplayable() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"edges\":[" +
                "{\"id\":\"a\",\"fromNodeId\":1,\"toNodeId\":2,\"priority\":1,\"weight\":1," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}," +
                "{\"id\":\"b\",\"fromNodeId\":1,\"toNodeId\":3,\"priority\":1,\"weight\":9," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}";
        TopicRouteEngine engine = new TopicRouteEngine();
        String left = engine.advance(graph, engine.initial(graph),
                new NodeOutcome(1L, "COMPLETED", "r1"), 4242L).getSelectedEdgeId();
        String right = engine.advance(graph, engine.initial(graph),
                new NodeOutcome(1L, "COMPLETED", "r2"), 4242L).getSelectedEdgeId();
        assertEquals(left, right);
    }

    @Test
    void triggerTypeParticipatesAndMaxVisitsIsEnforced() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[3]," +
                "\"edges\":[" +
                "{\"id\":\"wrong-type\",\"fromNodeId\":1,\"toNodeId\":3,\"priority\":10," +
                "\"trigger\":{\"type\":\"ADVANCED_RESULT\",\"outcomeCode\":\"ART\"}}," +
                "{\"id\":\"right-type\",\"fromNodeId\":1,\"toNodeId\":2,\"priority\":1," +
                "\"trigger\":{\"type\":\"PREFERENCE_RESULT\",\"outcomeCode\":\"ART\"}}," +
                "{\"id\":\"loop\",\"fromNodeId\":2,\"toNodeId\":1,\"allowLoop\":true,\"maxVisits\":1," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"BACK\"}}]}";
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.Decision first = engine.advance(graph, engine.initial(graph), "PREFERENCE_RESULT",
                new NodeOutcome(1L, "ART", "r1"), 7L);
        assertEquals("right-type", first.getSelectedEdgeId());
        TopicRouteEngine.Decision loop = engine.advance(graph, first.getState(), "CHOICE",
                new NodeOutcome(2L, "BACK", "r2"), 7L);
        assertEquals("loop", loop.getSelectedEdgeId());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> engine.advance(graph, loop.getState(), "PREFERENCE_RESULT",
                        new NodeOutcome(1L, "ART", "r3"), 7L));
    }

    @Test
    void unavailableTargetIsFilteredBeforeUsingServerFallback() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"edges\":[{\"id\":\"closed\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]}";
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.Decision decision = engine.advance(graph, engine.initial(graph), "CHOICE",
                new NodeOutcome(1L, "DONE", "r1"), 7L,
                new java.util.LinkedHashSet<Long>(java.util.Collections.singletonList(2L)));
        assertEquals("FALLBACK", decision.getReason());
        assertEquals(3L, decision.getState().getCurrentNodeId());
    }

    @Test
    void earnedClueEvidenceIsNotRevokedByLaterSetOrInc() throws Exception {
        TopicRouteEngine engine = new TopicRouteEngine();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        engine.applyEffects(json.readTree("[{\"op\":\"SET\",\"var\":\"clue.h_map\",\"value\":1}]"), state);
        assertEquals(1, state.getVariables().get("clue.h_map").intValue());

        engine.applyEffects(json.readTree("[{\"op\":\"SET\",\"var\":\"clue.h_map\",\"value\":0},"
                + "{\"op\":\"INC\",\"var\":\"clue.h_map\",\"value\":-1}]"), state);
        assertEquals(1, state.getVariables().get("clue.h_map").intValue());

        // 未获得前仍可按配置写入；非线索变量照常可减。
        engine.applyEffects(json.readTree("[{\"op\":\"SET\",\"var\":\"clue.h_key\",\"value\":0},"
                + "{\"op\":\"SET\",\"var\":\"clues\",\"value\":2},{\"op\":\"INC\",\"var\":\"clues\",\"value\":-1}]"), state);
        assertEquals(0, state.getVariables().get("clue.h_key").intValue());
        assertEquals(1, state.getVariables().get("clues").intValue());
    }
}
