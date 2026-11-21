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

    // GAME-19:时段限定在窗外分清「还没开」与「今天已关」,跨夜时段窗外总是「今天稍后开放」。
    @Test
    void timeWindowBeforeInsideAfterAndOvernight() {
        int from = 20 * 60, to = 23 * 60;
        TimeWindowRefusal before = TimeWindowRefusal.judge(from, to, 19 * 60, "20:00", "23:00");
        assertEquals(TimeWindowRefusal.NOT_YET, before.reasonCode());
        assertEquals("20:00", before.opensAt());
        assertEquals("这一站 20:00 开放，到时再来", before.getMessage());
        assertEquals(null, TimeWindowRefusal.judge(from, to, 20 * 60, "20:00", "23:00"), "start is inside");
        assertEquals(null, TimeWindowRefusal.judge(from, to, 23 * 60 - 1, "20:00", "23:00"));
        TimeWindowRefusal after = TimeWindowRefusal.judge(from, to, 23 * 60, "20:00", "23:00");
        assertEquals(TimeWindowRefusal.CLOSED, after.reasonCode(), "end is exclusive and means closed for today");
        assertEquals("23:00", after.closedAt());
        assertEquals("这一站今天 23:00 已关闭，明天 20:00 再来", after.getMessage());
        assertEquals(TimeWindowRefusal.CLOSED, TimeWindowRefusal.judge(from, to, 23 * 60 + 30, "20:00", "23:00").reasonCode());

        int nightFrom = 23 * 60, nightTo = 60;
        assertEquals(null, TimeWindowRefusal.judge(nightFrom, nightTo, 23 * 60 + 30, "23:00", "01:00"));
        assertEquals(null, TimeWindowRefusal.judge(nightFrom, nightTo, 30, "23:00", "01:00"), "after midnight is inside");
        TimeWindowRefusal day = TimeWindowRefusal.judge(nightFrom, nightTo, 12 * 60, "23:00", "01:00");
        assertEquals(TimeWindowRefusal.NOT_YET, day.reasonCode(), "an overnight window opens again later today");
        assertEquals(TimeWindowRefusal.NOT_YET, TimeWindowRefusal.judge(nightFrom, nightTo, 60, "23:00", "01:00").reasonCode());
        assertTrue(day instanceof IllegalStateException, "existing catch blocks keep working");
    }
}
