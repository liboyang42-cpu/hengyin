package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** DEC02 runtime adapter. Validation belongs to the shared frozen-definition reader. */
public final class FrozenAttributeDefinitions {
    private FrozenAttributeDefinitions() { }

    /** Only called while producing a new frozen snapshot, never while reading an existing run. */
    public static void copyPublishedDefinition(ObjectNode snapshot) {
        JsonNode published = snapshot.path(NodeJourneySnapshot.JOURNEY_RULES_KEY).get(ThemeAttributeDefinition.KEY);
        if (published == null || published.isNull()) return; // Legacy publication, not a complete definition.
        JsonNode existing = snapshot.get(ThemeAttributeDefinition.KEY);
        if (existing != null && !existing.equals(published)) {
            throw new IllegalArgumentException("发布属性定义与根快照不一致");
        }
        snapshot.set(ThemeAttributeDefinition.KEY, published.deepCopy());
        rules(snapshot); // Validate complete metadata; never fill from the live registry.
    }

    public static boolean complete(JsonNode snapshot) {
        ThemeAttributeDefinition.Snapshot definition = ThemeAttributeDefinition.read(snapshot == null ? null : snapshot.toString());
        return definition != null && definition.complete;
    }

    public static JourneyRules rules(JsonNode snapshot) {
        return rules(snapshot, null, false);
    }

    public static JourneyRules rules(JsonNode snapshot, Long configVersion) {
        return rules(snapshot, configVersion, true);
    }

    private static JourneyRules rules(JsonNode snapshot, Long configVersion, boolean checkVersion) {
        JourneyRules rules = JourneyRules.parse(snapshot.path(NodeJourneySnapshot.JOURNEY_RULES_KEY).toString());
        ThemeAttributeDefinition.Snapshot definition = ThemeAttributeDefinition.read(snapshot.toString());
        // No marker means legacy partial. Do not look up registry metadata or infer persistence.
        if (definition == null) return rules;
        if (!definition.complete || checkVersion && (configVersion == null
                || !definition.configVersion.equals(configVersion.toString()))
                || !rules.isStateEnabled() && !definition.keys.isEmpty()) {
            throw new IllegalArgumentException("完整属性冻结定义不可用，不能以实时注册表补齐");
        }
        rules.setAttributes(((ArrayNode) snapshot.path(ThemeAttributeDefinition.KEY).path("attributes")).deepCopy());
        return rules;
    }
}
