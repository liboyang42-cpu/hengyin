package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.ThemeRuntimeConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * qx-c1 #5(b) §23.1: arrival settings live in the sibling map {@code scenes[].nodeArrivals[nodeId]}, never in
 * {@code nodeConfigs[nodeId]} (which both the server template rules and the client treat as a full node-template
 * override). Save validation accepts and normalizes them, copy/adoption remaps them, references must be real nodes,
 * and publication freezes them into the route station.
 */
class ThemeNodeArrivalsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ARRIVAL = "{\"arrivalPolicy\":{\"radiusMeters\":60,\"maxAccuracyMeters\":40,\"maxAgeSeconds\":120},"
            + "\"arrivalReading\":{\"level\":2,\"log\":\"你听见钟声\"}}";

    private static String theme(String arrivals) {
        return "{\"schemaVersion\":1,\"currentScene\":\"s1\",\"scenes\":[{\"id\":\"s1\",\"name\":\"第一幕\","
                + "\"nodeArrivals\":" + arrivals + "}]}";
    }

    @Test
    void saveAcceptsAndNormalizesArrivalOnlyBlocksWithoutTouchingNodeConfigs() throws Exception {
        ObjectNode config = ThemeRuntimeConfig.parseDraft(theme("{\"301\":" + ARRIVAL + "}"));
        JsonNode scene = config.path("scenes").get(0);
        assertFalse(scene.has("nodeConfigs"), "arrival-only must not create a node template override");
        JsonNode row = scene.path(ThemeRuntimeConfig.NODE_ARRIVALS).path("301");
        assertEquals(60.0, row.path("arrivalPolicy").path("radiusMeters").asDouble());
        assertEquals("NONE", row.path("arrivalPolicy").path("fallbackMode").asText(), "normalized with defaults");
        assertEquals(2, row.path("arrivalReading").path("level").asInt());
    }

    @Test
    void saveRejectsAnythingButTheTwoArrivalFields() {
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(
                theme("{\"301\":{\"arrivalPolicy\":{\"radiusMeters\":60},\"qa\":{\"enabled\":true}}}")));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(theme("{\"301\":{}}")));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(theme("{\"abc\":" + ARRIVAL + "}")));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(
                theme("{\"301\":{\"arrivalPolicy\":{\"radiusMeters\":-1}}}")));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(
                theme("{\"301\":{\"arrivalReading\":{\"level\":9,\"log\":\"x\"}}}")));
    }

    @Test
    void referencesMustBeNodesOfThisTopicAndCopiesRemapThem() {
        ObjectNode config = ThemeRuntimeConfig.parseDraft(theme("{\"301\":" + ARRIVAL + "}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(config, Set.of(999L), Set.of()));
        ThemeRuntimeConfig.assertReferences(config, Set.of(301L), Set.of());
        String copied = ThemeRuntimeConfig.remapAll(config.toString(), Map.of(301L, 401L), Map.of(), Map.of());
        JsonNode remapped = ThemeRuntimeConfig.parseDraft(copied).path("scenes").get(0).path(ThemeRuntimeConfig.NODE_ARRIVALS);
        assertTrue(remapped.has("401") && !remapped.has("301"), remapped.toString());
    }

    @Test
    void clientKeysFromTheSameSaveAreRemappedToTheCreatedNode() {
        String raw = theme("{\"client:new-a\":" + ARRIVAL + "}");
        assertFalse(ThemeRuntimeConfig.withoutClientBindings(raw).contains("client:new-a"));
        String saved = ThemeRuntimeConfig.remapForNodeSave(raw, Map.of(), Map.of(), Map.of("new-a", 512L));
        assertTrue(JSON.valueToTree(ThemeRuntimeConfig.parseDraft(saved)).path("scenes").get(0)
                .path(ThemeRuntimeConfig.NODE_ARRIVALS).has("512"));
    }

    @Test
    void sceneFreezeCarriesArrivalsAndPublicationPrefersThemOverLegacyNodeConfigs() throws Exception {
        ObjectNode config = ThemeRuntimeConfig.parseDraft(theme("{\"301\":" + ARRIVAL + "}"));
        assertTrue(ThemeRuntimeConfig.freeze(config, 0, 0L).path(ThemeRuntimeConfig.NODE_ARRIVALS).has("301"));

        ObjectNode definition = (ObjectNode) JSON.readTree(theme("{\"301\":{\"arrivalReading\":{\"level\":3,\"log\":\"新读数\"}}}"));
        ((ObjectNode) definition.path("scenes").get(0)).set("nodeConfigs",
                JSON.readTree("{\"301\":{\"schemaVersion\":1,\"arrivalPolicy\":{\"radiusMeters\":70},\"arrivalReading\":{\"level\":1,\"log\":\"旧读数\"}}}"));
        TopicRoutePublicationCompiler compiler = mock(TopicRoutePublicationCompiler.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        JsonNode merged = ReflectionTestUtils.invokeMethod(compiler, "arrivalAuthorConfig", definition, 301L);
        assertEquals("新读数", merged.path("arrivalReading").path("log").asText(), "nodeArrivals wins per field");
        assertEquals(70, merged.path("arrivalPolicy").path("radiusMeters").asInt(), "legacy nodeConfigs still read");
        JsonNode none = ReflectionTestUtils.invokeMethod(compiler, "arrivalAuthorConfig", definition, 302L);
        assertEquals(null, none);
    }
}
