package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class TrustedNodeEffects {
    private TrustedNodeEffects() { }

    public static ArrayNode rootEffects(JsonNode snapshot, Long nodeId) {
        JsonNode effects = snapshot.path(NodeJourneySnapshot.NODE_JOURNEY_KEY).path(String.valueOf(nodeId)).path("effects");
        return effects.isArray() ? (ArrayNode) effects.deepCopy() : JsonNodeFactory.instance.arrayNode();
    }

    public static void appendRootOnce(JsonNode snapshot, Long nodeId, String instance, ArrayNode effects, ObjectNode sources) {
        String key = instance + ":node-effects:" + nodeId;
        if (sources.has(key)) return;
        effects.addAll(rootEffects(snapshot, nodeId));
        sources.put(key, true);
    }
}
