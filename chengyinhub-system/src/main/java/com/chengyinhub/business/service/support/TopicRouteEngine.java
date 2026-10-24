package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** 主题路线纯计算内核：不查库、不发奖、不接受 targetNodeId。 */
public class TopicRouteEngine {
    private static final ObjectMapper JSON = new ObjectMapper();

    public State initial(String configJson) {
        JsonNode config = read(configJson);
        long start = requiredLong(config, "startNodeId");
        State state = new State();
        state.status = "ACTIVE";
        state.currentNodeId = start;
        state.recommendedNodeId = start;
        state.variables.putAll(readNumberMap(config.path("variables")));
        for (Long id : allNodeIds(config)) state.nodeStates.put(id, TopicRouteState.HIDDEN);
        state.nodeStates.put(start, TopicRouteState.PLAYABLE);
        state.visitedNodeIds.add(start);
        return state;
    }

    public Decision advance(String configJson, State current, NodeOutcome outcome, long seed) {
        return advance(configJson, current, "CHOICE", outcome, seed);
    }

    public Decision advance(String configJson, State current, String triggerType, NodeOutcome outcome, long seed) {
        return advance(configJson, current, triggerType, outcome, seed, Collections.<Long>emptySet(), new Date(0L));
    }

    public Decision advance(String configJson, State current, String triggerType, NodeOutcome outcome, long seed,
                            Set<Long> unavailableNodeIds) {
        return advance(configJson, current, triggerType, outcome, seed, unavailableNodeIds, new Date(0L));
    }

    public Decision advance(String configJson, State current, String triggerType, NodeOutcome outcome, long seed,
                            Set<Long> unavailableNodeIds, Date decisionAt) {
        if (current == null || outcome == null) throw new IllegalArgumentException("路线状态与结果不能为空");
        if (triggerType == null || triggerType.trim().isEmpty()) throw new IllegalArgumentException("triggerType 不能为空");
        if (!"ACTIVE".equals(current.status)) throw new IllegalStateException("本局已结束");
        if (!outcome.getNodeId().equals(current.currentNodeId)) throw new IllegalStateException("结果节点不是当前节点");
        JsonNode config = read(configJson);
        State next = current.copy();
        long from = outcome.getNodeId();
        next.completedNodeIds.add(from);
        next.nodeStates.put(from, TopicRouteState.COMPLETED);

        if (containsLong(config.path("terminalNodeIds"), from)) {
            next.status = "COMPLETED";
            next.currentNodeId = null;
            next.recommendedNodeId = null;
            appendLog(next, from, outcome.getOutcomeCode(), null, null, "TERMINAL", decisionAt);
            return new Decision(next, null, "TERMINAL");
        }

        Set<Long> unavailable = unavailableNodeIds == null ? Collections.<Long>emptySet() : unavailableNodeIds;
        List<JsonNode> eligible = eligibleEdges(config.path("edges"), next, triggerType, outcome, unavailable);
        boolean fallback = false;
        if (eligible.isEmpty()) {
            eligible = fallbackEdges(config.path("fallbacks"), from, unavailable, next);
            fallback = true;
        }
        if (eligible.isEmpty()) throw new IllegalStateException("当前结果没有合法下一站");

        int bestPriority = Integer.MIN_VALUE;
        for (JsonNode edge : eligible) bestPriority = Math.max(bestPriority, edge.path("priority").asInt(0));
        List<JsonNode> best = new ArrayList<JsonNode>();
        for (JsonNode edge : eligible) if (edge.path("priority").asInt(0) == bestPriority) best.add(edge);
        JsonNode selected = weighted(best, seed, next.pull);
        next.pull++;
        String edgeId = selected.path("id").asText(fallback ? "fallback-" + from : "");
        if (edgeId.length() > 0) {
            next.usedEdgeIds.add(edgeId);
            next.edgeVisits.put(edgeId, visits(next, edgeId) + 1);
        }
        applyEffects(selected.path("effects"), next);
        long target = requiredLong(selected, "toNodeId");
        next.currentNodeId = target;
        next.recommendedNodeId = target;
        next.visitedNodeIds.add(target);
        next.nodeStates.put(target, TopicRouteState.PLAYABLE);
        appendLog(next, from, outcome.getOutcomeCode(), edgeId, target,
                fallback ? "FALLBACK" : "MATCHED", decisionAt);
        return new Decision(next, edgeId, fallback ? "FALLBACK" : "MATCHED");
    }

