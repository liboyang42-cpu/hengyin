package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.route.NodeOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路线发布确定性门禁：为每条普通边构造有限状态分区夹具，证明它在生产 eligibility/priority
 * 规则下具有非零入选概率；随机模拟只负责分布与路长，不再承担稀有边可达性证明。
 */
public class TopicRoutePublishGate {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FIXTURES_PER_EDGE = 50000;
    private final TopicRouteEngine engine = new TopicRouteEngine();

    public void assertPublishable(String normalizedConfigJson) {
        JsonNode root = read(normalizedConfigJson);
        JsonNode edges = root.path("edges");
        if (edges.isArray()) {
            for (int index = 0; index < edges.size(); index++)
                assertEdgeHasFixture(normalizedConfigJson, root, edges, edges.get(index), index);
        }
        assertFallbackFixtures(normalizedConfigJson, root.path("fallbacks"),
                realityGatedNodeIds(root.path("nodeRequirements")));
    }

    private void assertEdgeHasFixture(String configJson, JsonNode root, JsonNode edges, JsonNode target, int index) {
        String edgeId = target.path("id").asText();
        long from = target.path("fromNodeId").asLong();
        String triggerType = target.path("trigger").path("type").asText();
        String outcomeCode = target.path("trigger").path("outcomeCode").asText();

        List<JsonNode> competing = new ArrayList<JsonNode>();
        for (JsonNode edge : edges) {
            if (edge.path("fromNodeId").asLong() == from
                    && triggerType.equals(edge.path("trigger").path("type").asText())
                    && outcomeCode.equals(edge.path("trigger").path("outcomeCode").asText())) {
                competing.add(edge);
            }
        }

        TopicRouteEngine.State base = engine.initial(configJson);
        base.setCurrentNodeId(from);
        base.setRecommendedNodeId(from);
        applyPositiveFacts(base, target.path("conditions"));

        Map<String, List<Double>> domains = variableDomains(root, competing, target.path("conditions"));
        if (domains == null) throw unreachable(edgeId, target, index);
        List<String> names = new ArrayList<String>(domains.keySet());
        int[] attempts = new int[] { 0 };
        if (!hasFixture(configJson, edgeId, triggerType, outcomeCode, from, base,
                names, domains, 0, attempts)) {
            if (attempts[0] >= MAX_FIXTURES_PER_EDGE) {
                throw new IllegalArgumentException("路线 edge " + edgeId + " 的条件组合过于复杂，无法确定性验收" + edgeLocation(target, index));
            }
            throw unreachable(edgeId, target, index);
        }
    }

    private boolean hasFixture(String configJson, String edgeId, String triggerType, String outcomeCode,
                               long from, TopicRouteEngine.State state, List<String> names,
                               Map<String, List<Double>> domains, int cursor, int[] attempts) {
        if (attempts[0] >= MAX_FIXTURES_PER_EDGE) return false;
        if (cursor == names.size()) {
            attempts[0]++;
            return engine.topPriorityEligibleEdgeIdsForVerification(configJson, state, triggerType,
                    new NodeOutcome(from, outcomeCode, "publish-fixture:" + edgeId)).contains(edgeId);
        }
        String name = names.get(cursor);
        Number previous = state.getVariables().get(name);
        for (Double value : domains.get(name)) {
            state.getVariables().put(name, value);
            if (hasFixture(configJson, edgeId, triggerType, outcomeCode, from, state,
                    names, domains, cursor + 1, attempts)) return true;
        }
        if (previous == null) state.getVariables().remove(name);
        else state.getVariables().put(name, previous);
        return false;
    }

    private Map<String, List<Double>> variableDomains(JsonNode root, List<JsonNode> competing,
                                                       JsonNode targetConditions) {
        Map<String, Set<Double>> values = new LinkedHashMap<String, Set<Double>>();
        for (JsonNode edge : competing) collectThresholds(values, edge.path("conditions"));
        Map<String, List<Double>> domains = new LinkedHashMap<String, List<Double>>();
        for (Map.Entry<String, Set<Double>> item : values.entrySet()) {
            Set<Double> candidates = new LinkedHashSet<Double>();
            JsonNode initial = root.path("variables").path(item.getKey());
            if (initial.isNumber()) candidates.add(initial.asDouble());
            List<Double> thresholds = new ArrayList<Double>(item.getValue());
            Collections.sort(thresholds);
            for (Double threshold : thresholds) {
                candidates.add(Math.nextDown(threshold));
                candidates.add(threshold);
                candidates.add(Math.nextUp(threshold));
                candidates.add(threshold - 1D);
                candidates.add(threshold + 1D);
            }
            for (int i = 0; i + 1 < thresholds.size(); i++) {
                candidates.add(thresholds.get(i) + (thresholds.get(i + 1) - thresholds.get(i)) / 2D);
            }
            List<Double> allowed = new ArrayList<Double>();
            for (Double candidate : candidates) {
                if (Double.isFinite(candidate) && targetVariableConditionsPass(
                        targetConditions, item.getKey(), candidate)) allowed.add(candidate);
            }
            if (allowed.isEmpty()) return null;
            domains.put(item.getKey(), allowed);
        }
        return domains;
    }

