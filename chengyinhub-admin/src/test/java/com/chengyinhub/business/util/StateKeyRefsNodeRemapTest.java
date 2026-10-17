package com.chengyinhub.business.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StateKeyRefsNodeRemapTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void copiesNestedSystemConditionsAndPreservesUnrelatedAuthorContent() throws Exception {
        String source = "{\"schemaVersion\":1,\"title\":\"sys.passed.101\",\"requires\":[{\"var\":\"sys.passed.101\",\"op\":\"EQ\",\"value\":1},{\"op\":\"NODE_COMPLETED\",\"nodeId\":102}],\"qa\":{\"options\":[{\"label\":\"sys.asked.101.q\",\"effects\":[{\"var\":\"counter.score\",\"op\":\"INC\",\"value\":2}]}]},\"variants\":[{\"when\":{\"var\":\"sys.asked.101.q_one\",\"op\":\"EQ\",\"value\":1}}]}";
        Map<Long,Long> nodes = new HashMap<>(); nodes.put(101L, 201L); nodes.put(102L, 202L);
        JsonNode copy = json.readTree(StateKeyRefs.remapNodeIds(source, nodes));
        assertEquals("sys.passed.201", copy.path("requires").get(0).path("var").asText());
        assertEquals(202, copy.path("requires").get(1).path("nodeId").asLong());
        assertEquals("sys.asked.201.q_one", copy.path("variants").get(0).path("when").path("var").asText());
        assertEquals("sys.passed.101", copy.path("title").asText());
        assertEquals("sys.asked.101.q", copy.path("qa").path("options").get(0).path("label").asText());
        assertEquals("counter.score", copy.path("qa").path("options").get(0).path("effects").get(0).path("var").asText());
        assertTrue(source.contains("sys.passed.101"));
    }
    @Test void foreignReferencesAndMalformedConfigAbortCopyInsteadOfRetainingOldNodes() {
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.remapNodeIds("{\"requires\":[{\"var\":\"sys.outcome.101\"}]}", Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.remapNodeIds("{\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":101}]}", Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.remapNodeIds("{broken", Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> StateKeyRefs.remapNodeIds("[]", Collections.emptyMap()));
        assertEquals("", StateKeyRefs.remapNodeIds("", Collections.emptyMap()));
    }
    @Test void everyExistingNodeSystemNamespaceRetainsQuestionSuffixes() throws Exception {
        for (String namespace : Arrays.asList("asked", "mistakes", "outcome", "passed", "damage")) {
            String suffix = "asked".equals(namespace) ? ".q1" : "";
            String source = "{\"check\":{\"mods\":[{\"when\":{\"var\":\"sys." + namespace + ".101" + suffix + "\"}}]}}";
            JsonNode copy = json.readTree(StateKeyRefs.remapNodeIds(source, Collections.singletonMap(101L, 201L)));
            assertEquals("sys." + namespace + ".201" + suffix, copy.path("check").path("mods").get(0).path("when").path("var").asText());
        }
    }
}
