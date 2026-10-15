package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.JourneyStory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Raw helper regression only. Written but NOT_RUN; no database or service mock claims. */
class E03_11Test {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mapsAllKnownSlotsAndPreservesUnknownFieldsAndAuthorOrder() throws Exception {
        String raw = "{\"extra\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":11,\"var\":\"sys.outcome.11\"},"
                + "\"stages\":[{\"chapterId\":7,\"end\":{\"changed\":[{\"text\":\"sys.outcome.11 literal\","
                + "\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":11,\"extension\":{\"nodeId\":11}}}]}}],"
                + "\"ending\":{\"dispositionNodeId\":11,\"extra\":true,\"endings\":["
                + "{\"code\":\"first\",\"when\":[{\"op\":\"EQ\",\"var\":\"sys.outcome.11\",\"value\":2}]},"
                + "{\"code\":\"second\",\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":22}]}],"
                + "\"exhibits\":[{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":11},\"text\":\"exhibit\"}],"
                + "\"badges\":[{\"title\":\"badge\",\"when\":[{\"op\":\"EQ\",\"var\":\"sys.outcome.22\",\"value\":1}]}]}}";
        JsonNode before = JSON.readTree(raw);
        JsonNode mapped = JSON.readTree(JourneyStory.remapNodeIds(raw, mapping()));
        assertEquals(before.path("extra"), mapped.path("extra"));
        JsonNode change = mapped.path("stages").get(0).path("end").path("changed").get(0);
        assertEquals("sys.outcome.11 literal", change.path("text").asText());
        assertEquals(111L, change.path("when").path("nodeId").asLong());
        assertEquals(11L, change.path("when").path("extension").path("nodeId").asLong());
        JsonNode ending = mapped.path("ending");
        assertEquals(111L, ending.path("dispositionNodeId").asLong());
        assertTrue(ending.path("extra").asBoolean());
        assertEquals("first", ending.path("endings").get(0).path("code").asText());
        assertEquals("second", ending.path("endings").get(1).path("code").asText());
        assertEquals("sys.outcome.111", ending.path("endings").get(0).path("when").get(0).path("var").asText());
        assertEquals(222L, ending.path("endings").get(1).path("when").get(0).path("nodeId").asLong());
        assertEquals(111L, ending.path("exhibits").get(0).path("when").path("nodeId").asLong());
        assertEquals("sys.outcome.222", ending.path("badges").get(0).path("when").get(0).path("var").asText());
        assertEquals("extra", mapped.fieldNames().next());
    }

    @Test
    void newEndingConditionsMapOnceWithoutRemappingExistingRoot() throws Exception {
        String source = "{\"ending\":{\"dispositionNodeId\":11,\"preserved\":\"value\",\"endings\":[]}}";
        String prepared = JourneyStory.remapNodeIds(source, mapping());
        JsonNode authored = JSON.readTree("{\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":22},"
                + "{\"op\":\"EQ\",\"var\":\"sys.outcome.11\",\"value\":1}]}");
        String out = JourneyStory.withEndingChapters(prepared, Arrays.asList(
                new JourneyStory.EndingChapter(88L, "chosen", "", authored),
                new JourneyStory.EndingChapter(89L, "fallback", "", JSON.readTree("{\"fallback\":true}"))), mapping());
        JsonNode ending = JSON.readTree(out).path("ending");
        assertEquals(111L, ending.path("dispositionNodeId").asLong());
        assertEquals("value", ending.path("preserved").asText());
        assertEquals(222L, ending.path("endings").get(0).path("when").get(0).path("nodeId").asLong());
        assertEquals("sys.outcome.111", ending.path("endings").get(0).path("when").get(1).path("var").asText());
        assertEquals(22L, authored.path("when").get(0).path("nodeId").asLong(), "never mutate the chapter input");
        assertEquals("sys.outcome.11", authored.path("when").get(1).path("var").asText());
    }

    @Test
    void missingOrInvalidMappingNeverLeavesOldReferences() {
        String raw = "{\"ending\":{\"dispositionNodeId\":11}}";
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds(raw, null));
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds(raw, Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds(raw, Collections.singletonMap(11L, 0L)));
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds(raw, Collections.singletonMap(11L, -1L)));
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds(raw, Collections.singletonMap(11L, null)));
        String missing = "{\"ending\":{\"exhibits\":[{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":33}}]}}";
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds(missing, mapping()));
    }

    @Test
    void keepsFieldSpecificLegacyParsingAndRejectsInvalidExplicitIds() throws Exception {
        String coerced = "{\"ending\":{\"exhibits\":[{\"when\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":\"11\"}}]}}";
        assertEquals(111L, JSON.readTree(JourneyStory.remapNodeIds(coerced, mapping()))
                .path("ending").path("exhibits").get(0).path("when").path("nodeId").asLong());
        for (String disposition : new String[]{"\"11\"", "11.5", "0", "-1"}) {
            String raw = "{\"ending\":{\"dispositionNodeId\":" + disposition + "}}";
            assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds(raw, mapping()));
        }
        for (String variable : new String[]{"sys.outcome.0", "sys.outcome.01", "sys.outcome.x", "sys.outcome.9223372036854775808"}) {
            String raw = "{\"ending\":{\"exhibits\":[{\"when\":{\"op\":\"EQ\",\"var\":\"" + variable + "\",\"value\":1}}]}}";
            assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds(raw, mapping()));
        }
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds("[]", mapping()));
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.remapNodeIds("{", mapping()));
    }

    @Test
    void absentReferencesPreserveRawAndDoNotExpandOtherSystemVariableScope() {
        assertNull(JourneyStory.remapNodeIds(null, null));
        assertEquals("  ", JourneyStory.remapNodeIds("  ", null));
        String raw = "{ \"ending\": {\"dispositionNodeId\":null,\"exhibits\":["
                + "{\"when\":{\"op\":\"EQ\",\"var\":\"sys.passed.11\",\"value\":1}},"
                + "{\"when\":{\"op\":\"EQ\",\"var\":\"sys.asked.11.q\",\"value\":1}},"
                + "{\"when\":{\"op\":\"EQ\",\"var\":\"sys.mistakes.11\",\"value\":1}}]}}";
        assertEquals(raw, JourneyStory.remapNodeIds(raw, null));
    }

    private static Map<Long, Long> mapping() {
        Map<Long, Long> map = new LinkedHashMap<>();
        map.put(11L, 111L);
        map.put(22L, 222L);
        return map;
    }
}
