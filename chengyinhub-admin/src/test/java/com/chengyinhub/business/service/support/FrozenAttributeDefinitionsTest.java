package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrozenAttributeDefinitionsTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode snapshot() throws Exception {
        return (ObjectNode) json.readTree("{\"journeyRules\":{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[]},"
                + "\"attributeDefinition\":{\"schemaVersion\":1,\"configVersion\":\"3\",\"registryVersion\":\"2\",\"attributes\":[{"
                + "\"key\":\"counter.focus\",\"keyType\":\"counter\",\"label\":\"专注\",\"initial\":3,\"min\":0,\"max\":10,"
                + "\"visible\":true,\"persistScope\":\"topic\",\"displayType\":\"bar\",\"color\":\"#123456\",\"icon\":\"eye\",\"sortOrder\":null,\"rules\":null}]}}");
    }
    @Test void publishedDefinitionDrivesInitialStateAndMetadataWithoutLiveRegistry() throws Exception {
        ObjectNode snapshot = snapshot(); String unchanged = snapshot.toString();
        JourneyRules restored = FrozenAttributeDefinitions.rules(snapshot);
        TopicRouteEngine.State state = new TopicRouteEngine.State(); JourneyInitialState.initialize(restored, state);
        assertEquals(3, state.getVariables().get("counter.focus"));
        assertEquals("topic", restored.getAttributes().get(0).path("persistScope").asText());
        assertEquals("bar", restored.getAttributes().get(0).path("displayType").asText());
        assertEquals(unchanged, snapshot.toString());
        JourneyInitialState.freezeKeys(snapshot, null, restored);
        assertEquals("counter.focus", snapshot.path("stateKeys").get(0).path("key").asText());
        assertEquals(1, snapshot.path("attributeDefinition").path("schemaVersion").asInt());
    }
    @Test void emptyCompleteDefinitionDoesNotAddUnenabledAttributes() throws Exception {
        ObjectNode snapshot = snapshot(); ((ObjectNode)snapshot.path("attributeDefinition")).putArray("attributes");
        assertEquals(0, FrozenAttributeDefinitions.rules(snapshot).getAttributes().size());
    }
    @Test void missingMetadataAndUnknownPersistenceDoNotGainDefaults() throws Exception {
        ObjectNode snapshot = snapshot(); ObjectNode row = (ObjectNode)snapshot.path("attributeDefinition").path("attributes").get(0);
        row.remove("color"); assertThrows(IllegalArgumentException.class, () -> FrozenAttributeDefinitions.rules(snapshot));
        row.putNull("color"); row.put("persistScope", "unknown");
        assertThrows(IllegalArgumentException.class, () -> FrozenAttributeDefinitions.rules(snapshot));
    }
    @Test void versionsStayExactAndNoncanonicalOrOverflowVersionsAreRejected() throws Exception {
        for (String version : new String[]{"02", "0", "9223372036854775808"}) {
            ObjectNode snapshot = snapshot(); ((ObjectNode)snapshot.path("attributeDefinition")).put("registryVersion", version);
            assertThrows(IllegalArgumentException.class, () -> FrozenAttributeDefinitions.rules(snapshot));
        }
        ObjectNode snapshot = snapshot(); ((ObjectNode)snapshot.path("attributeDefinition")).put("registryVersion", "9007199254740993");
        assertEquals(1, FrozenAttributeDefinitions.rules(snapshot).getAttributes().size());
    }
    @Test void completeDefinitionMustMatchTheRunVersion() throws Exception {
        ObjectNode snapshot = snapshot();
        assertEquals(1, FrozenAttributeDefinitions.rules(snapshot, 3L).getAttributes().size());
        assertThrows(IllegalArgumentException.class, () -> FrozenAttributeDefinitions.rules(snapshot, 4L));
        assertThrows(IllegalArgumentException.class, () -> FrozenAttributeDefinitions.rules(snapshot, null));
    }
    @Test void oldPartialRemainsUnchangedWithoutLiveEnrichment() throws Exception {
        ObjectNode snapshot = snapshot(); snapshot.remove("attributeDefinition");
        snapshot.putArray("stateKeys").addObject().put("key", "counter.focus").put("label", "旧名");
        String unchanged = snapshot.toString();
        assertFalse(FrozenAttributeDefinitions.complete(snapshot));
        assertEquals(0, FrozenAttributeDefinitions.rules(snapshot).getAttributes().size());
        assertEquals(unchanged, snapshot.toString());
    }

    @Test void newRunCopiesPublishedNestedDefinitionExactlyWithoutSharingMutableNodes() throws Exception {
        ObjectNode snapshot = snapshot();
        ObjectNode published = (ObjectNode) snapshot.remove("attributeDefinition");
        ((ObjectNode) snapshot.path("journeyRules")).set("attributeDefinition", published);
        FrozenAttributeDefinitions.copyPublishedDefinition(snapshot);
        assertEquals(published, snapshot.path("attributeDefinition"));
        assertNotSame(published, snapshot.path("attributeDefinition"));
        assertEquals(3, FrozenAttributeDefinitions.rules(snapshot, 3L).getAttributes().get(0).path("initial").asInt());
    }

    @Test void existingRunReadsDoNotPromoteNestedMetadataAndNewFreezeRejectsConflicts() throws Exception {
        ObjectNode snapshot = snapshot();
        ObjectNode published = (ObjectNode) snapshot.remove("attributeDefinition");
        ((ObjectNode) snapshot.path("journeyRules")).set("attributeDefinition", published);
        FrozenAttributeDefinitions.rules(snapshot, 3L);
        assertFalse(snapshot.has("attributeDefinition"));
        snapshot.set("attributeDefinition", published.deepCopy());
        ((ObjectNode) snapshot.path("attributeDefinition")).put("registryVersion", "9");
        assertThrows(IllegalArgumentException.class, () -> FrozenAttributeDefinitions.copyPublishedDefinition(snapshot));
    }

    @Test void legacyNullDefinitionStaysPartialWhileMalformedPublishedDefinitionIsRejected() throws Exception {
        ObjectNode snapshot = snapshot(); snapshot.remove("attributeDefinition");
        ((ObjectNode) snapshot.path("journeyRules")).putNull("attributeDefinition");
        FrozenAttributeDefinitions.copyPublishedDefinition(snapshot);
        assertFalse(snapshot.has("attributeDefinition"));
        ((ObjectNode) snapshot.path("journeyRules")).putObject("attributeDefinition");
        assertThrows(IllegalArgumentException.class, () -> FrozenAttributeDefinitions.copyPublishedDefinition(snapshot));
    }
}