    /** 发布门禁专用：复用生产 eligibility/priority 规则，返回给定状态下具有非零抽中概率的普通边。 */
    Set<String> topPriorityEligibleEdgeIdsForVerification(String configJson, State current,
                                                           String triggerType, NodeOutcome outcome) {
        JsonNode config = read(configJson);
        State candidate = current.copy();
        candidate.completedNodeIds.add(outcome.getNodeId());
        List<JsonNode> eligible = eligibleEdges(config.path("edges"), candidate, triggerType, outcome,
                Collections.<Long>emptySet());
        int bestPriority = Integer.MIN_VALUE;
        for (JsonNode edge : eligible) bestPriority = Math.max(bestPriority, edge.path("priority").asInt(0));
        Set<String> ids = new LinkedHashSet<String>();
        for (JsonNode edge : eligible) {
            if (edge.path("priority").asInt(0) == bestPriority) ids.add(edge.path("id").asText());
        }
        return ids;
    }

    private List<JsonNode> eligibleEdges(JsonNode edges, State state, String triggerType, NodeOutcome outcome,
                                         Set<Long> unavailableNodeIds) {
        List<JsonNode> out = new ArrayList<JsonNode>();
        if (!edges.isArray()) return out;
        for (JsonNode edge : edges) {
            if (edge.path("fromNodeId").asLong(-1L) != outcome.getNodeId()) continue;
            String id = edge.path("id").asText();
            int visits = visits(state, id);
            if (edge.path("once").asBoolean(false) && visits > 0) continue;
            int maxVisits = edge.path("maxVisits").asInt(1);
            if (maxVisits > 0 && visits >= maxVisits) continue;
            long target = edge.path("toNodeId").asLong(-1L);
            if (unavailableNodeIds.contains(target)) continue;
            if (!edge.path("allowLoop").asBoolean(false) && state.completedNodeIds.contains(target)) continue;
            JsonNode trigger = edge.path("trigger");
            if (!triggerType.equals(trigger.path("type").asText())) continue;
            if (!outcome.getOutcomeCode().equals(trigger.path("outcomeCode").asText())) continue;
            if (!conditionsPass(edge.path("conditions"), state)) continue;
            out.add(edge);
        }
        return out;
    }

    private List<JsonNode> fallbackEdges(JsonNode fallbacks, long from, Set<Long> unavailableNodeIds, State state) {
        List<JsonNode> out = new ArrayList<JsonNode>();
        if (!fallbacks.isArray()) return out;
        for (JsonNode edge : fallbacks) if (edge.path("fromNodeId").asLong(-1L) == from
                && !unavailableNodeIds.contains(edge.path("toNodeId").asLong(-1L))
                && !state.completedNodeIds.contains(edge.path("toNodeId").asLong(-1L))) out.add(edge);
        return out;
    }

    /** 路线图条件求值(公开给旅程块配置复用:requires / advantageIf / disadvantageIf 同一套写法)。 */
    public boolean conditionsPass(JsonNode conditions, State state) {
        return conditionsPass(conditions, state, 0);
    }

    /**
     * 数组元素之间仍是 AND;AND / OR / NOT 只以显式嵌套对象出现,没有字符串表达式,
     * 所以优先级完全由结构决定:NOT 只作用于它自己的 condition,AND/OR 只作用于自己的 conditions。
     */
    private boolean conditionsPass(JsonNode conditions, State state, int depth) {
        if (!conditions.isArray()) return true;
        for (JsonNode condition : conditions) {
            if (!conditionPasses(condition, state, depth)) return false;
        }
        return true;
    }

