package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 通用主题初始属性；运行期仍写入既有路线变量和标签，不创建第二份角色状态。 */
public final class JourneyInitialState {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JourneyInitialState() { }

    /** Complete author definitions only; old key/label snapshots return null, never invented types. */
    public static List<ThemeStateKey> frozenDefinitions(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.trim().isEmpty()) return null;
        final JsonNode snapshot;
        try { snapshot = JSON.readTree(snapshotJson); }
        catch (Exception invalid) { throw new IllegalArgumentException("状态键快照不可用", invalid); }
        return frozenDefinitions(snapshot);
    }

    public static List<ThemeStateKey> frozenDefinitions(JsonNode snapshot) {
        if (snapshot == null || !snapshot.has("stateKeySnapshotVersion")) return null;
        JsonNode version = snapshot.get("stateKeySnapshotVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() != 1
                || !snapshot.path("stateKeys").isArray()) throw new IllegalArgumentException("状态键定义快照不可用");
        List<ThemeStateKey> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode row : snapshot.path("stateKeys")) {
            if (!row.isObject() || !row.path("key").isTextual()
                    || !row.path("stateKey").isTextual() || !row.path("key").asText().equals(row.path("stateKey").asText())
                    || !row.path("topicId").isIntegralNumber() || !row.path("topicId").canConvertToLong() || row.path("topicId").asLong() <= 0
                    || !ThemeStateKey.KEY_NAME.matcher(row.path("key").asText()).matches()
                    || !names.add(row.path("key").asText())) throw new IllegalArgumentException("状态键定义快照不可用");
            for (String field : java.util.Arrays.asList("keyType", "scope", "label", "displayType", "color", "icon", "persistScope", "rulesJson")) {
                JsonNode value = row.get(field);
                if (value == null || !value.isNull() && !value.isTextual()) throw new IllegalArgumentException("状态键定义快照不可用");
            }
            for (String field : java.util.Arrays.asList("initialValue", "minValue", "maxValue", "sortOrder")) {
                JsonNode value = row.get(field);
                if (value == null || !value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt()))
                    throw new IllegalArgumentException("状态键定义快照不可用");
            }
            if (!row.has("visible") || !row.path("visible").isNull() && !row.path("visible").isBoolean())
                throw new IllegalArgumentException("状态键定义快照不可用");
            ThemeStateKey key = new ThemeStateKey();
            key.setTopicId(row.path("topicId").asLong());
            key.setStateKey(row.path("key").asText());
            key.setKeyType(row.path("keyType").asText(null));
            key.setScope(row.path("scope").asText(null));
            key.setLabel(row.path("label").asText(null));
            key.setInitialValue(row.hasNonNull("initialValue") ? row.path("initialValue").asInt() : null);
            key.setMinValue(row.hasNonNull("minValue") ? row.path("minValue").asInt() : null);
            key.setMaxValue(row.hasNonNull("maxValue") ? row.path("maxValue").asInt() : null);
            key.setSortOrder(row.hasNonNull("sortOrder") ? row.path("sortOrder").asInt() : null);
            key.setDisplayType(row.path("displayType").asText(null));
            key.setColor(row.path("color").asText(null));
            key.setIcon(row.path("icon").asText(null));
            key.setPersistScope(row.path("persistScope").asText(null));
            key.setRulesJson(row.path("rulesJson").asText(null));
            key.setVisible(row.hasNonNull("visible") ? row.path("visible").asBoolean() : null);
            result.add(key);
        }
        return result;
    }

    /** New definitions must travel with their original numeric/visibility rules. */
    public static String frozenDefinitionRules(String snapshotJson) {
        if (frozenDefinitions(snapshotJson) == null) return null;
        String rules = NodeJourneySnapshot.journeyRules(snapshotJson);
        if (rules == null) throw new IllegalArgumentException("状态键规则快照不可用");
        return rules;
    }


    /** 数值目标只引用已启用的 counter 属性，不以显示名或幸运值替代。 */
    public static JsonNode numericAttribute(JourneyRules rules, String key) {
        if (rules != null && rules.isStateEnabled() && key != null && key.startsWith("counter.")) {
            for (JsonNode row : rules.getAttributes()) if (key.equals(row.path("key").asText())) return row;
        }
        throw new IllegalArgumentException("数值属性未启用或未注册：" + key);
    }

    public static int numericValue(TopicRouteEngine.State state, String key) {
        Number value = state.getVariables().get(key);
        if (value == null || !Double.isFinite(value.doubleValue()) || value.doubleValue() != value.intValue()) {
            throw new IllegalArgumentException("数值状态必须为已初始化的整数：" + key);
        }
        return value.intValue();
    }

    /** 仅用于已复制的玩家投影；保留服务端原始审计和结算差量。 */
    public static void redactHidden(JourneyRules rules, JsonNode projection) {
        Set<String> hidden = new HashSet<>();
        for (JsonNode row : rules.getAttributes()) if (!row.path("visible").asBoolean(true)) hidden.add(row.path("key").asText());
        redactHidden(projection, hidden);
    }

    private static void redactHidden(JsonNode node, Set<String> hidden) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (String key : hidden) object.remove(key);
            if (hidden.contains(object.path("sourceKey").asText())) {
                object.remove(java.util.Arrays.asList("sourceKey", "sourceBefore", "sourceAfter", "sourceDelta"));
                object.put("sourceHidden", true);
            }
            if (hidden.contains(object.path("targetKey").asText())) {
                object.remove(java.util.Arrays.asList("targetKey", "targetBefore", "targetAfter", "actualDamage", "delta"));
            }
            if (hidden.contains(object.path("statKey").asText())) object.remove(java.util.Arrays.asList("statKey", "delta", "amount", "requested", "charged", "needed", "current"));
            node.elements().forEachRemaining(child -> redactHidden(child, hidden));
        } else if (node.isArray()) node.forEach(child -> redactHidden(child, hidden));
    }

    public static ArrayNode normalize(JsonNode source) {
        ArrayNode result = JSON.createArrayNode();
        if (source == null) return result;
        if (!source.isArray() || source.size() > 32) throw new IllegalArgumentException("初始属性最多配置 32 项");
        Set<String> keys = new HashSet<String>();
        for (JsonNode row : source) {
            String key = row.path("key").asText("");
            if (!ThemeStateKey.KEY_NAME.matcher(key).matches() || !keys.add(key)) {
                throw new IllegalArgumentException("初始属性标识无效或重复：" + key);
            }
            JsonNode labelNode = row.get("label");
            if (labelNode == null || !labelNode.isTextual() || labelNode.asText().trim().isEmpty()
                    || labelNode.asText().trim().length() > 24) throw new IllegalArgumentException("属性名称须为 1 至 24 字");
            boolean flag = key.startsWith("clue.") || key.startsWith("tag.");
            int min = integer(row, "min", flag ? 0 : -10000);
            int max = integer(row, "max", flag ? 1 : 10000);
            int initial = integer(row, "initial", 0);
            if (min < -10000 || max > 10000 || min > max || initial < min || initial > max
                    || (flag && (min != 0 || max != 1))) throw new IllegalArgumentException("属性初始值或上下限无效：" + labelNode.asText());
            JsonNode visible = row.get("visible");
            if (visible != null && !visible.isBoolean()) throw new IllegalArgumentException("属性可见性必须是开关值");
            ObjectNode target = result.addObject();
            target.put("key", key); target.put("label", labelNode.asText().trim());
            target.put("initial", initial); target.put("min", min); target.put("max", max);
            target.put("visible", visible == null || visible.asBoolean());
        }
        return result;
    }

    private static int integer(JsonNode row, String key, int fallback) {
        JsonNode value = row.get(key);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("属性 " + key + " 必须是整数");
        return value.intValue();
    }

    public static void initialize(JourneyRules rules, TopicRouteEngine.State state) {
        if (!rules.isStateEnabled()) return;
        for (JsonNode row : rules.getAttributes()) {
            String key = row.path("key").asText();
            if (key.startsWith("tag.")) {
                if (row.path("initial").asInt() == 1) state.getTags().add(key);
            } else state.getVariables().put(key, row.path("initial").asInt());
        }
    }

    public static void clamp(JourneyRules rules, TopicRouteEngine.State state) {
        if (!rules.isStateEnabled()) return;
        for (JsonNode row : rules.getAttributes()) {
            String key = row.path("key").asText();
            Number value = state.getVariables().get(key);
            if (value != null) {
                double bounded = Math.max(row.path("min").asInt(), Math.min(row.path("max").asInt(), value.doubleValue()));
                if (key.startsWith("counter.")) {
                    numericValue(state, key);
                    state.getVariables().put(key, (int) bounded);
                } else state.getVariables().put(key, bounded);
            }
        }
    }

    public static List<Map<String, Object>> playerView(JourneyRules rules, TopicRouteEngine.State state) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (!rules.isStateEnabled()) return result;
        for (JsonNode row : rules.getAttributes()) {
            if (!row.path("visible").asBoolean()) continue;
            String key = row.path("key").asText();
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("key", key); value.put("label", row.path("label").asText());
            value.put("min", row.path("min").asInt()); value.put("max", row.path("max").asInt());
            value.put("value", key.startsWith("tag.") ? (state.getTags().contains(key) ? 1 : 0)
                    : state.getVariables().getOrDefault(key, row.path("initial").asInt()));
            result.add(value);
        }
        return result;
    }

    /** 将展示键随本局冻结，防止后来新增的属性进入旧局案件册。 */
    public static void freezeKeys(ObjectNode snapshot, List<ThemeStateKey> keys, JourneyRules rules) {
        if (snapshot.has("attributeDefinition")) {
            JourneyRules frozenRules = FrozenAttributeDefinitions.rules(snapshot);
            ArrayNode frozen = snapshot.putArray("stateKeys");
            for (JsonNode attribute : frozenRules.getAttributes()) {
                frozen.addObject().put("key", attribute.path("key").asText())
                        .put("label", attribute.path("label").asText());
            }
            return;
        }
        ArrayNode frozen = snapshot.putArray("stateKeys");
        Map<String, ThemeStateKey> all = new LinkedHashMap<String, ThemeStateKey>();
        if (keys != null) for (ThemeStateKey key : keys) all.put(key.getStateKey(), key);
        for (JsonNode row : rules.getAttributes()) {
            ThemeStateKey key = new ThemeStateKey();
            key.setStateKey(row.path("key").asText()); key.setLabel(row.path("label").asText());
            all.put(key.getStateKey(), key);
        }
        for (ThemeStateKey key : all.values()) {
            ObjectNode row = frozen.addObject();
            row.put("key", key.getStateKey()); row.put("label", key.displayLabel());
        }
    }

    /** Fresh publication only: full author declarations, never session values or technical IDs. */
    public static void freezeKeys(ObjectNode snapshot, List<ThemeStateKey> keys, JourneyRules rules, Long topicId) {
        List<ThemeStateKey> complete = ThemeStateKeyDeclarationSnapshot.includingRuleAttributes(topicId, keys, rules);
        snapshot.set("stateKeys", ThemeStateKeyDeclarationSnapshot.capture(topicId, complete));
        snapshot.put("stateKeySnapshotVersion", 1);
    }

    public static List<ThemeStateKey> visibleKeys(List<ThemeStateKey> liveKeys, JourneyRules rules, String snapshot) {
        List<ThemeStateKey> definitions = frozenDefinitions(snapshot);
        if (definitions != null) return visibleKeys(definitions, rules);
        List<ThemeStateKey> keys = liveKeys;
        if (snapshot != null) {
            try {
                JsonNode frozen = JSON.readTree(snapshot).get("stateKeys");
                if (frozen != null) {
                    if (!frozen.isArray()) throw new IllegalArgumentException("状态键快照不可用");
                    keys = new ArrayList<ThemeStateKey>();
                    for (JsonNode row : frozen) {
                        ThemeStateKey key = new ThemeStateKey();
                        key.setStateKey(row.path("key").asText()); key.setLabel(row.path("label").asText());
                        keys.add(key);
                    }
                }
            } catch (Exception error) { throw new IllegalArgumentException("状态键快照不可用", error); }
        }
        return visibleKeys(keys, rules);
    }

    /** 案件册和结算共用本局冻结的可见性；旧主题未声明的键保留原有行为。 */
    public static List<ThemeStateKey> visibleKeys(List<ThemeStateKey> keys, JourneyRules rules) {
        Set<String> hidden = new HashSet<String>();
        for (JsonNode row : rules.getAttributes()) {
            if (!row.path("visible").asBoolean(true)) hidden.add(row.path("key").asText());
        }
        List<ThemeStateKey> visible = new ArrayList<ThemeStateKey>();
        for (ThemeStateKey key : keys) {
            if (key != null && !hidden.contains(key.getStateKey())) visible.add(key);
        }
        return visible;
    }

    /** 公共主题详情不暴露隐藏属性声明；编辑器及审核仍读取完整规则。 */
    public static String publicRules(String raw) {
        if (raw == null || raw.trim().isEmpty()) return raw;
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(raw);
            root.remove("statLinks");
            if (!root.has("attributes")) return root.toString();
            ArrayNode visible = JSON.createArrayNode();
            for (JsonNode row : root.path("attributes")) if (row.path("visible").asBoolean(true)) visible.add(row);
            root.set("attributes", visible);
            return JSON.writeValueAsString(root);
        } catch (Exception error) { throw new IllegalArgumentException("主题初始状态不可用", error); }
    }
}
