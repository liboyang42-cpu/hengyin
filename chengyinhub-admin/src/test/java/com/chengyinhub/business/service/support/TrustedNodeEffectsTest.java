package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrustedNodeEffectsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void rootExtractionUsesOnlyFrozenSourceNodeAndReturnsAnIndependentCopy() {
        ObjectNode snapshot = json.createObjectNode();
        snapshot.putObject("nodeJourney").putObject("11").putArray("effects").addObject().put("op", "INC").put("var", "sys.hp").put("value", 2);
        ArrayNode effects = TrustedNodeEffects.rootEffects(snapshot, 11L);
        assertEquals(2, effects.get(0).path("value").asInt());
        ((ObjectNode) effects.get(0)).put("value", 9);
        assertEquals(2, snapshot.path("nodeJourney").path("11").path("effects").get(0).path("value").asInt());
        assertEquals(0, TrustedNodeEffects.rootEffects(snapshot, 12L).size());
    }

    @Test void rootSourceIsOncePerInstanceAndNodeEvenWithRepeatedReceipts() {
        ObjectNode snapshot = json.createObjectNode();
        snapshot.putObject("nodeJourney").putObject("11").putArray("effects").addObject().put("value", 2);
        ArrayNode effects = json.createArrayNode(); ObjectNode sources = json.createObjectNode();
        TrustedNodeEffects.appendRootOnce(snapshot, 11L, "instance-one", effects, sources);
        TrustedNodeEffects.appendRootOnce(snapshot, 11L, "instance-one", effects, sources);
        assertEquals(1, effects.size()); assertTrue(sources.path("instance-one:node-effects:11").asBoolean());
        TrustedNodeEffects.appendRootOnce(snapshot, 11L, "instance-two", effects, sources);
        assertEquals(2, effects.size());
    }
}