    private boolean conditionPasses(JsonNode condition, State state, int depth) {
        String op = condition.path("op").asText();
        if (StoryVocabulary.AND.equals(op) || StoryVocabulary.OR.equals(op) || StoryVocabulary.NOT.equals(op)) {
            if (depth >= StoryVocabulary.MAX_CONDITION_DEPTH) throw new IllegalArgumentException("条件嵌套过深");
            if (StoryVocabulary.NOT.equals(op)) {
                JsonNode inner = condition.path("condition");
                if (!inner.isObject()) throw new IllegalArgumentException("NOT 必须带一个 condition 对象");
                return !conditionPasses(inner, state, depth + 1);
            }
            JsonNode children = condition.path("conditions");
            if (!children.isArray()) throw new IllegalArgumentException(op + " 必须带 conditions 数组");
            if (StoryVocabulary.AND.equals(op)) return conditionsPass(children, state, depth + 1);
            for (JsonNode child : children) if (conditionPasses(child, state, depth + 1)) return true;
            return false;
        }
        if (StoryVocabulary.VISIT_COUNT.equals(op)) {
            JsonNode value = condition.get("value");
            if (value == null || !value.isNumber()) return false;
            return compare(visitCount(state, condition.path("nodeId").asLong()), value.asDouble(),
                    condition.path("cmp").asText("GTE"));
        }
        if (StoryVocabulary.ROLE_IN.equals(op)) {
            String role = state.actorRoleCode;
            if (role == null || role.isEmpty()) return false;
            for (JsonNode allowed : condition.path("values")) if (role.equals(allowed.asText())) return true;
            return false;
        }
        if (StoryVocabulary.HAS_CONDITION.equals(op)) {
            return state.statusConditions.contains(condition.path("value").asText());
        }
        if (StoryVocabulary.ITEM_EQUIPPED.equals(op)) {
            String itemId = condition.path("itemId").asText();
            return state.equipped.containsValue(itemId) && held(state, itemId) > 0;
        }
        if (StoryVocabulary.CHECK_RESULT.equals(op)) {
            // 读检定回执契约落进本局的事实 sys.passed.{nodeId}(结算与回执同一次 CAS 写入);未结算 = 两种结果都不成立。
            Number passed = state.variables.get(StoryVocabulary.SYS_PASSED_PREFIX + condition.path("nodeId").asLong());
            String result = condition.path("result").asText();
            if (StoryVocabulary.CHECK_PASS.equals(result)) return passed != null && passed.intValue() == 1;
            if (StoryVocabulary.CHECK_FAIL.equals(result)) return passed != null && passed.intValue() == 0;
            throw new IllegalArgumentException("不支持的检定结果:" + result);
        }
        if (StoryVocabulary.ENV_IN.equals(op)) {
            // F6:环境快照由服务端写入;没开环境键 / 天气源失败时取 unknown(fail-open:不报错,按「未知」判)。
            String value = state.environment.get(condition.path("key").asText());
            if (value == null || value.isEmpty()) value = JourneyRpg.ENV_UNKNOWN;
            for (JsonNode allowed : condition.path("values")) if (value.equals(allowed.asText())) return true;
            return false;
        }
        return legacyConditionPasses(condition, state);
    }

    private boolean legacyConditionPasses(JsonNode condition, State state) {
        String op = condition.path("op").asText();
        if ("ITEM_HELD".equals(op) || "ITEM_ACQUIRED".equals(op)) {
            String field = "ITEM_HELD".equals(op) ? "inventory" : "acquired";
            return state.themeRuntime.path(field).path(condition.path("itemId").asText()).asInt() >= condition.path("quantity").asInt();
        }
        if ("NODE_COMPLETED".equals(op)) {
            return state.completedNodeIds.contains(condition.path("nodeId").asLong());
        }
        if ("HAS_TAG".equals(op)) {
            return state.tags.contains(condition.path("value").asText());
        }
        Number left = state.variables.get(condition.path("var").asText());
        JsonNode value = condition.get("value");
        return left != null && value != null && compare(left.doubleValue(), value.asDouble(), op);
    }

    /**
     * 到达某节点的次数:决策日志里以它为 toNodeId 的条数,起点再算开局那一次。
     * 只从已持久化的 decisionLog 推导,不给 state_json 加新字段,存量局读出来的数与新局一致。
     */
    private int visitCount(State state, long nodeId) {
        int count = 0;
        Long start = null;
        for (Map<String, Object> item : state.decisionLog) {
            if (start == null && item.get("fromNodeId") instanceof Number) start = ((Number) item.get("fromNodeId")).longValue();
            Object to = item.get("toNodeId");
            if (to instanceof Number && ((Number) to).longValue() == nodeId) count++;
        }
        if (start == null) return state.visitedNodeIds.contains(nodeId) ? 1 : 0;
        return start == nodeId ? count + 1 : count;
    }

    private int held(State state, String itemId) {
        return state.themeRuntime.path("inventory").path(itemId).asInt();
    }

    private boolean compare(double left, double right, String op) {
        switch (op) {
            case "EQ": return Double.compare(left, right) == 0;
            case "NE": return Double.compare(left, right) != 0;
            case "GT": return left > right;
            case "GTE": return left >= right;
            case "LT": return left < right;
            case "LTE": return left <= right;
            default: throw new IllegalArgumentException("不支持的条件操作:" + op);
        }
    }

