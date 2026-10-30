package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeJourneySnapshotPresentFreezeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void explicitPresentationSurvivesBothSnapshotWriters() throws Exception {
        for (String present : new String[] { "inline", "fullscreen" }) {
            ObjectNode config = (ObjectNode) json.readTree("{\"check\":{\"enabled\":true},\"present\":\"" + present + "\"}");
            ObjectNode mechanics = json.createObjectNode();
            NodeJourneySnapshot.copyMechanicSegments(config, mechanics);
            assertEquals(present, mechanics.path("present").asText());
            assertEquals(present, AdvancedGameConfigValidator.resolvePresent(mechanics));
            ObjectNode journey = json.createObjectNode();
            NodeJourneySnapshot.copyJourneySegments(config, journey);
            assertEquals(present, journey.path("present").asText());
        }
    }

    @Test
    void defaultPresentationIsResolvedBeforeOtherMechanicsAreDiscarded() throws Exception {
        for (String extra : new String[] { "", ",\"diceRoll\":{\"enabled\":true}" }) {
            ObjectNode config = (ObjectNode) json.readTree("{\"check\":{\"enabled\":true}" + extra + "}");
            String expected = "inline"; // 2026-10-30 所有玩法默认内嵌:多开一个原默认全屏的 diceRoll 也不再翻成 fullscreen
            ObjectNode mechanics = json.createObjectNode();
            NodeJourneySnapshot.copyMechanicSegments(config, mechanics);
            assertEquals(expected, mechanics.path("present").asText());
            assertEquals(expected, AdvancedGameConfigValidator.resolvePresent(mechanics));
            ObjectNode journey = json.createObjectNode();
            NodeJourneySnapshot.copyJourneySegments(config, journey);
            assertEquals(expected, journey.path("present").asText());
        }
    }
}