    private void collectThresholds(Map<String, Set<Double>> values, JsonNode conditions) {
        if (!conditions.isArray()) return;
        for (JsonNode condition : conditions) {
            String op = condition.path("op").asText();
            if (!isNumeric(op)) continue;
            String name = condition.path("var").asText();
            values.computeIfAbsent(name, ignored -> new LinkedHashSet<Double>())
                    .add(condition.path("value").asDouble());
        }
    }

    private boolean targetVariableConditionsPass(JsonNode conditions, String name, double candidate) {
        if (!conditions.isArray()) return true;
        for (JsonNode condition : conditions) {
            String op = condition.path("op").asText();
            if (!isNumeric(op) || !name.equals(condition.path("var").asText())) continue;
            double expected = condition.path("value").asDouble();
            if (!compare(candidate, expected, op)) return false;
        }
        return true;
    }

    private void applyPositiveFacts(TopicRouteEngine.State state, JsonNode conditions) {
        if (!conditions.isArray()) return;
        for (JsonNode condition : conditions) {
            String op = condition.path("op").asText();
            if ("HAS_TAG".equals(op)) state.getTags().add(condition.path("value").asText());
            if ("NODE_COMPLETED".equals(op)) state.getCompletedNodeIds().add(condition.path("nodeId").asLong());
        }
    }

    private void assertFallbackFixtures(String configJson, JsonNode fallbacks, Set<Long> realityGatedNodeIds) {
        if (!fallbacks.isArray()) return;
        for (int index = 0; index < fallbacks.size(); index++) {
            JsonNode fallback = fallbacks.get(index);
            long from = fallback.path("fromNodeId").asLong();
            long to = fallback.path("toNodeId").asLong();
            if (realityGatedNodeIds.contains(to)) {
                throw new IllegalArgumentException("路线 fallback " + from + "->" + to
                        + " 的目标受现实门禁约束，无法保证安全回退" + fallbackLocation(index));
            }
            TopicRouteEngine.State state = engine.initial(configJson);
            state.setCurrentNodeId(from);
            state.setRecommendedNodeId(from);
            TopicRouteEngine.Decision decision;
            try {
                decision = engine.advance(configJson, state, "PUBLISH_GATE_FALLBACK",
                        new NodeOutcome(from, "FALLBACK", "publish-fallback:" + from), 0L);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("路线 fallback " + from + "->" + to + " 没有确定性可达状态夹具" + fallbackLocation(index), error);
            }
            if (!"FALLBACK".equals(decision.getReason())
                    || !Long.valueOf(to).equals(decision.getState().getCurrentNodeId())) {
                throw new IllegalArgumentException("路线 fallback " + from + "->" + to + " 没有确定性可达状态夹具" + fallbackLocation(index));
            }
        }
    }

    private Set<Long> realityGatedNodeIds(JsonNode requirements) {
        Set<Long> ids = new LinkedHashSet<Long>();
        if (!requirements.isArray()) return ids;
        for (JsonNode requirement : requirements) {
            if (requirement.path("nodeId").canConvertToLong()) {
                ids.add(requirement.path("nodeId").asLong());
            }
        }
        return ids;
    }

    private boolean isNumeric(String op) {
        return "EQ".equals(op) || "NE".equals(op) || "GT".equals(op) || "GTE".equals(op)
                || "LT".equals(op) || "LTE".equals(op);
    }

    private boolean compare(double left, double right, String op) {
        switch (op) {
            case "EQ": return Double.compare(left, right) == 0;
            case "NE": return Double.compare(left, right) != 0;
            case "GT": return left > right;
            case "GTE": return left >= right;
            case "LT": return left < right;
            case "LTE": return left <= right;
            default: return false;
        }
    }

    private IllegalArgumentException unreachable(String edgeId, JsonNode edge, int index) {
        return new IllegalArgumentException("路线 edge " + edgeId + " 没有确定性可达状态夹具"
                + edgeLocation(edge, index));
    }

    private String edgeLocation(JsonNode edge, int index) {
        return " [path=routeGraphJson.edges[" + index + "], fromNodeId="
                + edge.path("fromNodeId").asLong() + ", toNodeId=" + edge.path("toNodeId").asLong() + "]";
    }

    private String fallbackLocation(int index) {
        return " [path=routeGraphJson.fallbacks[" + index + "]]";
    }

    private JsonNode read(String raw) {
        try { return JSON.readTree(raw); }
        catch (Exception error) { throw new IllegalArgumentException("路线配置 JSON 无效", error); }
    }
}