    /** 路线图效果应用(公开给旅程块配置复用:effects / successEffects / failEffects 同一套写法)。 */
    public void applyEffects(JsonNode effects, State state) {
        if (!effects.isArray()) return;
        for (JsonNode effect : effects) {
            String op = effect.path("op").asText();
            if ("ADD_TAG".equals(op)) {
                String tag = effect.path("value").asText();
                if (!tag.isEmpty()) state.tags.add(tag);
                continue;
            }
            if (StoryVocabulary.EFFECT_OPS.contains(op)) {
                applyStoryEffect(op, effect, state);
                continue;
            }
            String var = effect.path("var").asText();
            if (var.isEmpty()) throw new IllegalArgumentException("effect 变量名不能为空");
            Number value = effect.path("value").numberValue();
            Number held = state.variables.get(var);
            // 已获得的线索证据（clue.h_*）只增不减：后续 SET/INC 不得撤回。
            if (var.startsWith("clue.h_") && value != null && held != null && held.doubleValue() >= 1 && ("SET".equals(op) || "INC".equals(op))) {
                double next = "SET".equals(op) ? value.doubleValue() : held.doubleValue() + value.doubleValue();
                if (next < held.doubleValue()) continue;
            }
            if ("SET".equals(op)) state.variables.put(var, value);
            else if ("INC".equals(op)) {
                Number old = state.variables.get(var);
                state.variables.put(var, (old == null ? 0D : old.doubleValue()) + value.doubleValue());
            } else if ("FLOOR".equals(op) && var.startsWith("counter.")) {
                Number old = state.variables.get(var);
                state.variables.put(var, Math.max(old == null ? 0D : old.doubleValue(), value.doubleValue()));
            } else throw new IllegalArgumentException("不支持的 effect:" + op);
        }
    }

