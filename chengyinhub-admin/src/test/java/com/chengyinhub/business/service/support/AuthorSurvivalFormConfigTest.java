package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthorSurvivalFormConfigTest {
    @Test void phoneFormRestRescueRationsAndParticipantsFacilityValidateAndSurviveCompilationAndCopy() throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-xcx", "tests", "unit", "fixtures", "author-survival-config.json")), StandardCharsets.UTF_8);
        ObjectNode config = ThemeRuntimeConfig.parse(raw);
        ThemeRuntimeConfig.assertJourneyReferences(config, com.chengyinhub.business.domain.JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":8,\"max\":10}}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertJourneyReferences(config, com.chengyinhub.business.domain.JourneyRules.parse(null)));
        JsonNode game = config.path("gameplayRuntime");
        assertEquals("REST", game.path("survival").path("recoveries").get(0).path("kind").asText());
        assertEquals("RESCUE", game.path("survival").path("recoveries").get(1).path("kind").asText());
        assertEquals("supply", game.path("survival").path("rations").get(0).path("itemId").asText());
        assertEquals("PARTICIPANTS", game.path("facilities").get(0).path("targetScope").asText());
        ThemeRuntimeConfig.assertReferences(config, new HashSet<>(Arrays.asList(101L, 102L)), Collections.emptySet());
        ObjectNode templateConfigs = new ObjectMapper().createObjectNode();
        templateConfigs.putObject("101").putObject("interactionRules").put("hpCost", 1).put("limit", 1).put("scope", "OWNER").put("targetScope", "OWNER");
        ObjectNode compiled = GameplayTemplateRules.merge(config, templateConfigs, Collections.singletonMap(101L, "act_one"));
        assertEquals(2, compiled.path("gameplayRuntime").path("facilities").size());
        assertEquals(game.path("facilities").get(0), compiled.path("gameplayRuntime").path("facilities").get(0));
        assertEquals("tpl_node_101", compiled.path("gameplayRuntime").path("facilities").get(1).path("facilityKey").asText());
        assertEquals(game.path("survival"), compiled.path("gameplayRuntime").path("survival"));
        Map<Long, Long> nodes = new HashMap<>(); nodes.put(101L, 201L); nodes.put(102L, 202L);
        ObjectNode copied = ThemeRuntimeConfig.parse(ThemeRuntimeConfig.remapNodes(raw, nodes));
        assertEquals(202, copied.path("gameplayRuntime").path("facilities").get(0).path("operationNodeId").asInt());
        assertEquals(201, copied.path("gameplayRuntime").path("facilities").get(0).path("when").get(2).path("nodeId").asInt());
        assertEquals(game.path("survival"), copied.path("gameplayRuntime").path("survival"));
        ObjectNode invalid = config.deepCopy();
        ((ObjectNode) invalid.path("gameplayRuntime").path("survival").path("recoveries").get(0)).put("limit", 100);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parse(invalid.toString())).getMessage().contains("limit"));
    }
}
