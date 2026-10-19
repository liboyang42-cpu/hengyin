package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 主题路线发布门禁与 nodeId/clientNodeKey 重映射。 */
public class TopicRouteConfigValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TRIGGERS = set("CHOICE", "PREFERENCE_RESULT", "ADVANCED_RESULT");
    private static final Set<String> EFFECTS = set("SET", "INC", "ADD_TAG");
    private static final Set<String> OPS = set("EQ", "NE", "GT", "GTE", "LT", "LTE");
    private static final Set<String> ROLE_CODES = set("NAVIGATOR", "OBSERVER", "RECORDER", "NEGOTIATOR", "DECODER");

    public String validateAndNormalize(String raw, Set<Long> topicNodeIds) {
        return validateAndNormalize(raw, topicNodeIds, null);
    }

    public String validateAndNormalize(String raw, Set<Long> topicNodeIds,
                                       Map<Long, Set<String>> declaredOutcomes) {
        ObjectNode root = canonicalizeNodeReferences(
                normalizeAliases(object(readTree(raw), "路线配置必须是对象")));
        if (root.path("schemaVersion").asInt(-1) != 1) throw fail("schemaVersion", "仅支持 1");
        Set<Long> nodes = topicNodeIds == null ? new LinkedHashSet<Long>() : new LinkedHashSet<Long>(topicNodeIds);
        long start = positive(root, "startNodeId");
        requireNode(nodes, start, "startNodeId");
        Set<Long> terminals = longs(root.path("terminalNodeIds"), "terminalNodeIds");
        if (terminals.isEmpty()) throw fail("terminalNodeIds", "至少需要一个终点");
        for (Long terminal : terminals) requireNode(nodes, terminal, "terminalNodeIds");
        Set<Long> requirementNodes = validateNodeRequirements(root.path("nodeRequirements"), nodes);

        ArrayNode edges = array(root.path("edges"), "edges");
        ArrayNode fallbacks = root.has("fallbacks") ? array(root.path("fallbacks"), "fallbacks") : JSON.createArrayNode();
        Set<String> edgeIds = new HashSet<String>();
        Map<Long, Set<Long>> graph = new LinkedHashMap<Long, Set<Long>>();
        Map<Long, Integer> outgoing = new HashMap<Long, Integer>();
        for (JsonNode rawEdge : edges) {
            ObjectNode edge = object(rawEdge, "edge 必须是对象");
            String id = text(edge, "id", 64);
            if (!edgeIds.add(id)) throw fail(id, "edge id 重复");
            long from = positive(edge, "fromNodeId"); long to = positive(edge, "toNodeId");
            requireNode(nodes, from, id + ".fromNodeId"); requireNode(nodes, to, id + ".toNodeId");
            if (terminals.contains(from)) throw fail(id, "终点不得有普通出边");
            ObjectNode trigger = object(edge.path("trigger"), id + ".trigger 无效");
            if (!TRIGGERS.contains(text(trigger, "type", 32))) throw fail(id, "trigger.type 不支持");
            String triggerType = trigger.path("type").asText();
            String outcomeCode = text(trigger, "outcomeCode", 64);
            if (declaredOutcomes != null) {
                Set<String> sourceOutcomes = declaredOutcomes.get(from);
                if (sourceOutcomes == null || !sourceOutcomes.contains(triggerType + ":" + outcomeCode)) {
                    throw fail(id, "源节点玩法未声明 outcomeCode:" + triggerType + ":" + outcomeCode);
                }
            }
            validateConditions(edge.path("conditions"), id, nodes, root.path("variables"));
            validateEffects(edge.path("effects"), id, root.path("variables"));
            int weight = edge.path("weight").asInt(1);
            if (weight < 1 || weight > 10000) throw fail(id, "weight 必须为 1..10000");
            int priority = edge.path("priority").asInt(0);
            if (priority < -1000 || priority > 1000) throw fail(id, "priority 超出范围");
            int maxVisits = edge.path("maxVisits").asInt(1);
            if (maxVisits < 0 || maxVisits > 10000) throw fail(id, "maxVisits 必须为 0..10000");
            add(graph, from, to); outgoing.put(from, outgoing.containsKey(from) ? outgoing.get(from) + 1 : 1);
        }
        Set<Long> fallbackFrom = new HashSet<Long>();
        for (JsonNode rawFallback : fallbacks) {
            ObjectNode fallback = object(rawFallback, "fallback 必须是对象");
            long from = positive(fallback, "fromNodeId"); long to = positive(fallback, "toNodeId");
            requireNode(nodes, from, "fallback.fromNodeId"); requireNode(nodes, to, "fallback.toNodeId");
            if (requirementNodes.contains(to)) {
                throw fail(from + "->" + to,
                        "fallback 目标受现实门禁约束，无法保证安全回退");
            }
            if (!fallbackFrom.add(from)) throw fail(String.valueOf(from), "每个节点只能有一个 fallback");
            if (terminals.contains(from)) throw fail(String.valueOf(from), "终点不得有 fallback");
            add(graph, from, to);
        }
        for (Map.Entry<Long, Integer> item : outgoing.entrySet()) {
            if (item.getValue() > 1 && !fallbackFrom.contains(item.getKey())) {
                throw fail(String.valueOf(item.getKey()), "分支节点必须有 fallback");
            }
        }
        for (JsonNode rawEdge : edges) {
            long from = rawEdge.path("fromNodeId").asLong();
            long to = rawEdge.path("toNodeId").asLong();
            if (requirementNodes.contains(to) && !fallbackFrom.contains(from)) {
                throw fail(rawEdge.path("id").asText(),
                        "现实门禁可能淘汰候选地点，来源节点必须配置 fallback");
            }
        }
        validateCycles(edges, fallbacks, graph);
        Set<Long> reachable = reachable(start, graph);
        if (!reachable.containsAll(terminals)) throw fail("terminalNodeIds", "存在从起点不可达的终点");
        for (Long node : nodes) if (!reachable.contains(node)) throw fail(String.valueOf(node), "节点从起点不可达");
        Set<Long> canReachTerminal = reverseReachable(terminals, graph);
        for (Long node : reachable) if (!terminals.contains(node) && !canReachTerminal.contains(node)) {
            throw fail(String.valueOf(node), "节点不能到达任何终点");
        }
        return write(root);
    }

    public String remapClientNodeKeys(String raw, Map<String, Long> nodeIdMap) {
        ObjectNode root = object(readTree(raw), "路线配置必须是对象");
        replaceKey(root, "startNodeKey", "startNodeId", nodeIdMap);
        if (root.has("terminalNodeKeys")) {
            ArrayNode ids = root.putArray("terminalNodeIds");
            for (JsonNode key : array(root.path("terminalNodeKeys"), "terminalNodeKeys")) {
                ids.add(requireMapped(nodeIdMap, key.asText(), "terminalNodeKeys"));
            }
            root.remove("terminalNodeKeys");
        }
        remapEdgeArray(root.path("edges"), nodeIdMap);
        remapEdgeArray(root.path("fallbacks"), nodeIdMap);
        remapRequirementKeys(root.path("nodeRequirements"), nodeIdMap);
        return write(root);
    }

    public String remapNodeIds(String raw, Map<Long, Long> nodeIdMap) {
        ObjectNode root = object(readTree(raw), "路线配置必须是对象");
        replaceId(root, "startNodeId", nodeIdMap);
        ArrayNode terminals = root.withArray("terminalNodeIds");
        for (int i = 0; i < terminals.size(); i++) terminals.set(i, JSON.getNodeFactory().numberNode(requireMapped(nodeIdMap, terminals.get(i).asLong(), "terminalNodeIds")));
        remapIdArray(root.path("edges"), nodeIdMap);
        remapIdArray(root.path("fallbacks"), nodeIdMap);
        remapRequirementIds(root.path("nodeRequirements"), nodeIdMap);
        return write(root);
    }

    /**
     * 整主题复制目前只复制章节和节点，不复制票种，因此源主题票 ID 没有合法的目标映射。
     * 精确票种门禁若被原样带入，会在副本中静默指向另一个主题的票；这里必须拒绝而不是放宽。
     */
    public String remapNodeIdsForTopicCopy(String raw, Map<Long, Long> nodeIdMap) {
        ObjectNode root = object(readTree(raw), "路线配置必须是对象");
        JsonNode requirements = root.path("nodeRequirements");
        if (requirements.isArray()) {
            for (JsonNode item : requirements) {
                if (item.path("allowedTicketIds").isArray()
                        && item.path("allowedTicketIds").size() > 0) {
                    throw fail("allowedTicketIds",
                            "整主题复制不会复制票种，请在副本中重新选择票种后再配置现实门禁");
                }
            }
        }
        return remapNodeIds(raw, nodeIdMap);
    }

    public JsonNode readTree(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("路线配置不能为空");
            return JSON.readTree(raw);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("路线配置 JSON 无效", e); }
    }

    private void remapEdgeArray(JsonNode raw, Map<String, Long> ids) {
        if (!raw.isArray()) return;
        for (JsonNode item : raw) {
            ObjectNode edge = object(item, "edge 必须是对象");
            replaceKey(edge, "fromNodeKey", "fromNodeId", ids);
            replaceKey(edge, "toNodeKey", "toNodeId", ids);
            remapConditionKeys(edge.path("conditions"), ids);
        }
    }

    private void remapIdArray(JsonNode raw, Map<Long, Long> ids) {
        if (!raw.isArray()) return;
        for (JsonNode item : raw) {
            ObjectNode edge = object(item, "edge 必须是对象");
            replaceId(edge, "fromNodeId", ids); replaceId(edge, "toNodeId", ids);
            remapConditionIds(edge.path("conditions"), ids);
        }
    }

    private void remapConditionKeys(JsonNode raw, Map<String, Long> ids) {
        if (!raw.isArray()) return;
        for (JsonNode item : raw) replaceKey(object(item, "condition 必须是对象"), "nodeKey", "nodeId", ids);
    }

    private void remapConditionIds(JsonNode raw, Map<Long, Long> ids) {
        if (!raw.isArray()) return;
        for (JsonNode item : raw) {
            ObjectNode condition = object(item, "condition 必须是对象");
            String op = condition.path("op").asText(condition.path("type").asText());
            if ("NODE_COMPLETED".equals(op)) replaceId(condition, "nodeId", ids);
        }
    }

    private void remapRequirementKeys(JsonNode raw, Map<String, Long> ids) {
        if (!raw.isArray()) return;
        for (JsonNode item : raw) {
            replaceKey(object(item, "nodeRequirement 必须是对象"), "nodeKey", "nodeId", ids);
        }
    }

    private void remapRequirementIds(JsonNode raw, Map<Long, Long> ids) {
        if (!raw.isArray()) return;
        for (JsonNode item : raw) {
            replaceId(object(item, "nodeRequirement 必须是对象"), "nodeId", ids);
        }
    }

    /** 编辑端以字符串保存 picker id；发布边界统一收成数值，避免合法快照在运行时才失败。 */
    private ObjectNode canonicalizeNodeReferences(ObjectNode root) {
        canonicalizeId(root, "startNodeId");
        canonicalizeIdArray(root.path("terminalNodeIds"));
        canonicalizeEdgeReferences(root.path("edges"));
        canonicalizeEdgeReferences(root.path("fallbacks"));
        JsonNode requirements = root.path("nodeRequirements");
        if (requirements.isArray()) for (JsonNode item : requirements) {
            if (item.isObject()) canonicalizeId((ObjectNode) item, "nodeId");
        }
        return root;
    }

    private void canonicalizeEdgeReferences(JsonNode raw) {
        if (!raw.isArray()) return;
        for (JsonNode item : raw) {
            if (!item.isObject()) continue;
            ObjectNode edge = (ObjectNode) item;
            canonicalizeId(edge, "fromNodeId");
            canonicalizeId(edge, "toNodeId");
            JsonNode conditions = edge.path("conditions");
            if (!conditions.isArray()) continue;
            for (JsonNode condition : conditions) {
                if (condition.isObject() && "NODE_COMPLETED".equals(condition.path("op").asText())) {
                    canonicalizeId((ObjectNode) condition, "nodeId");
                }
            }
        }
    }

    private void canonicalizeIdArray(JsonNode raw) {
        if (!raw.isArray()) return;
        ArrayNode values = (ArrayNode) raw;
        for (int i = 0; i < values.size(); i++) {
            Long parsed = numericText(values.get(i));
            if (parsed != null) values.set(i, JSON.getNodeFactory().numberNode(parsed));
        }
    }

    private void canonicalizeId(ObjectNode node, String field) {
        Long parsed = numericText(node.get(field));
        if (parsed != null) node.put(field, parsed);
    }

    private Long numericText(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        String text = value.asText().trim();
        if (!text.matches("[1-9]\\d*")) return null;
        try { return Long.valueOf(text); }
        catch (NumberFormatException outOfRange) { return null; }
    }

    private void replaceKey(ObjectNode node, String keyField, String idField, Map<String, Long> ids) {
        if (!node.has(keyField)) return;
        node.put(idField, requireMapped(ids, node.path(keyField).asText(), keyField));
        node.remove(keyField);
    }

    private void replaceId(ObjectNode node, String field, Map<Long, Long> ids) {
        if (!node.has(field)) return;
        node.put(field, requireMapped(ids, node.path(field).asLong(), field));
    }

    private long requireMapped(Map<String, Long> ids, String key, String field) {
        Long mapped = ids == null ? null : ids.get(key);
        if (mapped == null || mapped <= 0L) throw fail(field, "未知 clientNodeKey:" + key);
        return mapped;
    }

    private long requireMapped(Map<Long, Long> ids, long key, String field) {
        Long mapped = ids == null ? null : ids.get(key);
        if (mapped == null || mapped <= 0L) throw fail(field, "找不到 nodeId 映射:" + key);
        return mapped;
    }

    private void validateConditions(JsonNode raw, String edgeId, Set<Long> topicNodeIds, JsonNode variables) {
        if (raw.isMissingNode()) return;
        ArrayNode conditions = array(raw, edgeId + ".conditions");
        for (JsonNode item : conditions) {
            ObjectNode condition = object(item, edgeId + ".condition 无效");
            String op = text(condition, "op", 32);
            if (OPS.contains(op)) {
                String var = text(condition, "var", 64);
                if (!variables.isObject() || !variables.has(var)) {
                    throw fail(edgeId, "condition 使用未声明变量:" + var);
                }
                if (!condition.path("value").isNumber()) throw fail(edgeId, "condition.value 必须是数值");
            } else if ("ITEM_HELD".equals(op) || "ITEM_ACQUIRED".equals(op)) {
                if (!condition.path("itemId").asText().matches("[a-z][a-z0-9_]{0,31}") || !condition.path("quantity").isIntegralNumber()
                    || !condition.path("quantity").canConvertToLong() || condition.path("quantity").asLong() < 1
                    || condition.path("quantity").asLong() > 999) throw fail(edgeId,"物资条件不合法");
            } else if ("NODE_COMPLETED".equals(op)) {
                long nodeId = positive(condition, "nodeId");
                requireNode(topicNodeIds, nodeId, edgeId + ".condition.nodeId");
            } else if ("HAS_TAG".equals(op)) text(condition, "value", 64);
            else throw fail(edgeId, "condition.op 不支持");
        }
    }

    private void validateEffects(JsonNode raw, String edgeId, JsonNode variables) {
        if (raw.isMissingNode()) return;
        ArrayNode effects = array(raw, edgeId + ".effects");
        for (JsonNode item : effects) {
            ObjectNode effect = object(item, edgeId + ".effect 无效");
            String op = text(effect, "op", 32);
            if (!EFFECTS.contains(op)) throw fail(edgeId, "effect.op 不支持");
            if ("ADD_TAG".equals(op)) text(effect, "value", 64);
            else {
                String var = text(effect, "var", 64);
                if (!variables.isObject() || !variables.has(var)) throw fail(edgeId, "effect 使用未声明变量:" + var);
                if (!effect.path("value").isNumber()) throw fail(edgeId, "effect.value 必须是数值");
            }
        }
    }

    private Set<Long> validateNodeRequirements(JsonNode raw, Set<Long> topicNodeIds) {
        Set<Long> seen = new HashSet<Long>();
        if (raw.isMissingNode()) return seen;
        ArrayNode requirements = array(raw, "nodeRequirements");
        for (JsonNode item : requirements) {
            ObjectNode requirement = object(item, "nodeRequirement 必须是对象");
            long nodeId = strictPositiveLong(requirement.path("nodeId"), "nodeRequirements.nodeId");
            requireNode(topicNodeIds, nodeId, "nodeRequirements.nodeId");
            if (!seen.add(nodeId)) throw fail("nodeRequirements", "同一节点只能声明一次现实条件:" + nodeId);
            validatePositiveLongArray(requirement.path("allowedTicketIds"), "allowedTicketIds", Long.MAX_VALUE);
            validatePositiveLongArray(requirement.path("allowedPurchaseKinds"), "allowedPurchaseKinds", 3L);
            validateRoleCodes(requirement.path("requiredRoleCodes"));
            validateBoolean(requirement, "requireOpen");
            validateBoolean(requirement, "requireReachable");
            if (requirement.has("maxDistanceMeters")) {
                JsonNode distance = requirement.path("maxDistanceMeters");
                double value = distance.isNumber() ? distance.asDouble() : Double.NaN;
                if (!Double.isFinite(value) || value <= 0D || value > 1000000D) {
                    throw fail("maxDistanceMeters", "必须是 (0,1000000] 范围内的数值");
                }
            }
        }
        return seen;
    }

    private void validatePositiveLongArray(JsonNode raw, String field, long max) {
        if (raw.isMissingNode()) return;
        ArrayNode values = array(raw, field);
        if (values.isEmpty()) throw fail(field, "不能为空数组");
        Set<Long> seen = new HashSet<Long>();
        for (JsonNode value : values) {
            long parsed = strictPositiveLong(value, field);
            if (parsed > max) throw fail(field, "取值超出范围");
            if (!seen.add(parsed)) throw fail(field, "不得包含重复值:" + parsed);
        }
    }

    private void validateRoleCodes(JsonNode raw) {
        if (raw.isMissingNode()) return;
        ArrayNode values = array(raw, "requiredRoleCodes");
        if (values.isEmpty()) throw fail("requiredRoleCodes", "不能为空数组");
        Set<String> seen = new HashSet<String>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw fail("requiredRoleCodes", "必须是字符串数组");
            String role = value.asText().trim();
            if (!ROLE_CODES.contains(role)) throw fail("requiredRoleCodes", "角色码不支持:" + role);
            if (!seen.add(role)) throw fail("requiredRoleCodes", "不得包含重复值:" + role);
        }
    }

    private void validateBoolean(ObjectNode requirement, String field) {
        if (requirement.has(field) && !requirement.path(field).isBoolean()) {
            throw fail(field, "必须是布尔值");
        }
    }

    private long strictPositiveLong(JsonNode raw, String field) {
        if (!raw.isIntegralNumber() || !raw.canConvertToLong() || raw.asLong() <= 0L) {
            throw fail(field, "必须是正整数");
        }
        return raw.asLong();
    }

    private void validateCycles(ArrayNode edges, ArrayNode fallbacks, Map<Long, Set<Long>> graph) {
        for (JsonNode raw : edges) {
            ObjectNode edge = (ObjectNode) raw;
            long from = edge.path("fromNodeId").asLong(); long to = edge.path("toNodeId").asLong();
            if (pathExists(to, from, graph, new HashSet<Long>())
                    && (!edge.path("allowLoop").asBoolean(false) || edge.path("maxVisits").asInt(0) <= 0)) {
                throw fail(edge.path("id").asText(), "循环边必须 allowLoop=true 且 maxVisits>0");
            }
        }
        for (JsonNode raw : fallbacks) {
            long from = raw.path("fromNodeId").asLong(); long to = raw.path("toNodeId").asLong();
            if (pathExists(to, from, graph, new HashSet<Long>())) throw fail(String.valueOf(from), "fallback 不允许形成环");
        }
    }

    private boolean pathExists(Long current, Long target, Map<Long, Set<Long>> graph, Set<Long> seen) {
        if (current.equals(target)) return true;
        if (!seen.add(current)) return false;
        for (Long next : graph.containsKey(current) ? graph.get(current) : java.util.Collections.<Long>emptySet()) {
            if (pathExists(next, target, graph, seen)) return true;
        }
        return false;
    }

    /** 兼容仅发生在解析边界；返回值和持久化快照只保留 V1 canonical 字段。 */
    private ObjectNode normalizeAliases(ObjectNode root) {
        JsonNode rawEdges = root.path("edges");
        if (!rawEdges.isArray()) return root;
        for (JsonNode raw : rawEdges) {
            if (!raw.isObject()) continue;
            ObjectNode edge = (ObjectNode) raw;
            if (edge.path("trigger").isObject()) {
                ObjectNode trigger = (ObjectNode) edge.path("trigger");
                moveAlias(trigger, "code", "outcomeCode");
            }
            if (edge.path("conditions").isArray()) for (JsonNode item : edge.path("conditions")) {
                if (!item.isObject()) continue;
                ObjectNode condition = (ObjectNode) item;
                moveAlias(condition, "name", "var");
                moveAlias(condition, "variable", "var");
                moveAlias(condition, "operator", "op");
                String legacyType = condition.path("type").asText("");
                if (!condition.has("op") && !legacyType.isEmpty()) {
                    condition.put("op", "VAR".equals(legacyType) ? "EQ" : legacyType);
                }
                if ("HAS_TAG".equals(legacyType)) moveAlias(condition, "tag", "value");
                condition.remove("type"); condition.remove("name"); condition.remove("variable");
                condition.remove("operator"); condition.remove("tag");
            }
            if (edge.path("effects").isArray()) for (JsonNode item : edge.path("effects")) {
                if (!item.isObject()) continue;
                ObjectNode effect = (ObjectNode) item;
                moveAlias(effect, "type", "op"); moveAlias(effect, "name", "var");
                if ("ADD_TAG".equals(effect.path("op").asText())) moveAlias(effect, "tag", "value");
                effect.remove("type"); effect.remove("name"); effect.remove("tag");
            }
        }
        return root;
    }

    private void moveAlias(ObjectNode node, String oldField, String canonicalField) {
        if (!node.has(canonicalField) && node.has(oldField)) node.set(canonicalField, node.path(oldField));
        node.remove(oldField);
    }

    private Set<Long> reachable(Long start, Map<Long, Set<Long>> graph) {
        Set<Long> out = new LinkedHashSet<Long>(); Deque<Long> queue = new ArrayDeque<Long>(); queue.add(start);
        while (!queue.isEmpty()) { Long node = queue.removeFirst(); if (!out.add(node)) continue;
            for (Long next : graph.containsKey(node) ? graph.get(node) : java.util.Collections.<Long>emptySet()) queue.add(next); }
        return out;
    }

    private Set<Long> reverseReachable(Set<Long> terminals, Map<Long, Set<Long>> graph) {
        Map<Long, Set<Long>> reverse = new HashMap<Long, Set<Long>>();
        for (Map.Entry<Long, Set<Long>> item : graph.entrySet()) for (Long to : item.getValue()) add(reverse, to, item.getKey());
        Set<Long> out = new LinkedHashSet<Long>(); Deque<Long> queue = new ArrayDeque<Long>(terminals);
        while (!queue.isEmpty()) { Long node = queue.removeFirst(); if (!out.add(node)) continue;
            for (Long prev : reverse.containsKey(node) ? reverse.get(node) : java.util.Collections.<Long>emptySet()) queue.add(prev); }
        return out;
    }

    private void add(Map<Long, Set<Long>> graph, long from, long to) {
        if (!graph.containsKey(from)) graph.put(from, new LinkedHashSet<Long>());
        graph.get(from).add(to);
    }
    private Set<Long> longs(JsonNode raw, String field) {
        ArrayNode array = array(raw, field); Set<Long> out = new LinkedHashSet<Long>();
        for (JsonNode item : array) { long value = item.asLong(-1L); if (value <= 0L) throw fail(field, "nodeId 无效"); out.add(value); }
        return out;
    }
    private long positive(ObjectNode node, String field) {
        long value = node.path(field).asLong(-1L); if (value <= 0L) throw fail(field, "必须是正整数"); return value;
    }
    private String text(ObjectNode node, String field, int max) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty() || value.length() > max) throw fail(field, "不能为空且长度不超过 " + max); return value;
    }
    private void requireNode(Set<Long> nodes, long node, String field) {
        if (!nodes.contains(node)) throw fail(field, "节点不属于当前主题:" + node);
    }
    private ObjectNode object(JsonNode node, String message) { if (!node.isObject()) throw new IllegalArgumentException(message); return (ObjectNode) node; }
    private ArrayNode array(JsonNode node, String field) { if (!node.isArray()) throw fail(field, "必须是数组"); return (ArrayNode) node; }
    private String write(JsonNode node) { try { return JSON.writeValueAsString(node); } catch (Exception e) { throw new IllegalStateException(e); } }
    private IllegalArgumentException fail(String field, String message) { return new IllegalArgumentException(field + ": " + message); }
    private static Set<String> set(String... values) { Set<String> out = new HashSet<String>(); java.util.Collections.addAll(out, values); return out; }
}
