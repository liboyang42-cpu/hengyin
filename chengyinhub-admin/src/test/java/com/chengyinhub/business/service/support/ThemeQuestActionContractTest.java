package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Typed request-boundary tests only; gameplay, arrival, rewards and persistence remain in the existing runtime. */
class ThemeQuestActionContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void supportsOnlyTheThreeExistingQuestOperations() {
        for (String kind : List.of("QUEST_START", "QUEST_CANCEL", "QUEST_RESOLVE")) assertTrue(ThemeQuestActionContract.supports(kind));
        for (String kind : List.of("GAMEPLAY_PREVIEW", "FACILITY_USE", "QUEST", "quest_start", "")) assertFalse(ThemeQuestActionContract.supports(kind));
        assertFalse(ThemeQuestActionContract.supports(null));
    }

    @Test void startAndCancelMapOnlyOriginalContentAndExplicitCostSelection() {
        for (String kind : List.of("QUEST_START", "QUEST_CANCEL")) {
            assertEquals(Map.of("contentId", "north"), ThemeQuestActionContract.arguments(kind, json.valueToTree(Map.of("contentId", "north"))));
            assertEquals(Map.of("contentId", "north", "costOption", 1), ThemeQuestActionContract.arguments(kind, json.valueToTree(Map.of("contentId", "north", "costOption", 1))));
        }
    }

    @Test void resolveRequiresTheExistingServerPreviewTokenAndRetainsItsExplicitPayload() throws Exception {
        JsonNode payload = node("""
                {"contentId":"north","choiceKey":"food","costOption":1,"previewVersion":"server-token",
                 "discardRewards":[{"itemId":"water","quantity":2}]}
                """);
        Map<String,Object> arguments = ThemeQuestActionContract.arguments("QUEST_RESOLVE", payload);
        assertEquals("north", arguments.get("contentId")); assertEquals("food", arguments.get("choiceKey"));
        assertEquals(1, arguments.get("costOption")); assertEquals("server-token", arguments.get("previewVersion"));
        assertEquals(payload.get("discardRewards"), arguments.get("discardRewards"));
        for (JsonNode invalid : List.of(node("{\"contentId\":\"north\"}"), node("{\"contentId\":\"north\",\"previewVersion\":null}"),
                node("{\"contentId\":\"north\",\"previewVersion\":\"\"}"), node("{\"contentId\":\"north\",\"previewVersion\":1}")))
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_RESOLVE", invalid));
    }

    @Test void startAndCancelRejectEverySettlementOnlyField() {
        for (String kind : List.of("QUEST_START", "QUEST_CANCEL")) for (Map.Entry<String,Object> field : Map.<String,Object>of(
                "choiceKey", "food", "discardRewards", List.of(), "previewVersion", "server-token").entrySet()) {
            ObjectNode payload = json.createObjectNode().put("contentId", "north"); payload.set(field.getKey(), json.valueToTree(field.getValue()));
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments(kind, payload));
        }
    }

    @Test void rejectsUnknownOperationsFieldsAndClientClaimedOutcomes() {
        for (String kind : List.of("QUEST", "FACILITY_USE", "GAMEPLAY_PREVIEW"))
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments(kind, json.valueToTree(Map.of("contentId", "north"))));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments(null, json.createObjectNode()));
        for (String field : List.of("operation", "kind", "schemaVersion", "passed", "rewards", "ownerId", "weRun", "discardOwned")) {
            ObjectNode payload = resolve(); payload.put(field, "client-claimed");
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_RESOLVE", payload));
        }
    }

    @Test void rejectsNullNonObjectMissingAndCoercedContent() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_START", null));
        for (JsonNode payload : List.of(node("null"), node("[]"), node("\"north\""), node("{}"),
                node("{\"contentId\":null}"), node("{\"contentId\":1}"), node("{\"contentId\":\"\"}")))
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_START", payload));
    }

    @Test void appliesDeclaredTextBoundsWithoutTrimmingOrTruncation() {
        ObjectNode boundary = resolve(); boundary.put("contentId", "c".repeat(96)).put("choiceKey", "x".repeat(96)).put("previewVersion", "p".repeat(128));
        assertEquals("c".repeat(96), ThemeQuestActionContract.arguments("QUEST_RESOLVE", boundary).get("contentId"));
        for (String field : List.of("contentId", "choiceKey", "previewVersion")) {
            ObjectNode payload = resolve(); payload.put(field, "x".repeat("previewVersion".equals(field) ? 129 : 97));
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_RESOLVE", payload));
        }
    }

    @Test void optionIndexesRejectCoercionNegativeAndOverflowValues() throws Exception {
        for (String value : List.of("null", "\"1\"", "1.5", "true", "-1", "2147483648")) {
            ObjectNode payload = resolve(); payload.set("costOption", node(value));
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_RESOLVE", payload));
        }
        assertEquals(0, ThemeQuestActionContract.arguments("QUEST_START", json.valueToTree(Map.of("contentId", "north", "costOption", 0))).get("costOption"));
        // Actual configured option membership remains the runtime's responsibility.
        assertEquals(Integer.MAX_VALUE, ThemeQuestActionContract.arguments("QUEST_START", json.valueToTree(Map.of("contentId", "north", "costOption", Integer.MAX_VALUE))).get("costOption"));
    }

    @Test void validatesDiscardArrayRowFieldsTypesQuantityAndUniqueIds() throws Exception {
        for (String value : List.of("null", "{}", "[null]", "[{}]", "[{\"itemId\":\"water\"}]",
                "[{\"itemId\":1,\"quantity\":1}]", "[{\"itemId\":\"\",\"quantity\":1}]",
                "[{\"itemId\":\"water\",\"quantity\":null}]", "[{\"itemId\":\"water\",\"quantity\":\"1\"}]",
                "[{\"itemId\":\"water\",\"quantity\":1.5}]", "[{\"itemId\":\"water\",\"quantity\":0}]",
                "[{\"itemId\":\"water\",\"quantity\":2147483648}]", "[{\"itemId\":\"water\",\"quantity\":1,\"grant\":true}]",
                "[{\"itemId\":\"water\",\"quantity\":1},{\"itemId\":\"water\",\"quantity\":2}]")) {
            ObjectNode payload = resolve(); payload.set("discardRewards", node(value));
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_RESOLVE", payload), value);
        }
        ObjectNode longId = resolve(); longId.putArray("discardRewards").addObject().put("itemId", "x".repeat(97)).put("quantity", 1);
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_RESOLVE", longId));
    }

    @Test void permitsTwoHundredExplicitDiscardRowsAndRejectsTheNextRow() {
        ObjectNode payload = resolve(); ArrayNode rows = payload.putArray("discardRewards");
        for (int i = 0; i < 200; i++) rows.addObject().put("itemId", "item_" + i).put("quantity", 1);
        assertEquals(200, ((JsonNode) ThemeQuestActionContract.arguments("QUEST_RESOLVE", payload).get("discardRewards")).size());
        rows.addObject().put("itemId", "item_200").put("quantity", 1);
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.arguments("QUEST_RESOLVE", payload));
    }

    @Test void argumentsAndFingerprintLeaveTheRawPayloadUntouchedAndReturnIndependentDiscardRows() {
        ObjectNode payload = resolve(); payload.putArray("discardRewards").addObject().put("itemId", "water").put("quantity", 2);
        JsonNode before = payload.deepCopy();
        Map<String,Object> arguments = ThemeQuestActionContract.arguments("QUEST_RESOLVE", payload);
        fingerprint(payload);
        ((ObjectNode) ((JsonNode) arguments.get("discardRewards")).get(0)).put("quantity", 99);
        assertEquals(before, payload);
    }

    @Test void payloadAndRowObjectFieldOrderDoNotChangeTheFingerprint() throws Exception {
        JsonNode first = node("{\"contentId\":\"north\",\"choiceKey\":\"food\",\"costOption\":1,\"discardRewards\":[{\"itemId\":\"water\",\"quantity\":2}],\"previewVersion\":\"token\"}");
        JsonNode reordered = node("{\"previewVersion\":\"token\",\"discardRewards\":[{\"quantity\":2,\"itemId\":\"water\"}],\"costOption\":1,\"choiceKey\":\"food\",\"contentId\":\"north\"}");
        String originalFirst = first.toString(), originalReordered = reordered.toString();
        Map<String,Object> firstArguments = ThemeQuestActionContract.arguments("QUEST_RESOLVE", first);
        Map<String,Object> reorderedArguments = ThemeQuestActionContract.arguments("QUEST_RESOLVE", reordered);
        // The legacy receipt hash reads JsonNode.toString(): canonical argument rows must agree with the typed proof.
        assertEquals(firstArguments.toString(), reorderedArguments.toString());
        assertEquals("[{\"itemId\":\"water\",\"quantity\":2}]", firstArguments.get("discardRewards").toString());
        assertEquals("[{\"itemId\":\"water\",\"quantity\":2}]", reorderedArguments.get("discardRewards").toString());
        assertEquals(fingerprint(first), fingerprint(reordered));
        assertEquals(originalFirst, first.toString()); assertEquals(originalReordered, reordered.toString());
    }

    @Test void explicitArrayOrderRemainsPartOfTheRequestContract() throws Exception {
        ObjectNode first = resolve(); first.set("discardRewards", node("[{\"itemId\":\"water\",\"quantity\":1},{\"itemId\":\"food\",\"quantity\":2}]"));
        ObjectNode reversed = resolve(); reversed.set("discardRewards", node("[{\"itemId\":\"food\",\"quantity\":2},{\"itemId\":\"water\",\"quantity\":1}]"));
        assertNotEquals(fingerprint(first), fingerprint(reversed));
    }

    @Test void missingDiscardSelectionAndExplicitEmptySelectionHaveDifferentProofs() {
        ObjectNode omitted = resolve(); ObjectNode explicit = resolve(); explicit.putArray("discardRewards");
        assertNotEquals(fingerprint(omitted), fingerprint(explicit));
    }

    @Test void literalNullAndOmittedChoiceCannotCollideAsTheyDidInStringConcatenation() {
        ObjectNode omitted = resolve(); ObjectNode literal = resolve(); literal.put("choiceKey", "null");
        assertNotEquals(fingerprint(omitted), fingerprint(literal));
        ObjectNode explicitNull = resolve(); explicitNull.putNull("choiceKey");
        assertThrows(IllegalArgumentException.class, () -> fingerprint(explicitNull));
    }

    @Test void everyContextMemberKindAndPayloadFieldParticipatesInTheProof() {
        JsonNode payload = json.valueToTree(Map.of("contentId", "north"));
        String baseline = ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 2, "request:one", "QUEST_START", payload);
        Set<String> variants = new HashSet<>(List.of(
                ThemeQuestActionContract.fingerprint(8L, 10L, 0L, 99L, 2, "request:one", "QUEST_START", payload),
                ThemeQuestActionContract.fingerprint(7L, 11L, 0L, 99L, 2, "request:one", "QUEST_START", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 1L, 99L, 2, "request:one", "QUEST_START", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 100L, 2, "request:one", "QUEST_START", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 3, "request:one", "QUEST_START", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 2, "request:two", "QUEST_START", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 2, "request:one", "QUEST_CANCEL", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 2, "request:one", "QUEST_START", json.valueToTree(Map.of("contentId", "east")))));
        assertEquals(8, variants.size()); assertFalse(variants.contains(baseline));
        ObjectNode original = resolve(); original.put("choiceKey", "food").put("costOption", 1);
        original.putArray("discardRewards").addObject().put("itemId", "water").put("quantity", 1);
        for (String field : List.of("contentId", "choiceKey", "costOption", "discardRewards", "previewVersion")) {
            ObjectNode changed = original.deepCopy();
            if ("costOption".equals(field)) changed.put(field, 2);
            else if ("discardRewards".equals(field)) ((ObjectNode) changed.path(field).get(0)).put("quantity", 2);
            else changed.put(field, "different");
            assertNotEquals(fingerprint(original), fingerprint(changed), field);
        }
    }

    @Test void pinnedAsciiEscapedVectorProtectsTheVersionedCanonicalEncoding() throws Exception {
        JsonNode payload = node("{\"contentId\":\"north\",\"choiceKey\":\"中\",\"costOption\":1,\"discardRewards\":[{\"itemId\":\"水\",\"quantity\":2}],\"previewVersion\":\"预览\"}");
        // SHA-256 of the fixed tuple with uppercase ASCII \\u escapes; independently computed without the helper.
        assertEquals("dd2b96a7832a5ba53ad0aeaa8d132fd7a7f3a2c73a17d15e759a16809e40570c", fingerprint(payload));
    }

    @Test void contextRejectsMissingInvalidAndOverflowingVersionScopeOrRequestIdentifiers() {
        assertDoesNotThrow(() -> ThemeQuestActionContract.context(7L, 10L, 0L, 99L, 0, "id:_-"));
        assertDoesNotThrow(() -> ThemeQuestActionContract.context(7L, 10L, 0L, 99L, Integer.MAX_VALUE - 1, "x".repeat(80)));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(null, 10L, 0L, 99L, 2, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(0L, 10L, 0L, 99L, 2, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, null, 0L, 99L, 2, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 0L, 0L, 99L, 2, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, null, 99L, 2, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, -1L, 99L, 2, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, 0L, null, 2, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, 0L, 0L, 2, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, 0L, 99L, null, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, 0L, 99L, -1, "id"));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, 0L, 99L, Integer.MAX_VALUE, "id"));
        for (String id : List.of("", "space id", "x".repeat(81), "换行"))
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, 0L, 99L, 2, id));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.context(7L, 10L, 0L, 99L, 2, null));
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, Integer.MAX_VALUE, "id", "QUEST_START", json.valueToTree(Map.of("contentId", "north"))));
    }

    @Test void storedJsonRejectsDuplicateKeysAndTrailingContent() throws Exception {
        assertEquals(2, ThemeQuestActionContract.readStored("{\"version\":2}").path("version").intValue());
        for (String body : List.of("{\"version\":1,\"version\":2}", "{\"receipt\":{\"version\":1,\"version\":2}}", "{} {}", "{} true", "{"))
            assertThrows(IOException.class, () -> ThemeQuestActionContract.readStored(body));
    }

    @Test void previewRequiresTheExplicitQuestResolveTargetAndMapsOnlyRealSelections() throws Exception {
        JsonNode payload = node("""
                {"operation":"QUEST_RESOLVE","contentId":"north","choiceKey":"food","costOption":1,
                 "discardRewards":[{"itemId":"water","quantity":2}]}
                """);
        Map<String,Object> arguments = ThemeQuestActionContract.previewArguments(payload);
        assertEquals("north", arguments.get("contentId")); assertEquals("food", arguments.get("choiceKey"));
        assertEquals(1, arguments.get("costOption")); assertEquals(payload.get("discardRewards"), arguments.get("discardRewards"));
        assertFalse(arguments.containsKey("operation")); assertFalse(arguments.containsKey("previewVersion"));
        assertEquals(Map.of("contentId", "north"), ThemeQuestActionContract.previewArguments(
                json.valueToTree(Map.of("operation", "QUEST_RESOLVE", "contentId", "north"))));
    }

    @Test void previewDoesNotDefaultMissingOrOtherKindsToQuestResolution() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.previewArguments(null));
        for (JsonNode payload : List.of(node("null"), node("[]"), node("{\"contentId\":\"north\"}"),
                node("{\"operation\":null,\"contentId\":\"north\"}"), node("{\"operation\":1,\"contentId\":\"north\"}"),
                node("{\"operation\":\"QUEST_START\",\"contentId\":\"north\"}"), node("{\"operation\":\"FACILITY_USE\",\"contentId\":\"north\"}")))
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.previewArguments(payload));
    }

    @Test void previewRejectsTokensUnknownFieldsAndCoercedSelectionValues() throws Exception {
        for (String field : List.of("previewVersion", "passed", "rewards", "sessionId", "kind", "schemaVersion")) {
            ObjectNode payload = json.createObjectNode().put("operation", "QUEST_RESOLVE").put("contentId", "north").put(field, "not-preview-input");
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.previewArguments(payload));
        }
        for (String fragment : List.of("\"choiceKey\":1", "\"choiceKey\":null", "\"costOption\":\"1\"", "\"costOption\":1.5", "\"discardRewards\":{}"))
            assertThrows(IllegalArgumentException.class, () -> ThemeQuestActionContract.previewArguments(
                    node("{\"operation\":\"QUEST_RESOLVE\",\"contentId\":\"north\"," + fragment + "}")));
    }

    @Test void previewPreservesOmittedVersusEmptySelectionAndNeverMutatesTheRequest() throws Exception {
        ObjectNode omitted = json.createObjectNode().put("operation", "QUEST_RESOLVE").put("contentId", "north");
        assertFalse(ThemeQuestActionContract.previewArguments(omitted).containsKey("discardRewards"));
        ObjectNode empty = omitted.deepCopy(); empty.putArray("discardRewards");
        assertTrue(((JsonNode) ThemeQuestActionContract.previewArguments(empty).get("discardRewards")).isEmpty());
        ObjectNode selected = omitted.deepCopy(); selected.set("discardRewards", node("[{\"quantity\":2,\"itemId\":\"water\"}]"));
        JsonNode before = selected.deepCopy();
        Map<String,Object> arguments = ThemeQuestActionContract.previewArguments(selected);
        ((ObjectNode) ((JsonNode) arguments.get("discardRewards")).get(0)).put("quantity", 99);
        assertEquals(before, selected);
    }

    private ObjectNode resolve() { return json.createObjectNode().put("contentId", "north").put("previewVersion", "token"); }
    private JsonNode node(String body) throws Exception { return json.readTree(body); }
    private String fingerprint(JsonNode payload) {
        return ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 2, "request:one", "QUEST_RESOLVE", payload);
    }
}
