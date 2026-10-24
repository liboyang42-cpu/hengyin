package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicRouteConfigValidatorTest {
    @ParameterizedTest
    @ValueSource(strings = {"18446744073709551617", "-18446744073709551615"})
    void itemConditionsRejectLongOverflowBeforeSaving(String quantity) {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        for (String op : new String[]{"ITEM_HELD", "ITEM_ACQUIRED"}) {
            String raw = itemConditionGraph(op, quantity);
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(raw, new LinkedHashSet<Long>(Arrays.asList(1L, 2L))), op + ": " + quantity);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 999})
    void itemConditionsPreserveValidQuantityBoundaries(int quantity) {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        for (String op : new String[]{"ITEM_HELD", "ITEM_ACQUIRED"}) {
            String normalized = validator.validateAndNormalize(itemConditionGraph(op, String.valueOf(quantity)),
                    new LinkedHashSet<Long>(Arrays.asList(1L, 2L)));
            assertEquals(quantity, validator.readTree(normalized).path("edges").get(0).path("conditions").get(0).path("quantity").asInt());
        }
    }

    private static String itemConditionGraph(String op, String quantity) {
        return "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],\"edges\":["
                + "{\"id\":\"go\",\"fromNodeId\":1,\"toNodeId\":2,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"},"
                + "\"conditions\":[{\"op\":\"" + op + "\",\"itemId\":\"supply\",\"quantity\":" + quantity + "}]}]}";
    }


    @Test
    void firstCreateRemapsStableClientKeysAndRejectsUnknownKeys() throws Exception {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String draft = "{\"schemaVersion\":1,\"startNodeKey\":\"a\",\"terminalNodeKeys\":[\"d\"]," +
                "\"edges\":[{\"id\":\"history\",\"fromNodeKey\":\"a\",\"toNodeKey\":\"b\"," +
                "\"trigger\":{\"type\":\"PREFERENCE_RESULT\",\"outcomeCode\":\"HISTORY\"}}," +
                "{\"id\":\"art\",\"fromNodeKey\":\"a\",\"toNodeKey\":\"c\"," +
                "\"trigger\":{\"type\":\"PREFERENCE_RESULT\",\"outcomeCode\":\"ART\"}}," +
                "{\"id\":\"merge-b\",\"fromNodeKey\":\"b\",\"toNodeKey\":\"d\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}," +
                "{\"id\":\"merge-c\",\"fromNodeKey\":\"c\",\"toNodeKey\":\"d\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"fallbacks\":[{\"fromNodeKey\":\"a\",\"toNodeKey\":\"b\"}]}";
        Map<String, Long> ids = new LinkedHashMap<String, Long>();
        ids.put("a", 101L);
        ids.put("b", 102L);
        ids.put("c", 103L);
        ids.put("d", 104L);
        String remapped = validator.remapClientNodeKeys(draft, ids);
        assertEquals(101L, validator.readTree(remapped).path("startNodeId").asLong());
        assertEquals(104L, validator.readTree(remapped).path("edges").get(2).path("toNodeId").asLong());
        validator.validateAndNormalize(remapped,
                new LinkedHashSet<Long>(Arrays.asList(101L, 102L, 103L, 104L)));

        assertThrows(IllegalArgumentException.class,
                () -> validator.remapClientNodeKeys(draft, CollectionsMap.only("a", 101L)));
    }

    @Test
    void validatorRejectsDanglingTargetsAndCycles() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String dangling = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2]," +
                "\"edges\":[{\"id\":\"bad\",\"fromNodeId\":1,\"toNodeId\":9," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(dangling,
                new LinkedHashSet<Long>(Arrays.asList(1L, 2L))));
    }

    @Test
    void goldenSchemaUsesOutcomeCodeVarOpAndNormalizesLegacyAliasesAtTheBoundary() throws Exception {
        String golden = new Scanner(getClass().getResourceAsStream("/contracts/topic-route-graph-v1.json"), "UTF-8")
                .useDelimiter("\\A").next();
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        validator.validateAndNormalize(golden, new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L)));

        String legacy = golden.replace("\"outcomeCode\"", "\"code\"")
                .replace("\"var\"", "\"name\"")
                .replace("\"op\": \"INC\"", "\"type\": \"INC\"");
        String normalized = validator.validateAndNormalize(legacy,
                new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L)));
        assertEquals("COMPLETED", validator.readTree(normalized).path("edges").get(0)
                .path("trigger").path("outcomeCode").asText());
        assertEquals("clues", validator.readTree(normalized).path("edges").get(0)
                .path("conditions").get(0).path("var").asText());
        assertEquals("INC", validator.readTree(normalized).path("edges").get(0)
                .path("effects").get(0).path("op").asText());
    }

    @Test
    void cyclesRequireExplicitAllowLoopAndPositiveMaxVisits() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[3]," +
                "\"edges\":[{\"id\":\"forward\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"NEXT\"}}," +
                "{\"id\":\"loop\",\"fromNodeId\":2,\"toNodeId\":1," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"BACK\"}}," +
                "{\"id\":\"finish\",\"fromNodeId\":2,\"toNodeId\":3," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"DONE\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":2,\"toNodeId\":3}]}";
        LinkedHashSet<Long> nodes = new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(graph, nodes));
        String bounded = graph.replace("\"id\":\"forward\"", "\"id\":\"forward\",\"allowLoop\":true,\"maxVisits\":2")
                .replace("\"id\":\"loop\"", "\"id\":\"loop\",\"allowLoop\":true,\"maxVisits\":2");
        validator.validateAndNormalize(bounded, nodes);
    }

    @Test
    void publishRejectsOutcomeNotDeclaredBySourceNodeGameplay() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2]," +
                "\"edges\":[{\"id\":\"bad\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"PREFERENCE_RESULT\",\"outcomeCode\":\"INVENTED\"}}]}";
        Map<Long, java.util.Set<String>> declared = new LinkedHashMap<Long, java.util.Set<String>>();
        declared.put(1L, new LinkedHashSet<String>(Arrays.asList("PREFERENCE_RESULT:HISTORY")));
        declared.put(2L, new LinkedHashSet<String>(Arrays.asList("CHOICE:COMPLETED")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(graph,
                new LinkedHashSet<Long>(Arrays.asList(1L, 2L)), declared));
    }

    @Test
    void nodeCompletedConditionMustBelongToTopicAndIsRemappedForCreateAndCopy() throws Exception {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String draft = "{\"schemaVersion\":1,\"startNodeKey\":\"a\",\"terminalNodeKeys\":[\"c\"]," +
                "\"edges\":[{\"id\":\"e1\",\"fromNodeKey\":\"a\",\"toNodeKey\":\"b\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}} ," +
                "{\"id\":\"e2\",\"fromNodeKey\":\"b\",\"toNodeKey\":\"c\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}," +
                "\"conditions\":[{\"type\":\"NODE_COMPLETED\",\"nodeKey\":\"a\"}]}]}";
        Map<String, Long> created = new LinkedHashMap<String, Long>();
        created.put("a", 1L); created.put("b", 2L); created.put("c", 3L);
        String remapped = validator.remapClientNodeKeys(draft, created);
        assertEquals(1L, validator.readTree(remapped).path("edges").get(1)
                .path("conditions").get(0).path("nodeId").asLong());
        validator.validateAndNormalize(remapped, new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L)));

        Map<Long, Long> copied = new LinkedHashMap<Long, Long>();
        copied.put(1L, 11L); copied.put(2L, 12L); copied.put(3L, 13L);
        String copiedGraph = validator.remapNodeIds(remapped, copied);
        assertEquals(11L, validator.readTree(copiedGraph).path("edges").get(1)
                .path("conditions").get(0).path("nodeId").asLong());

        String foreign = remapped.replace("\"nodeId\":1", "\"nodeId\":99");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(foreign,
                new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L))));
    }

    @Test
    void variableConditionMustReferenceDeclaredVariable() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],\"variables\":{}," +
                "\"edges\":[{\"id\":\"guarded\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}," +
                "\"conditions\":[{\"op\":\"GTE\",\"var\":\"score\",\"value\":1}]}]}";

        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(graph,
                new LinkedHashSet<Long>(Arrays.asList(1L, 2L))));

        validator.validateAndNormalize(graph.replace("\"variables\":{}", "\"variables\":{\"score\":0}"),
                new LinkedHashSet<Long>(Arrays.asList(1L, 2L)));
    }

    @Test
    void numericStringNodeReferencesAreCanonicalizedBeforeSnapshotExecution() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String graph = "{\"schemaVersion\":1,\"startNodeId\":\"1\",\"terminalNodeIds\":[\"3\"]," +
                "\"edges\":[{\"id\":\"go\",\"fromNodeId\":\"1\",\"toNodeId\":\"2\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}},{" +
                "\"id\":\"finish\",\"fromNodeId\":\"2\",\"toNodeId\":\"3\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}," +
                "\"conditions\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":\"1\"}]}]," +
                "\"fallbacks\":[{\"fromNodeId\":\"1\",\"toNodeId\":\"3\"}," +
                "{\"fromNodeId\":\"2\",\"toNodeId\":\"3\"}]," +
                "\"nodeRequirements\":[{\"nodeId\":\"2\",\"requireOpen\":true}]}";

        String normalized = validator.validateAndNormalize(graph,
                new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L)));

        com.fasterxml.jackson.databind.JsonNode root = validator.readTree(normalized);
        assertEquals(true, root.path("startNodeId").isIntegralNumber());
        assertEquals(true, root.path("terminalNodeIds").get(0).isIntegralNumber());
        assertEquals(true, root.path("edges").get(0).path("fromNodeId").isIntegralNumber());
        assertEquals(true, root.path("edges").get(0).path("toNodeId").isIntegralNumber());
        assertEquals(true, root.path("edges").get(1).path("conditions").get(0)
                .path("nodeId").isIntegralNumber());
        assertEquals(true, root.path("fallbacks").get(0).path("fromNodeId").isIntegralNumber());
        assertEquals(true, root.path("fallbacks").get(0).path("toNodeId").isIntegralNumber());
        assertEquals(true, root.path("nodeRequirements").get(0).path("nodeId").isIntegralNumber());

        TopicRouteEngine.State initial = new TopicRouteEngine().initial(normalized);
        assertEquals(1L, initial.getCurrentNodeId());
    }

    @Test
    void nodeRequirementsAreValidatedAndRemappedForCreateAndCopy() throws Exception {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String draft = "{\"schemaVersion\":1,\"startNodeKey\":\"a\",\"terminalNodeKeys\":[\"b\",\"c\"]," +
                "\"edges\":[{\"id\":\"go\",\"fromNodeKey\":\"a\",\"toNodeKey\":\"b\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"fallbacks\":[{\"fromNodeKey\":\"a\",\"toNodeKey\":\"c\"}]," +
                "\"nodeRequirements\":[{\"nodeKey\":\"b\",\"allowedTicketIds\":[7]," +
                "\"allowedPurchaseKinds\":[1],\"requiredRoleCodes\":[\"NAVIGATOR\"]," +
                "\"requireOpen\":true,\"maxDistanceMeters\":800,\"requireReachable\":true}]}";
        Map<String, Long> created = new LinkedHashMap<String, Long>();
        created.put("a", 1L); created.put("b", 2L); created.put("c", 3L);

        String remapped = validator.remapClientNodeKeys(draft, created);
        assertEquals(2L, validator.readTree(remapped).path("nodeRequirements").get(0).path("nodeId").asLong());
        validator.validateAndNormalize(remapped, new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L)));

        Map<Long, Long> copied = new LinkedHashMap<Long, Long>();
        copied.put(1L, 11L); copied.put(2L, 12L); copied.put(3L, 13L);
        String copiedGraph = validator.remapNodeIds(remapped, copied);
        assertEquals(12L, validator.readTree(copiedGraph).path("nodeRequirements").get(0).path("nodeId").asLong());
    }

    @Test
    void nodeRequirementsRejectForeignNodesInvalidRangesAndWrongTypes() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String base = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2]," +
                "\"edges\":[{\"id\":\"go\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"nodeRequirements\":[%s]}";
        LinkedHashSet<Long> nodes = new LinkedHashSet<Long>(Arrays.asList(1L, 2L));

        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                String.format(base, "{\"nodeId\":9,\"requireOpen\":true}"), nodes));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                String.format(base, "{\"nodeId\":2,\"maxDistanceMeters\":0}"), nodes));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                String.format(base, "{\"nodeId\":2,\"requireReachable\":\"yes\"}"), nodes));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                String.format(base, "{\"nodeId\":2,\"requiredRoleCodes\":[\"TYPO\"]}"), nodes));
    }

    @Test
    void realityGatedCandidateRequiresExplicitFallbackEvenWithOneOrdinaryEdge() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2]," +
                "\"edges\":[{\"id\":\"gated\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"nodeRequirements\":[{\"nodeId\":2,\"requireOpen\":true}]}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(graph,
                        new LinkedHashSet<Long>(Arrays.asList(1L, 2L))));

        assertTrue(error.getMessage().contains("现实门禁"), error.getMessage());
        assertTrue(error.getMessage().contains("fallback"), error.getMessage());
    }

    @Test
    void fallbackTargetMustNotDependOnRealityFacts() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"edges\":[{\"id\":\"gated\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]," +
                "\"nodeRequirements\":[{\"nodeId\":2,\"requireOpen\":true}," +
                "{\"nodeId\":3,\"requireReachable\":true}]}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(graph,
                        new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L))));

        assertTrue(error.getMessage().contains("fallback"), error.getMessage());
        assertTrue(error.getMessage().contains("现实门禁"), error.getMessage());
    }

    @Test
    void rebuildingNodesFailsClosedWhenExistingGraphCannotBeFullyRemapped() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String graph = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2]," +
                "\"edges\":[{\"id\":\"go\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}";
        Map<Long, Long> incomplete = new LinkedHashMap<Long, Long>();
        incomplete.put(1L, 101L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.remapNodeIds(graph, incomplete));

        assertTrue(error.getMessage().contains("找不到 nodeId 映射:2"), error.getMessage());
    }

    private static final class CollectionsMap {
        static Map<String, Long> only(String key, Long value) {
            Map<String, Long> map = new LinkedHashMap<String, Long>();
            map.put(key, value);
            return map;
        }
    }
}