    /**
     * F5 词汇的效果。全部只写本局 state(与路线推进同一次 CAS 提交),不查库、不碰资金:
     * <ul>
     *   <li>ADD_ITEM 只挂成待领取(themeRuntime.pending),容量与目录仍由背包领取流程裁决;</li>
     *   <li>GRANT_BADGE / GRANT_XP 只记进本局发放台账 storyGrants,键 = 种类 + 节点 + 代码,
     *       同一局同一节点重放(含 allowLoop 回到同一站)不会二记;</li>
     *   <li>奖励上限 XP_BADGE_ONLY(自报传感 / 滤镜完成)下只放行 XP 与徽章,ADD_ITEM 静默不发。</li>
     * </ul>
     */
    private void applyStoryEffect(String op, JsonNode effect, State state) {
        String node = state.currentNodeId == null ? "run" : String.valueOf(state.currentNodeId);
        switch (op) {
            case StoryVocabulary.ADD_ITEM: {
                if (state.rewardCeilingXpBadgeOnly) return;
                String itemId = effect.path("itemId").asText();
                int quantity = effect.path("quantity").asInt(1);
                if (itemId.isEmpty() || quantity < 1 || quantity > StoryVocabulary.MAX_ITEM_QUANTITY) {
                    throw new IllegalArgumentException("ADD_ITEM 物资或数量无效");
                }
                if (!state.themeRuntime.path("pending").isObject()) {
                    throw new IllegalStateException("本主题未启用物资背包，不能 ADD_ITEM");
                }
                String source = "story:" + node + ":" + itemId;
                if (state.themeRuntime.with("sources").has(source)) return;
                state.themeRuntime.with("sources").put(source, true);
                state.themeRuntime.with("pending").putObject(source).put("itemId", itemId).put("quantity", quantity);
                return;
            }
            case StoryVocabulary.GRANT_BADGE: {
                String code = effect.path("value").asText();
                if (code.isEmpty()) throw new IllegalArgumentException("GRANT_BADGE 徽章代码不能为空");
                String key = "BADGE:" + node + ":" + code;
                if (state.storyGrants.containsKey(key)) return;
                Map<String, Object> grant = new LinkedHashMap<String, Object>();
                grant.put("kind", "BADGE"); grant.put("code", code); grant.put("node", node);
                state.storyGrants.put(key, grant);
                return;
            }
            case StoryVocabulary.GRANT_XP: {
                JsonNode amount = effect.get("value");
                if (amount == null || !amount.canConvertToInt() || amount.asInt() < 1 || amount.asInt() > StoryVocabulary.MAX_GRANT_XP) {
                    throw new IllegalArgumentException("GRANT_XP 数值无效");
                }
                String key = "XP:" + node + ":" + effect.path("id").asText("xp");
                if (state.storyGrants.containsKey(key)) return;
                Map<String, Object> grant = new LinkedHashMap<String, Object>();
                grant.put("kind", "XP"); grant.put("amount", amount.asInt()); grant.put("node", node);
                state.storyGrants.put(key, grant);
                return;
            }
            case StoryVocabulary.ADD_CONDITION:
            case StoryVocabulary.REMOVE_CONDITION: {
                String value = effect.path("value").asText();
                if (value.isEmpty()) throw new IllegalArgumentException(op + " 状态不能为空");
                if (StoryVocabulary.ADD_CONDITION.equals(op)) state.statusConditions.add(value);
                else state.statusConditions.remove(value);
                return;
            }
            case StoryVocabulary.EQUIP: {
                String itemId = effect.path("itemId").asText();
                if (itemId.isEmpty()) throw new IllegalArgumentException("EQUIP 物资不能为空");
                // 没持有就无从装备:不报错、不凭空造物,与条件侧 ITEM_EQUIPPED 同时要求持有一致。
                if (held(state, itemId) < 1) return;
                state.equipped.put(effect.path("slot").asText(itemId), itemId);
                return;
            }
            case StoryVocabulary.UNEQUIP: {
                String itemId = effect.path("itemId").asText();
                state.equipped.values().removeIf(itemId::equals);
                return;
            }
            case StoryVocabulary.TICK_CLOCK: {
                String var = effect.path("var").asText();
                int ticks = effect.path("value").asInt(0);
                int max = effect.path("max").asInt(0);
                if (!var.startsWith("counter.") || ticks < 1 || max < StoryVocabulary.MIN_CLOCK_SEGMENTS
                        || max > StoryVocabulary.MAX_CLOCK_SEGMENTS) {
                    throw new IllegalArgumentException("TICK_CLOCK 配置无效");
                }
                Number old = state.variables.get(var);
                double base = old == null ? 0D : Math.max(0D, old.doubleValue());
                // 钟只往前走:已超过 max 的旧值(作者另用 SET 写过)不被拉回。
                state.variables.put(var, Math.max(base, Math.min((double) max, base + ticks)));
                return;
            }
            default:
                throw new IllegalArgumentException("不支持的 effect:" + op);
        }
    }

    private int visits(State state, String edgeId) {
        if (state.edgeVisits == null) return state.usedEdgeIds != null && state.usedEdgeIds.contains(edgeId) ? 1 : 0;
        Integer count = state.edgeVisits.get(edgeId);
        return count == null ? 0 : count;
    }

    private JsonNode weighted(List<JsonNode> choices, long seed, int pull) {
        if (choices.size() == 1) return choices.get(0);
        long total = 0L;
        for (JsonNode choice : choices) total += Math.max(1, choice.path("weight").asInt(1));
        long mixed = seed ^ (0x9E3779B97F4A7C15L * (pull + 1L));
        long selected = Math.floorMod(new Random(mixed).nextLong(), total);
        long cursor = 0L;
        for (JsonNode choice : choices) {
            cursor += Math.max(1, choice.path("weight").asInt(1));
            if (selected < cursor) return choice;
        }
        return choices.get(choices.size() - 1);
    }

