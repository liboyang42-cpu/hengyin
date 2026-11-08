package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-15:到达读数只投影 level/log；未配置/未到达不输出；公开投影不带该段；
 * level 沿既有叙事块 0–3，不是新的强弱枚举或奖励数值。
 */
class ArrivalReadingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode station(String readingJson) throws Exception {
        ObjectNode station = JSON.createObjectNode().put("nodeId", 10L);
        if (readingJson != null) station.set(ArrivalReading.FIELD, JSON.readTree(readingJson));
        return station;
    }

    @Test
    void projectsOnlyLevelAndLog() throws Exception {
        ObjectNode station = (ObjectNode) station("{\"level\":2,\"log\":\"风从站台尽头吹来\",\"qa\":{\"answer\":\"秘密\"}}");
        ObjectNode projected = ArrivalReading.project(station);
        assertEquals(2, projected.path("level").asInt());
        assertEquals("风从站台尽头吹来", projected.path("log").asText());
        assertEquals(2, projected.size(), "只允许 level/log，答案与其它键不投影");
        assertFalse(projected.has("qa"));
    }

    @Test
    void missingReadingYieldsNoField() throws Exception {
        assertNull(ArrivalReading.project(station(null)));
        assertNull(ArrivalReading.project(null));
    }

    @Test
    void emptyReadingIsRejectedAtSave() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalReading.normalize(JSON.readTree("{}")));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalReading.normalize(JSON.readTree("{\"level\":4}")));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalReading.normalize(JSON.readTree("{\"level\":-1}")));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalReading.normalize(JSON.readTree("{\"log\":\"  \"}")));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalReading.normalize(JSON.readTree("{\"level\":1,\"answer\":\"x\"}")));
    }

    @Test
    void levelUsesExistingNarrativeScale() throws Exception {
        for (int level = ArrivalReading.LEVEL_MIN; level <= ArrivalReading.LEVEL_MAX; level++) {
            ObjectNode normalized = ArrivalReading.normalize(
                    JSON.readTree("{\"level\":" + level + ",\"log\":\"到了\"}"));
            assertEquals(level, normalized.path("level").asInt());
        }
    }

    @Test
    void frozenReadingIsProjectedFromStationCopy() throws Exception {
        ObjectNode frozen = (ObjectNode) station("{\"level\":1,\"log\":\"旧局的读数\"}");
        // 模拟发布后作者改了实时配置：运行时只读冻结站点副本，不回读实时节点配置。
        String liveConfig = "{\"arrivalReading\":{\"level\":3,\"log\":\"新局的读数\"}}";
        assertEquals("旧局的读数", ArrivalReading.project(frozen).path("log").asText());
        assertFalse(liveConfig.contains("旧局"));
    }

    @Test
    void publicProjectionDoesNotCarryArrivalReading() {
        String safe = AdvancedGamePublicProjection.safeConfigJson(
                "{\"schemaVersion\":1,\"arrivalReading\":{\"level\":2,\"log\":\"只给到了的人看\"}}");
        assertTrue(safe != null);
        assertFalse(safe.contains("arrivalReading"));
        assertFalse(safe.contains("只给到了的人看"));
    }

    @Test
    void authorBlockValidationAcceptsArrivalFieldsAndRejectsExtraKeys() {
        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(
                "{\"schemaVersion\":1,\"arrivalPolicy\":{\"radiusMeters\":60},"
                        + "\"arrivalReading\":{\"level\":1,\"log\":\"到了\"}}");
        assertTrue(normalized.contains("\"arrivalReading\""));
        assertTrue(normalized.contains("\"arrivalPolicy\""));
        assertThrows(IllegalArgumentException.class, () -> new AdvancedGameConfigValidator().validateAndNormalize(
                "{\"schemaVersion\":1,\"arrivalReading\":{\"level\":1,\"log\":\"到了\",\"answer\":\"x\"}}"));
    }
}
