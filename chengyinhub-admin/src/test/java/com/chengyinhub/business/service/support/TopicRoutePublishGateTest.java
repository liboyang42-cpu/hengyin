package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicRoutePublishGateTest {

    private final TopicRoutePublishGate gate = new TopicRoutePublishGate();

    @Test
    void acceptsEveryRareWeightedEdgeUsingDeterministicStateFixtures() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"variables\":{\"score\":0},\"edges\":[" +
                "{\"id\":\"rare\",\"fromNodeId\":1,\"toNodeId\":2,\"priority\":5,\"weight\":1," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}," +
                "\"conditions\":[{\"op\":\"GTE\",\"var\":\"score\",\"value\":0}]}," +
                "{\"id\":\"common\",\"fromNodeId\":1,\"toNodeId\":3,\"priority\":5,\"weight\":10000," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}," +
                "\"conditions\":[{\"op\":\"GTE\",\"var\":\"score\",\"value\":0}]}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]}";

        assertDoesNotThrow(() -> gate.assertPublishable(graph));
    }

    @Test
    void rejectsAnEdgeWhoseConditionsCannotBeTrueTogether() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"variables\":{\"score\":0},\"edges\":[" +
                "{\"id\":\"impossible\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}," +
                "\"conditions\":[{\"op\":\"GT\",\"var\":\"score\",\"value\":5}," +
                "{\"op\":\"LT\",\"var\":\"score\",\"value\":3}]}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gate.assertPublishable(graph));

        assertEquals("路线 edge impossible 没有确定性可达状态夹具 [path=routeGraphJson.edges[0], fromNodeId=1, toNodeId=2]", error.getMessage());
    }

    @Test
    void rejectsAnEdgePermanentlyShadowedByAHigherPriorityCandidate() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3],\"edges\":[" +
                "{\"id\":\"shadowed\",\"fromNodeId\":1,\"toNodeId\":2,\"priority\":1," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}}," +
                "{\"id\":\"winner\",\"fromNodeId\":1,\"toNodeId\":3,\"priority\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gate.assertPublishable(graph));

        assertEquals("路线 edge shadowed 没有确定性可达状态夹具 [path=routeGraphJson.edges[0], fromNodeId=1, toNodeId=2]", error.getMessage());
    }

    @Test
    void rejectsFallbackThatNeedsUnavailableRealityFacts() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3],\"edges\":[" +
                "{\"id\":\"gated\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]," +
                "\"nodeRequirements\":[{\"nodeId\":2,\"requireOpen\":true}," +
                "{\"nodeId\":3,\"requireReachable\":true}]}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gate.assertPublishable(graph));

        assertEquals("路线 fallback 1->3 的目标受现实门禁约束，无法保证安全回退 [path=routeGraphJson.fallbacks[0]]", error.getMessage());
    }
    @Test
    void diagnosticUsesTheActualEdgeArrayIndexRatherThanAssumingFirstEdge() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3],"
                + "\"variables\":{\"score\":0},\"edges\":["
                + "{\"id\":\"safe\",\"fromNodeId\":1,\"toNodeId\":3,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}},"
                + "{\"id\":\"bad\",\"fromNodeId\":1,\"toNodeId\":2,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"},"
                + "\"conditions\":[{\"op\":\"GT\",\"var\":\"score\",\"value\":5},{\"op\":\"LT\",\"var\":\"score\",\"value\":3}]}],"
                + "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]}";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> gate.assertPublishable(graph));
        assertEquals("路线 edge bad 没有确定性可达状态夹具 [path=routeGraphJson.edges[1], fromNodeId=1, toNodeId=2]", error.getMessage());
    }

    @Test
    void existentialWitnessDoesNotChangePlayerVariablesOrSupplyRuntimeReachabilityFacts() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3],"
                + "\"variables\":{\"score\":0},\"edges\":[{\"id\":\"maybe\",\"fromNodeId\":1,\"toNodeId\":2,"
                + "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"},\"conditions\":[{\"op\":\"GTE\",\"var\":\"score\",\"value\":0}]}],"
                + "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}],\"nodeRequirements\":[{\"nodeId\":2,\"requireReachable\":true}]}";
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.State player = engine.initial(graph);
        player.getVariables().put("score", -1);
        assertDoesNotThrow(() -> gate.assertPublishable(graph));
        assertEquals(-1, player.getVariables().get("score").intValue());
        com.chengyinhub.business.domain.CmsTopicNode from = new com.chengyinhub.business.domain.CmsTopicNode(); from.setId(1L);
        com.chengyinhub.business.domain.CmsTopicNode to = new com.chengyinhub.business.domain.CmsTopicNode(); to.setId(2L);
        assertEquals(RouteReachabilityProvider.Reachability.UNKNOWN, RouteReachabilityProvider.UNKNOWN.evaluate(7L, from, to));
        TopicRouteEngine.Decision decision = engine.advance(graph, player, "CHOICE",
                new com.chengyinhub.business.domain.route.NodeOutcome(1L, "DONE", "original-result"), 0L,
                java.util.Collections.singleton(2L));
        assertEquals("FALLBACK", decision.getReason());
        assertEquals(Long.valueOf(3), decision.getState().getCurrentNodeId());
        assertEquals(-1, player.getVariables().get("score").intValue());
    }

}