    private void appendLog(State state, Long from, String outcome, String edge, Long to, String reason, Date decisionAt) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("fromNodeId", from);
        item.put("outcomeCode", outcome);
        item.put("edgeId", edge);
        item.put("toNodeId", to);
        item.put("reason", reason);
        item.put("at", decisionAt == null ? new Date(0L) : new Date(decisionAt.getTime()));
        state.decisionLog.add(item);
    }

    private Set<Long> allNodeIds(JsonNode config) {
        Set<Long> ids = new LinkedHashSet<Long>();
        ids.add(requiredLong(config, "startNodeId"));
        addLongs(ids, config.path("terminalNodeIds"));
        JsonNode edges = config.path("edges");
        if (edges.isArray()) for (JsonNode edge : edges) {
            ids.add(requiredLong(edge, "fromNodeId")); ids.add(requiredLong(edge, "toNodeId"));
        }
        JsonNode fallbacks = config.path("fallbacks");
        if (fallbacks.isArray()) for (JsonNode edge : fallbacks) {
            ids.add(requiredLong(edge, "fromNodeId")); ids.add(requiredLong(edge, "toNodeId"));
        }
        return ids;
    }

    private void addLongs(Set<Long> out, JsonNode array) {
        if (array.isArray()) for (JsonNode item : array) out.add(item.asLong());
    }

    private boolean containsLong(JsonNode array, long value) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (item.asLong() == value) return true;
        return false;
    }

    private Map<String, Number> readNumberMap(JsonNode node) {
        Map<String, Number> result = new LinkedHashMap<String, Number>();
        if (node.isObject()) node.fields().forEachRemaining(e -> {
            if (e.getValue().isNumber()) result.put(e.getKey(), e.getValue().numberValue());
        });
        return result;
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.asLong() <= 0L) {
            throw new IllegalArgumentException(field + " 无效");
        }
        return value.asLong();
    }

    private JsonNode read(String json) {
        try { return JSON.readTree(json); }
        catch (Exception e) { throw new IllegalArgumentException("路线配置 JSON 无效", e); }
    }

    public static final class Decision {
        private final State state;
        private final String selectedEdgeId;
        private final String reason;
        private Decision(State state, String selectedEdgeId, String reason) {
            this.state = state; this.selectedEdgeId = selectedEdgeId; this.reason = reason;
        }
        public State getState() { return state; }
        public String getSelectedEdgeId() { return selectedEdgeId; }
        public String getReason() { return reason; }
    }

    public static final class State {
        /**
         * 力竭标志的键与判据(阶段 3)。放在 State 上是因为它有**两个消费方**:
         * 运行期(roll 拒绝)与 Encounter(下发给前端解释「为什么掷不了」)。
         * 各写一份判据,flag 取 1 之外的值时两边就会给出相反答案 —— 前端说没力竭、
         * 服务端却拒掉,正是这次改动要消灭的「灰着不给理由」。
         *
         * <p>⚠️ 必须 @JsonIgnore:State 会整体序列化进 state_json,一个 isXxx() getter
         * 会被 Jackson 当成新字段写出去,下次读回来直接 UnrecognizedPropertyException。
         */
        public static final String SYS_EXHAUSTED = "sys.exhausted";

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isExhausted() {
            Number flag = variables.get(SYS_EXHAUSTED);
            return flag != null && flag.intValue() != 0;
        }

        private String status;
        private Long currentNodeId;
        private Long recommendedNodeId;
        private Map<Long, String> nodeStates = new LinkedHashMap<Long, String>();
        private Set<Long> completedNodeIds = new LinkedHashSet<Long>();
        private Set<Long> visitedNodeIds = new LinkedHashSet<Long>();
        private Map<String, Number> variables = new LinkedHashMap<String, Number>();
        private Set<String> tags = new LinkedHashSet<String>();
        private Set<String> usedEdgeIds = new LinkedHashSet<String>();
        private Map<String, Integer> edgeVisits = new LinkedHashMap<String, Integer>();
        private List<Map<String, Object>> decisionLog = new ArrayList<Map<String, Object>>();
        private int pull;
        private com.fasterxml.jackson.databind.node.ObjectNode themeRuntime = JSON.createObjectNode();
        /** F5:ADD_CONDITION / REMOVE_CONDITION 维护的状态(如 poisoned);空集合不序列化,存量局 state_json 不变。 */
        private Set<String> statusConditions = new LinkedHashSet<String>();
        /** F5:装备槽 → itemId;slot 缺省即 itemId 本身。 */
        private Map<String, String> equipped = new LinkedHashMap<String, String>();
        /** F5:本局 XP / 徽章发放台账,键 = 种类:节点:代码,存在即不再记(重放幂等)。 */
        private Map<String, Map<String, Object>> storyGrants = new LinkedHashMap<String, Map<String, Object>>();
        /** F6:环境键快照(weather / daypart / weekpart / season → 取值);只在主题开了环境键时写,空不序列化。 */
        private Map<String, String> environment = new LinkedHashMap<String, String>();
        /** 调用方注入的本局角色(ROLE_IN 读);不落库,未注入时 ROLE_IN 一律不成立。 */
        private String actorRoleCode;
        /** 调用方注入的奖励上限:true = 只放行 XP / 徽章(自报传感完成)。不落库。 */
        private boolean rewardCeilingXpBadgeOnly;

        public State() {}
        public State copy() {
            State out = new State();
            out.status = status; out.currentNodeId = currentNodeId; out.recommendedNodeId = recommendedNodeId;
            out.nodeStates.putAll(nodeStates); out.completedNodeIds.addAll(completedNodeIds);
            out.visitedNodeIds.addAll(visitedNodeIds); out.variables.putAll(variables); out.tags.addAll(tags);
            out.usedEdgeIds.addAll(usedEdgeIds); out.edgeVisits.putAll(edgeVisits);
            out.decisionLog.addAll(decisionLog); out.pull = pull;
            out.themeRuntime = themeRuntime.deepCopy();
            out.statusConditions.addAll(statusConditions); out.equipped.putAll(equipped);
            for (Map.Entry<String, Map<String, Object>> grant : storyGrants.entrySet()) {
                out.storyGrants.put(grant.getKey(), new LinkedHashMap<String, Object>(grant.getValue()));
            }
            out.environment.putAll(environment);
            out.actorRoleCode = actorRoleCode; out.rewardCeilingXpBadgeOnly = rewardCeilingXpBadgeOnly;
            return out;
        }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getCurrentNodeId() { return currentNodeId; }
        public void setCurrentNodeId(Long currentNodeId) { this.currentNodeId = currentNodeId; }
        public Long getRecommendedNodeId() { return recommendedNodeId; }
        public void setRecommendedNodeId(Long recommendedNodeId) { this.recommendedNodeId = recommendedNodeId; }
        public Map<Long, String> getNodeStates() { return nodeStates; }
        public void setNodeStates(Map<Long, String> nodeStates) { this.nodeStates = nodeStates; }
        public Set<Long> getCompletedNodeIds() { return completedNodeIds; }
        public void setCompletedNodeIds(Set<Long> completedNodeIds) { this.completedNodeIds = completedNodeIds; }
        public Set<Long> getVisitedNodeIds() { return visitedNodeIds; }
        public void setVisitedNodeIds(Set<Long> visitedNodeIds) { this.visitedNodeIds = visitedNodeIds; }
        public Map<String, Number> getVariables() { return variables; }
        public void setVariables(Map<String, Number> variables) { this.variables = variables; }
        public Set<String> getTags() { return tags; }
        public void setTags(Set<String> tags) { this.tags = tags; }
        public Set<String> getUsedEdgeIds() { return usedEdgeIds; }
        public void setUsedEdgeIds(Set<String> usedEdgeIds) { this.usedEdgeIds = usedEdgeIds; }
        public Map<String, Integer> getEdgeVisits() { return edgeVisits; }
        public void setEdgeVisits(Map<String, Integer> edgeVisits) { this.edgeVisits = edgeVisits; }
        public List<Map<String, Object>> getDecisionLog() { return decisionLog; }
        public void setDecisionLog(List<Map<String, Object>> decisionLog) { this.decisionLog = decisionLog; }
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public com.fasterxml.jackson.databind.node.ObjectNode getThemeRuntime() { return themeRuntime; }
        public void setThemeRuntime(com.fasterxml.jackson.databind.node.ObjectNode value) { themeRuntime = value == null ? JSON.createObjectNode() : value; }
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public Set<String> getStatusConditions() { return statusConditions; }
        public void setStatusConditions(Set<String> value) { statusConditions = value == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(value); }
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public Map<String, String> getEquipped() { return equipped; }
        public void setEquipped(Map<String, String> value) { equipped = value == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(value); }
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public Map<String, Map<String, Object>> getStoryGrants() { return storyGrants; }
        public void setStoryGrants(Map<String, Map<String, Object>> value) { storyGrants = value == null ? new LinkedHashMap<String, Map<String, Object>>() : new LinkedHashMap<String, Map<String, Object>>(value); }
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public Map<String, String> getEnvironment() { return environment; }
        public void setEnvironment(Map<String, String> value) { environment = value == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(value); }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getActorRoleCode() { return actorRoleCode; }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public void setActorRoleCode(String value) { actorRoleCode = value; }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isRewardCeilingXpBadgeOnly() { return rewardCeilingXpBadgeOnly; }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public void setRewardCeilingXpBadgeOnly(boolean value) { rewardCeilingXpBadgeOnly = value; }
        public int getPull() { return pull; }
        public void setPull(int pull) { this.pull = pull; }
    }
}
