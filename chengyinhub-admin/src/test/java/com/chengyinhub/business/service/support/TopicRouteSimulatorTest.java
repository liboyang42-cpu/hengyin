package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicRouteSimulatorTest {
    @Test
    void batchSimulationUsesProductionEngineAndReportsCoverage() {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"edges\":[" +
                "{\"id\":\"a\",\"fromNodeId\":1,\"toNodeId\":2,\"priority\":1,\"weight\":1," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}}," +
                "{\"id\":\"b\",\"fromNodeId\":1,\"toNodeId\":3,\"priority\":1,\"weight\":3," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}}]}";
        java.util.Map<Long, TopicRouteSimulator.Coordinate> coordinates = new java.util.LinkedHashMap<>();
        coordinates.put(1L, new TopicRouteSimulator.Coordinate(31.2304, 121.4737));
        coordinates.put(2L, new TopicRouteSimulator.Coordinate(31.2310, 121.4740));
        coordinates.put(3L, new TopicRouteSimulator.Coordinate(31.2320, 121.4750));
        TopicRouteSimulator.Report report = new TopicRouteSimulator().simulate(graph, 200, 42L, coordinates);
        assertEquals(200, report.getCompletedRuns());
        assertEquals(0, report.getDeadlockRuns());
        assertTrue(report.getEdgeCounts().containsKey("a"));
        assertTrue(report.getEdgeCounts().containsKey("b"));
        assertEquals(2, report.getMinSteps());
        assertEquals(2, report.getMaxSteps());
        assertEquals(200, report.getTerminalCounts().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(report.getAverageDistanceMeters() > 0D);
    }
}
