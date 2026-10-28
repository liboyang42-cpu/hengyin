package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** DEC02 A: 完整发布定义的读合同；读侧不补默认值，不访问实时注册表。 */
public final class ThemeAttributeDefinition {
    public static final String KEY = "attributeDefinition";
    private static final ObjectMapper JSON = new ObjectMapper();
    private ThemeAttributeDefinition() { }

    public static final class Snapshot {
        public final String configVersion;
        public final String registryVersion;
        public final List<ThemeStateKey> keys;
        public final boolean complete;
        private Snapshot(String version, String registryVersion, List<ThemeStateKey> keys, boolean complete) {
            this.configVersion = version; this.registryVersion = registryVersion; this.keys = Collections.unmodifiableList(keys); this.complete = complete;
        }
    }

    /** null仅表示旧快照没有标记；有标记但不完整/不支持绝不回落实时定义。 */
    public static Snapshot read(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonNode root = JSON.readTree(raw);
            if (root != null && root.isObject() && !root.has(KEY)) return null;
            JsonNode definition = root == null ? null : root.get(KEY);
            if (definition == null || !definition.isObject() || !definition.path("schemaVersion").isInt()
                    || definition.path("schemaVersion").asInt() != 1) return partial();
            JsonNode version = definition.path("configVersion");
            if (!version.isTextual() || !version.asText().matches("[1-9][0-9]{0,18}")) return partial();
            Long.parseLong(version.asText());
            JsonNode registryVersion = definition.path("registryVersion");
            if (!registryVersion.isTextual() || !registryVersion.asText().matches("[1-9][0-9]{0,18}")) return partial();
            Long.parseLong(registryVersion.asText());
            JsonNode rows = definition.path("attributes");
            if (!rows.isArray() || rows.size() > 32) return partial();
            List<ThemeStateKey> keys = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode row : rows) {
                for (String field : Arrays.asList("key", "keyType", "label", "initial", "min", "max", "visible",
                        "displayType", "color", "icon", "sortOrder", "persistScope", "rules")) {
                    if (!row.has(field)) return partial();
                }
                String name = row.path("key").asText();
                if (!row.path("key").isTextual() || !ThemeStateKey.KEY_NAME.matcher(name).matches() || !seen.add(name)
                        || !row.path("label").isTextual() || row.path("label").asText().trim().isEmpty()
                        || !row.path("visible").isBoolean() || !integer(row.path("initial"))
                        || !integer(row.path("min")) || !integer(row.path("max"))) return partial();
                String prefix = name.substring(0, name.indexOf('.'));
                String type = "clue".equals(prefix) ? "flag" : prefix;
                if (!type.equals(row.path("keyType").asText())
                        || !Arrays.asList("number", "bar", "stars").contains(row.path("displayType").asText())
                        || !Arrays.asList("run", "topic").contains(row.path("persistScope").asText())
                        || "topic".equals(row.path("persistScope").asText()) && !"counter".equals(type)) return partial();
                int min = row.path("min").asInt(), max = row.path("max").asInt(), initial = row.path("initial").asInt();
                if (min < -10000 || max > 10000 || min > max || initial < min || initial > max
                        || ("flag".equals(type) || "tag".equals(type)) && (min != 0 || max != 1)) return partial();
                if (!nullablePattern(row.get("color"), "#[0-9A-Fa-f]{6}")
                        || !nullablePattern(row.get("icon"), "[a-z0-9][a-z0-9-]{0,31}")
                        || !row.get("sortOrder").isNull() && !integer(row.get("sortOrder"))
                        || !row.get("rules").isNull() && !row.get("rules").isObject()) return partial();
                ThemeStateKey key = new ThemeStateKey();
                key.setStateKey(name); key.setKeyType(type); key.setScope("run"); key.setLabel(row.get("label").asText());
                key.setInitialValue(initial); key.setMinValue(min); key.setMaxValue(max); key.setVisible(row.get("visible").asBoolean());
                key.setDisplayType(row.get("displayType").asText()); key.setPersistScope(row.get("persistScope").asText());
                key.setColor(row.get("color").isNull() ? null : row.get("color").asText());
                key.setIcon(row.get("icon").isNull() ? null : row.get("icon").asText());
                key.setSortOrder(row.get("sortOrder").isNull() ? null : row.get("sortOrder").asInt());
                key.setRulesJson(row.get("rules").isNull() ? null : row.get("rules").toString());
                keys.add(key);
            }
            keys.sort(Comparator.comparing(k -> k.getSortOrder() == null ? Integer.MAX_VALUE : k.getSortOrder()));
            return new Snapshot(version.asText(), registryVersion.asText(), keys, true);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException invalid) {
            return partial();
        }
    }

    private static Snapshot partial() { return new Snapshot(null, null, Collections.emptyList(), false); }
    private static boolean integer(JsonNode node) { return node.isIntegralNumber() && node.canConvertToInt(); }
    private static boolean nullablePattern(JsonNode node, String pattern) {
        return node.isNull() || node.isTextual() && node.asText().matches(pattern);
    }
}
