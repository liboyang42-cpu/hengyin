package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G2 / GAM-07:三个原"只展示"玩法的服务端判定、奖励与兼容口径。 */
class AmbientKitJudgementTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode cfg(String json) throws Exception { return mapper.readTree(json); }

    @Test
    void silentOrderWithinLimitPassesOnceAndPaysXpOnce() throws Exception {
        JsonNode config = cfg("{\"silentOrder\":{\"enabled\":true,\"title\":\"t\",\"limitSeconds\":120,\"scored\":true,\"xp\":20}}");
        ObjectNode state = mapper.createObjectNode();
        assertFalse(AdvancedActionJudgement.mechanicsReady(config, state));
        assertEquals(0, AmbientKitJudgement.apply("START_SILENT_ORDER", config, state, 1_000L));
        assertEquals(20, AmbientKitJudgement.apply("FINISH_SILENT_ORDER", config, state, 61_000L));
        assertTrue(state.path("silentOrder").path("passed").asBoolean());
        assertEquals(0, AmbientKitJudgement.apply("FINISH_SILENT_ORDER", config, state, 62_000L), "replay pays nothing");
        assertTrue(AdvancedActionJudgement.mechanicsReady(config, state));
    }

    @Test
    void silentOrderLateIsRecordedAndCanRestart() throws Exception {
        JsonNode config = cfg("{\"silentOrder\":{\"enabled\":true,\"title\":\"t\",\"limitSeconds\":60,\"scored\":true,\"xp\":20}}");
        ObjectNode state = mapper.createObjectNode();
        AmbientKitJudgement.apply("START_SILENT_ORDER", config, state, 0L);
        assertEquals(0, AmbientKitJudgement.apply("FINISH_SILENT_ORDER", config, state, 90_000L));
        assertEquals("LATE", state.path("silentOrder").path("lastOutcome").asText());
        assertFalse(state.path("silentOrder").has("startedAt"));
        AmbientKitJudgement.apply("START_SILENT_ORDER", config, state, 100_000L);
        assertEquals(20, AmbientKitJudgement.apply("FINISH_SILENT_ORDER", config, state, 130_000L));
        assertEquals(2, state.path("silentOrder").path("attempts").asInt());
    }

    @Test
    void silentOrderRepeatedStartDoesNotResetClockAndFinishNeedsStart() throws Exception {
        JsonNode config = cfg("{\"silentOrder\":{\"enabled\":true,\"title\":\"t\",\"limitSeconds\":0}}");
        ObjectNode state = mapper.createObjectNode();
        assertThrows(IllegalStateException.class, () -> AmbientKitJudgement.apply("FINISH_SILENT_ORDER", config, state, 0L));
        AmbientKitJudgement.apply("START_SILENT_ORDER", config, state, 0L);
        AmbientKitJudgement.apply("START_SILENT_ORDER", config, state, 50_000L);
        assertEquals(0L, state.path("silentOrder").path("startedAt").asLong());
        assertThrows(IllegalStateException.class, () -> AmbientKitJudgement.apply("FINISH_SILENT_ORDER", config, state, 5_000L),
                "no limit still needs a real pause");
    }

    @Test
    void musicCornerNeedsServerTimeNotClientClaim() throws Exception {
        JsonNode config = cfg("{\"musicCorner\":{\"enabled\":true,\"title\":\"t\",\"durationSeconds\":100,\"scored\":true,\"xp\":5}}");
        ObjectNode state = mapper.createObjectNode();
        assertThrows(IllegalStateException.class, () -> AmbientKitJudgement.apply("FINISH_MUSIC_CORNER", config, state, 0L));
        AmbientKitJudgement.apply("START_MUSIC_CORNER", config, state, 0L);
        IllegalStateException early = assertThrows(IllegalStateException.class,
                () -> AmbientKitJudgement.apply("FINISH_MUSIC_CORNER", config, state, 30_000L));
        assertTrue(early.getMessage().contains("再听"));
        assertFalse(state.path("musicCorner").path("listened").asBoolean());
        assertEquals(5, AmbientKitJudgement.apply("FINISH_MUSIC_CORNER", config, state, 80_000L));
        assertTrue(AdvancedActionJudgement.mechanicsReady(config, state));
    }

    @Test
    void timeWindowCheckInIsIdempotent() throws Exception {
        JsonNode config = cfg("{\"timeWindow\":{\"enabled\":true,\"openFrom\":\"20:00\",\"openTo\":\"23:00\",\"scored\":true,\"xp\":8}}");
        ObjectNode state = mapper.createObjectNode();
        assertFalse(AdvancedActionJudgement.mechanicsReady(config, state));
        assertEquals(8, AmbientKitJudgement.apply("CHECK_IN_TIME_WINDOW", config, state, 1L));
        assertEquals(0, AmbientKitJudgement.apply("CHECK_IN_TIME_WINDOW", config, state, 2L));
        assertTrue(AdvancedActionJudgement.mechanicsReady(config, state));
    }

    @Test
    void unscoredLegacyTemplatesKeepOldBehaviour() throws Exception {
        JsonNode config = cfg("{\"silentOrder\":{\"enabled\":true,\"title\":\"t\",\"limitSeconds\":60,\"xp\":20},"
                + "\"musicCorner\":{\"enabled\":true,\"title\":\"m\"},\"timeWindow\":{\"enabled\":true,\"openFrom\":\"20:00\",\"openTo\":\"23:00\"}}");
        ObjectNode state = mapper.createObjectNode();
        assertTrue(AdvancedActionJudgement.mechanicsReady(config, state), "unscored kits never block completion");
        AmbientKitJudgement.apply("START_SILENT_ORDER", config, state, 0L);
        assertEquals(0, AmbientKitJudgement.apply("FINISH_SILENT_ORDER", config, state, 30_000L), "no xp unless scored");
    }

    @Test
    void projectionCarriesProgressOnly() throws Exception {
        JsonNode config = cfg("{\"musicCorner\":{\"enabled\":true,\"title\":\"m\",\"durationSeconds\":0,\"scored\":true,\"xp\":3}}");
        ObjectNode state = mapper.createObjectNode();
        AmbientKitJudgement.apply("START_MUSIC_CORNER", config, state, 0L);
        Map<String, Object> value = new LinkedHashMap<>();
        AmbientKitJudgement.project("musicCorner", config.get("musicCorner"), state, value);
        assertEquals(true, value.get("playing"));
        assertEquals(24L, value.get("requiredSeconds"));
        assertFalse(value.containsKey("startedAt"));
    }

    @Test
    void disabledKitRefuses() throws Exception {
        JsonNode config = cfg("{}");
        assertThrows(IllegalArgumentException.class,
                () -> AmbientKitJudgement.apply("START_MUSIC_CORNER", config, mapper.createObjectNode(), 0L));
    }
}
