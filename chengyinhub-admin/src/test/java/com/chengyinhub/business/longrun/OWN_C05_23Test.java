package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: actual public remap entrances and the retained generic recursion seam. */
class OWN_C05_23Test {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<Long, Long> mapping() {
        Map<Long, Long> ids = new LinkedHashMap<>();
        ids.put(1L, 2L);
        ids.put(2L, 3L);
        return ids;
    }

    private ObjectNode config() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("currentScene", "scene");
        ObjectNode scene = root.putArray("scenes").addObject();
        scene.put("id", "scene").put("name", "Scene");
        scene.putObject("nodeConfigs").putObject("1").put("schemaVersion", 1);
        ObjectNode story = scene.putObject("journeyStory");
        story.put("schemaVersion", 1);
        story.putObject("extra").put("var", "sys.outcome.1");
        ObjectNode ending = story.putObject("ending");
        ending.put("dispositionNodeId", 1);
        ArrayNode cases = ending.putArray("endings");
        ObjectNode selected = cases.addObject();
        selected.put("code", "selected").put("title", "Selected").put("chapterId", 7);
        ArrayNode conditions = selected.putArray("when");
        for (String var : new String[] {"sys.asked.1.Q_last", "sys.outcome.1", "sys.mistakes.1", "sys.passed.1"})
            conditions.addObject().put("op", "EQ").put("var", var).put("value", 1);
        conditions.addObject().put("op", "NODE_COMPLETED").put("nodeId", 1);
        cases.addObject().put("code", "fallback").put("title", "Fallback").put("fallback", true);
        ObjectNode runtimeEnding = root.putArray("endings").addObject();
        runtimeEnding.put("id", "runtime").put("title", "Runtime").put("fallback", true);
        runtimeEnding.putArray("when").addObject().put("op", "NODE_COMPLETED").put("nodeId", 1);
        return root;
    }

    private void assertMappedOnce(JsonNode mapped) {
        JsonNode scene = mapped.path("scenes").get(0);
        JsonNode story = scene.path("journeyStory");
        JsonNode ending = story.path("ending");
        JsonNode conditions = ending.path("endings").get(0).path("when");
        assertEquals("sys.asked.2.Q_last", conditions.get(0).path("var").asText());
        assertEquals("sys.outcome.2", conditions.get(1).path("var").asText());
        assertEquals("sys.mistakes.2", conditions.get(2).path("var").asText());
        assertEquals("sys.passed.2", conditions.get(3).path("var").asText());
        assertEquals(2, conditions.get(4).path("nodeId").asInt());
        assertEquals(2, ending.path("dispositionNodeId").asInt());
        assertEquals("sys.outcome.1", story.path("extra").path("var").asText());
        assertTrue(scene.path("nodeConfigs").has("2"));
        assertFalse(scene.path("nodeConfigs").has("3"));
        assertEquals(2, mapped.path("endings").get(0).path("when").get(0).path("nodeId").asInt());
    }

    @Test
    void publicRemapNodesMapsAskedAndDoesNotMapAnyStoryIdTwice() throws Exception {
        JsonNode result = JSON.readTree(ThemeRuntimeConfig.remapNodes(config().toString(), mapping()));
        assertMappedOnce(result);
        assertEquals(7, result.path("scenes").get(0).path("journeyStory").path("ending")
                .path("endings").get(0).path("chapterId").asInt());
    }

    @Test
    void publicRemapAllStillMapsChapterIdsAfterSingleNodePass() throws Exception {
        JsonNode result = JSON.readTree(ThemeRuntimeConfig.remapAll(config().toString(), mapping(),
                Collections.singletonMap(7L, 8L), null));
        assertMappedOnce(result);
        assertEquals(8, result.path("scenes").get(0).path("journeyStory").path("ending")
                .path("endings").get(0).path("chapterId").asInt());
    }

    @Test
    void missingStoryNodeMappingStillRejectsCopy() {
        assertThrows(IllegalArgumentException.class,
                () -> ThemeRuntimeConfig.remapNodes(config().toString(), Collections.emptyMap()));
    }

    @Test
    void genericDamageRecursionIsUnchangedWithoutExpandingPublicDsl() {
        ObjectNode runtime = JSON.createObjectNode();
        runtime.putObject("nested").put("var", "sys.damage.1");
        ReflectionTestUtils.invokeMethod(ThemeRuntimeConfig.class, "remap", runtime, mapping());
        assertEquals("sys.damage.2", runtime.path("nested").path("var").asText());
    }
}
