package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Author declarations only. Never reads a session or materializes legacy NULL defaults. */
public final class ThemeStateKeyDeclarationSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] AUTHOR_FIELDS = ("stateKey keyType scope label initialValue minValue maxValue "
            + "displayType color icon sortOrder visible persistScope rulesJson").split(" ");
    private ThemeStateKeyDeclarationSnapshot() { }

    public static List<ThemeStateKey> copy(Long topicId, List<ThemeStateKey> declarations) {
        capture(topicId, declarations);
        List<ThemeStateKey> copied = new ArrayList<>();
        for (ThemeStateKey row : declarations) copied.add(JSON.convertValue(JSON.valueToTree(row), ThemeStateKey.class));
        return java.util.Collections.unmodifiableList(copied);
    }

    /** Original journeyRules may declare an attribute even when the old registry lacks it.
     * Keep every existing full declaration; derive only missing keys from those author rules. */
    public static List<ThemeStateKey> includingRuleAttributes(Long topicId, List<ThemeStateKey> declarations, JourneyRules rules) {
        java.util.Map<String, ThemeStateKey> all = new java.util.LinkedHashMap<>();
        for (ThemeStateKey row : copy(topicId, declarations)) all.put(row.getStateKey(), row);
        for (com.fasterxml.jackson.databind.JsonNode attribute : rules.getAttributes()) {
            String name = attribute.path("key").asText();
            if (all.containsKey(name)) {
                // Preserve the old rules-label precedence without replacing the full declaration.
                all.get(name).setLabel(attribute.path("label").asText());
                continue;
            }
            ThemeStateKey row = new ThemeStateKey(); row.setTopicId(topicId); row.setStateKey(name);
            String prefix = name.substring(0, name.indexOf('.'));
            row.setKeyType("clue".equals(prefix) ? "flag" : prefix); row.setScope(ThemeStateKey.SCOPE_RUN);
            row.setLabel(attribute.path("label").asText()); row.setInitialValue(attribute.path("initial").asInt());
            row.setMinValue(attribute.path("min").asInt()); row.setMaxValue(attribute.path("max").asInt());
            row.setVisible(attribute.path("visible").asBoolean());
            all.put(name, row);
        }
        return new ArrayList<>(all.values());
    }

    public static ArrayNode capture(Long topicId, List<ThemeStateKey> declarations) {
        if (topicId == null || topicId <= 0 || declarations == null) throw new IllegalStateException("主题属性声明缺失");
        List<ThemeStateKey> sorted = new ArrayList<>(declarations);
        for (ThemeStateKey row : sorted) {
            if (row == null || !topicId.equals(row.getTopicId()) || row.getStateKey() == null
                    || !ThemeStateKey.KEY_NAME.matcher(row.getStateKey()).matches())
                throw new IllegalStateException("主题属性声明身份不一致");
        }
        ArrayNode result = JSON.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (ThemeStateKey row : sorted) {
            if (!seen.add(row.getStateKey())) throw new IllegalStateException("主题属性声明重复");
            ObjectNode source = JSON.valueToTree(row);
            ObjectNode content = JSON.createObjectNode();
            content.put("topicId", topicId);
            content.put("key", row.getStateKey());
            for (String field : AUTHOR_FIELDS) content.set(field, source.has(field) ? source.get(field) : JSON.nullNode());
            // Preserve the original mapper order, without exposing allocator IDs/timestamps as content.
            result.add(content);
        }
        return result;
    }
}
