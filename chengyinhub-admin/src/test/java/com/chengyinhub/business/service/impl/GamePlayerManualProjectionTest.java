package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class GamePlayerManualProjectionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void onlyActualPlayerSeesOwnMinimalSignaturesAndServerSequence() throws Exception {
        ObjectNode run = (ObjectNode) json.readTree("{\"manual\":{\"nextSequence\":3,\"configHash\":\"secret\",\"signatures\":{"
            + "\"20\":{\"actorMemberId\":7,\"perspective\":\"PLAYER\",\"sequence\":3,\"occurredAt\":100,\"used\":false,\"command\":{\"action\":\"QUEST_START\",\"payload\":{\"questKey\":\"q1\"}},\"commandHash\":\"secret\"},"
            + "\"18\":{\"actorMemberId\":7,\"perspective\":\"PLAYER\",\"sequence\":1,\"occurredAt\":90,\"used\":true,\"command\":{\"action\":\"QUEST_START\"},\"result\":{\"balance\":9}},"
            + "\"19\":{\"actorMemberId\":7,\"perspective\":\"PLAYER\",\"sequence\":2,\"occurredAt\":95,\"used\":true,\"rejected\":true,\"reason\":\"未发生\",\"command\":{\"action\":\"QUEST_CANCEL\"}},"
            + "\"21\":{\"actorMemberId\":9,\"perspective\":\"PLAYER\",\"sequence\":4,\"command\":{\"action\":\"VOTE_CAST\"}},"
            + "\"22\":{\"actorMemberId\":7,\"perspective\":\"CLUB\",\"sequence\":5,\"command\":{\"action\":\"ARRIVAL_ATTEST\"}}}}}");
        JsonNode projected = ReflectionTestUtils.invokeMethod(new GameSessionRuntimeServiceImpl(), "playerManualView", run, 7L);
        assertEquals(3, projected.path("nextSequence").asInt());
        assertEquals(3, projected.path("signatures").size());
        assertEquals("PENDING", projected.path("signatures").path(0).path("status").asText());
        assertEquals("VERIFIED", projected.path("signatures").path(1).path("status").asText());
        assertEquals("REJECTED", projected.path("signatures").path(2).path("status").asText());
        assertEquals("未发生", projected.path("signatures").path(2).path("reason").asText());
        assertEquals(5, projected.path("signatures").path(0).size());
        assertFalse(projected.toString().contains("secret"));
        assertFalse(projected.toString().contains("payload"));
        assertFalse(projected.toString().contains("balance"));
        assertFalse(projected.toString().contains("actorMemberId"));
        assertEquals(0, ((JsonNode) ReflectionTestUtils.invokeMethod(new GameSessionRuntimeServiceImpl(), "playerManualView", run, 8L)).path("signatures").size());
        assertNull(ReflectionTestUtils.invokeMethod(new GameSessionRuntimeServiceImpl(), "playerManualView", json.createObjectNode(), 7L));
    }
}
