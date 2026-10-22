package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.GeoDistanceUtils;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 发布前批量模拟器：复用生产路由内核，报告终局、死路和边覆盖。 */
public class TopicRouteSimulator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TopicRouteEngine engine = new TopicRouteEngine();

    public Report simulate(String normalizedConfigJson, int runs, long baseSeed) {
        return simulate(normalizedConfigJson, runs, baseSeed, null);
    }

    public Report simulate(String normalizedConfigJson, int runs, long baseSeed,
                           Map<Long, Coordinate> coordinates) {
        if (runs <= 0 || runs > 10000) throw new IllegalArgumentException("runs 必须为 1..10000");
        JsonNode config = read(normalizedConfigJson);
        Report report = new Report(runs);
        for (int run = 0; run < runs; run++) {
            TopicRouteEngine.State state = engine.initial(normalizedConfigJson);
            int maxSteps = Math.max(16, state.getNodeStates().size() * 20);
            boolean finished = false;
            int stepsTaken = 0;
            Map<String, Integer> runEdgeVisits = new LinkedHashMap<String, Integer>();
            for (int step = 0; step < maxSteps; step++) {
                Long current = state.getCurrentNodeId();
                if (current == null) { finished = "COMPLETED".equals(state.getStatus()); break; }
                JsonNode trigger = selectTrigger(config.path("edges"), current, run + step);
                String type = trigger == null ? "CHOICE" : trigger.path("type").asText("CHOICE");
                String code = trigger == null ? "COMPLETED" : trigger.path("outcomeCode").asText("COMPLETED");
                try {
                    TopicRouteEngine.Decision decision = engine.advance(normalizedConfigJson, state, type,
                            new NodeOutcome(current, code, "simulate-" + run + "-" + step), baseSeed + run);
                    Long target = decision.getState().getCurrentNodeId();
                    if (coordinates != null && target != null && coordinates.containsKey(current)
                            && coordinates.containsKey(target)) {
                        report.totalDistanceMeters += distance(coordinates.get(current), coordinates.get(target));
                        report.distanceSamples++;
                    }
                    state = decision.getState(); stepsTaken++;
                    if (decision.getSelectedEdgeId() != null && !decision.getSelectedEdgeId().isEmpty()) {
                        report.edgeCounts.merge(decision.getSelectedEdgeId(), 1, Integer::sum);
                        int edgeVisits = runEdgeVisits.merge(decision.getSelectedEdgeId(), 1, Integer::sum);
                        if (edgeVisits > 1) report.repeatedTraversals++;
                    }
                    if ("COMPLETED".equals(state.getStatus())) {
                        finished = true; report.terminalCounts.merge(current, 1, Integer::sum); break;
                    }
                } catch (IllegalStateException deadlock) {
                    break;
                }
            }
            if (finished) {
                report.completedRuns++; report.totalSteps += stepsTaken;
                report.minSteps = Math.min(report.minSteps, stepsTaken);
                report.maxSteps = Math.max(report.maxSteps, stepsTaken);
            } else report.deadlockRuns++;
        }
        return report;
    }

    private double distance(Coordinate a, Coordinate b) {
        return GeoDistanceUtils.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude);
    }

    public static final class Coordinate {
        private final double latitude; private final double longitude;
        public Coordinate(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude; }
    }

    private JsonNode selectTrigger(JsonNode edges, Long from, int cursor) {
        if (!edges.isArray()) return null;
        int matches = 0;
        for (JsonNode edge : edges) if (edge.path("fromNodeId").asLong(-1L) == from) matches++;
        if (matches == 0) return null;
        int selected = Math.floorMod(cursor, matches); int seen = 0;
        for (JsonNode edge : edges) if (edge.path("fromNodeId").asLong(-1L) == from) {
            if (seen++ == selected) return edge.path("trigger");
        }
        return null;
    }

    private JsonNode read(String json) {
        try { return JSON.readTree(json); }
        catch (Exception e) { throw new IllegalArgumentException("路线配置 JSON 无效", e); }
    }

    public static final class Report {
        private final int runs;
        private int completedRuns;
        private int deadlockRuns;
        private int minSteps = Integer.MAX_VALUE;
        private int maxSteps;
        private long totalSteps;
        private int repeatedTraversals;
        private double totalDistanceMeters;
        private int distanceSamples;
        private final Map<String, Integer> edgeCounts = new LinkedHashMap<String, Integer>();
        private final Map<Long, Integer> terminalCounts = new LinkedHashMap<Long, Integer>();
        private Report(int runs) { this.runs = runs; }
        public int getRuns() { return runs; }
        public int getCompletedRuns() { return completedRuns; }
        public int getDeadlockRuns() { return deadlockRuns; }
        public int getMinSteps() { return completedRuns == 0 ? 0 : minSteps; }
        public int getMaxSteps() { return maxSteps; }
        public double getAverageSteps() { return completedRuns == 0 ? 0D : (double) totalSteps / completedRuns; }
        public int getRepeatedTraversals() { return repeatedTraversals; }
        public Double getAverageDistanceMeters() { return distanceSamples == 0 ? null : totalDistanceMeters / completedRuns; }
        public Map<String, Integer> getEdgeCounts() { return edgeCounts; }
        public Map<Long, Integer> getTerminalCounts() { return terminalCounts; }
    }
}
