package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.route.NodeOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5 金样:存量主题的条件 / 效果在新引擎下与改动前逐条一致。
 *
 * <p>判据是本类里**原样抄下来的旧引擎求值**(master 的 conditionsPass / applyEffects,
 * 外加 #486 / #512 已带的 clue.h_* 只增不减守卫)。把测试资源里所有存量夹具的条件数组与效果数组
 * 全部抽出来,在一批确定性伪随机状态上对比:结果、抛错类型与中文文案、写后的变量与 tag 都必须相同。
 */
class TopicRouteEngineGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] CONDITION_KEYS = {"conditions", "requires", "when", "advantageIf", "disadvantageIf"};
    private static final String[] EFFECT_KEYS = {"effects", "successEffects", "failEffects", "critEffects",
            "fumbleEffects", "worldEffects"};
    private static final int STATES_PER_ARRAY = 64;

    @Test
    void everyExistingFixtureConditionAndEffectBehavesExactlyAsBefore() throws Exception {
        List<JsonNode> conditions = new ArrayList<JsonNode>();
        List<JsonNode> effects = new ArrayList<JsonNode>();
        for (JsonNode root : fixtures()) collect(root, conditions, effects);
        assertTrue(conditions.size() >= 20, "存量条件夹具过少:" + conditions.size());
        assertTrue(effects.size() >= 20, "存量效果夹具过少:" + effects.size());

        Universe universe = new Universe();
        for (JsonNode array : conditions) universe.learn(array);
        for (JsonNode array : effects) universe.learn(array);

        TopicRouteEngine engine = new TopicRouteEngine();
        Random random = new Random(20261022L);
        int compared = 0;
        for (JsonNode array : conditions) {
            for (int i = 0; i < STATES_PER_ARRAY; i++) {
                TopicRouteEngine.State state = universe.sample(random);
                assertEquals(outcome(() -> LegacyEngine.conditionsPass(array, state.copy())),
                        outcome(() -> engine.conditionsPass(array, state.copy())), array.toString());
                compared++;
            }
        }
        for (JsonNode array : effects) {
            for (int i = 0; i < STATES_PER_ARRAY; i++) {
                TopicRouteEngine.State state = universe.sample(random);
                TopicRouteEngine.State legacy = state.copy();
                TopicRouteEngine.State current = state.copy();
                String expected = outcome(() -> { LegacyEngine.applyEffects(array, legacy); return snapshot(legacy); });
                String actual = outcome(() -> { engine.applyEffects(array, current); return snapshot(current); });
                assertEquals(expected, actual, array.toString());
                compared++;
            }
        }
        assertTrue(compared > 2000);
    }

    /** 契约金样路线整局推进:决策、状态、序列化后的 state_json 全部与旧引擎一致(无新增字段)。 */
    @Test
    void contractRouteGraphReplaysToTheSameStateJson() throws Exception {
        String graph = read(Paths.get(TopicRouteEngineGoldenTest.class.getResource("/contracts/topic-route-graph-v1.json").toURI()));
        TopicRouteEngine engine = new TopicRouteEngine();
        TopicRouteEngine.State state = engine.initial(graph);
        state = engine.advance(graph, state, "CHOICE", new NodeOutcome(1L, "COMPLETED", "r1"), 7L, java.util.Collections.<Long>emptySet(),
                new java.util.Date(0L)).getState();
        state = engine.advance(graph, state, "CHOICE", new NodeOutcome(2L, "COMPLETED", "r2"), 7L, java.util.Collections.<Long>emptySet(),
                new java.util.Date(0L)).getState();
        assertEquals(3L, state.getCurrentNodeId());
        assertEquals(2, state.getVariables().get("clues").intValue());
        JsonNode written = JSON.readTree(JSON.writeValueAsString(state));
        Set<String> fields = new LinkedHashSet<String>();
        written.fieldNames().forEachRemaining(fields::add);
        assertEquals(new LinkedHashSet<String>(java.util.Arrays.asList("status", "currentNodeId", "recommendedNodeId",
                "nodeStates", "completedNodeIds", "visitedNodeIds", "variables", "tags", "usedEdgeIds", "edgeVisits",
                "decisionLog", "pull")), fields);
    }

    // ---------- 夹具收集 ----------

    private static List<JsonNode> fixtures() throws IOException, URISyntaxException {
        Path root = Paths.get(TopicRouteEngineGoldenTest.class.getResource("/contracts").toURI()).getParent();
        List<JsonNode> out = new ArrayList<JsonNode>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList())) {
                try { out.add(JSON.readTree(read(file))); }
                catch (IOException notJson) { /* 非 JSON 夹具不参与 */ }
            }
        }
        return out;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void collect(JsonNode node, List<JsonNode> conditions, List<JsonNode> effects) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (contains(CONDITION_KEYS, field.getKey())) {
                    if (value.isArray() && opsOnly(value)) conditions.add(value);
                    else if (value.isObject() && value.has("op")) conditions.add(JSON.createArrayNode().add(value));
                }
                if (contains(EFFECT_KEYS, field.getKey()) && value.isArray() && opsOnly(value)) effects.add(value);
                collect(value, conditions, effects);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) collect(item, conditions, effects);
        }
    }

    private static boolean opsOnly(JsonNode array) {
        if (array.size() == 0) return false;
        for (JsonNode item : array) if (!item.isObject() || !item.has("op")) return false;
        return true;
    }

    private static boolean contains(String[] values, String value) {
        for (String candidate : values) if (candidate.equals(value)) return true;
        return false;
    }

    // ---------- 状态采样 ----------

    private static final class Universe {
        private final Set<String> vars = new LinkedHashSet<String>();
        private final Set<String> tags = new LinkedHashSet<String>();
        private final Set<Long> nodes = new LinkedHashSet<Long>();
        private final Set<String> items = new LinkedHashSet<String>();

        void learn(JsonNode array) {
            for (JsonNode item : array) {
                if (item.hasNonNull("var")) vars.add(item.path("var").asText());
                if ("HAS_TAG".equals(item.path("op").asText()) || "ADD_TAG".equals(item.path("op").asText())) tags.add(item.path("value").asText());
                if (item.has("nodeId")) nodes.add(item.path("nodeId").asLong());
                if (item.has("itemId")) items.add(item.path("itemId").asText());
            }
        }

        TopicRouteEngine.State sample(Random random) {
            int[] values = {-1, 0, 1, 2, 3, 5, 10};
            TopicRouteEngine.State state = new TopicRouteEngine.State();
            for (String var : vars) if (random.nextInt(4) > 0) state.getVariables().put(var, values[random.nextInt(values.length)]);
            for (String tag : tags) if (random.nextBoolean()) state.getTags().add(tag);
            for (Long node : nodes) if (random.nextBoolean()) state.getCompletedNodeIds().add(node);
            if (!items.isEmpty()) {
                for (String item : items) {
                    state.getThemeRuntime().with("inventory").put(item, random.nextInt(3));
                    state.getThemeRuntime().with("acquired").put(item, random.nextInt(3));
                }
            }
            return state;
        }
    }

    private interface Body { Object run(); }

    private static String outcome(Body body) {
        try { return "OK " + body.run(); }
        catch (RuntimeException error) { return error.getClass().getSimpleName() + " " + error.getMessage(); }
    }

    private static String snapshot(TopicRouteEngine.State state) {
        Map<String, Double> numbers = new java.util.TreeMap<String, Double>();
        for (Map.Entry<String, Number> entry : state.getVariables().entrySet()) {
            numbers.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().doubleValue());
        }
        return numbers + " " + state.getTags() + " " + state.getThemeRuntime();
    }

    /** 改动前的求值,逐字抄自 master(effects 段含 #486 / #512 的 clue.h_* 守卫)。不得改。 */
    private static final class LegacyEngine {
        static boolean conditionsPass(JsonNode conditions, TopicRouteEngine.State state) {
            if (!conditions.isArray()) return true;
            for (JsonNode condition : conditions) {
                String op = condition.path("op").asText();
                if ("ITEM_HELD".equals(op) || "ITEM_ACQUIRED".equals(op)) {
                    String field = "ITEM_HELD".equals(op) ? "inventory" : "acquired";
                    if (state.getThemeRuntime().path(field).path(condition.path("itemId").asText()).asInt() < condition.path("quantity").asInt()) return false;
                    continue;
                }
                if ("NODE_COMPLETED".equals(op)) {
                    if (!state.getCompletedNodeIds().contains(condition.path("nodeId").asLong())) return false;
                    continue;
                }
                if ("HAS_TAG".equals(op)) {
                    if (!state.getTags().contains(condition.path("value").asText())) return false;
                    continue;
                }
                Number left = state.getVariables().get(condition.path("var").asText());
                JsonNode value = condition.get("value");
                if (left == null || value == null || !compare(left.doubleValue(), value.asDouble(), op)) return false;
            }
            return true;
        }

        private static boolean compare(double left, double right, String op) {
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

        static void applyEffects(JsonNode effects, TopicRouteEngine.State state) {
            if (!effects.isArray()) return;
            for (JsonNode effect : effects) {
                String op = effect.path("op").asText();
                if ("ADD_TAG".equals(op)) {
                    String tag = effect.path("value").asText();
                    if (!tag.isEmpty()) state.getTags().add(tag);
                    continue;
                }
                String var = effect.path("var").asText();
                if (var.isEmpty()) throw new IllegalArgumentException("effect 变量名不能为空");
                Number value = effect.path("value").numberValue();
                Number held = state.getVariables().get(var);
                if (var.startsWith("clue.h_") && value != null && held != null && held.doubleValue() >= 1 && ("SET".equals(op) || "INC".equals(op))) {
                    double next = "SET".equals(op) ? value.doubleValue() : held.doubleValue() + value.doubleValue();
                    if (next < held.doubleValue()) continue;
                }
                if ("SET".equals(op)) state.getVariables().put(var, value);
                else if ("INC".equals(op)) {
                    Number old = state.getVariables().get(var);
                    state.getVariables().put(var, (old == null ? 0D : old.doubleValue()) + value.doubleValue());
                } else if ("FLOOR".equals(op) && var.startsWith("counter.")) {
                    Number old = state.getVariables().get(var);
                    state.getVariables().put(var, Math.max(old == null ? 0D : old.doubleValue(), value.doubleValue()));
                } else throw new IllegalArgumentException("不支持的 effect:" + op);
            }
        }
    }
}
