package com.chengyinhub.business.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R13-NPC:主题剧情配置的 NPC 引用重映射。
 * 复制 / 重开一期后节点 NPC 换新 id,call 与 epilogues 必须跟着 old→new 走;
 * 门店分身不随复制,映射不到时 fail-closed 置空并给出提示。
 */
class JourneyStoryNpcRemapTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void remapsCallAndEpilogueNpcIdsToCopies() throws Exception {
        String story = "{\"schemaVersion\":1,"
                + "\"call\":{\"npcId\":501,\"opener\":\"喂\"},"
                + "\"ending\":{\"epilogues\":["
                + "{\"npcId\":501,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"},"
                + "{\"npcId\":502,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"}]}}";
        Map<Long, Long> npcIdMap = new HashMap<Long, Long>();
        npcIdMap.put(501L, 9501L);
        npcIdMap.put(502L, 9502L);
        List<String> warnings = new ArrayList<String>();

        JsonNode root = JSON.readTree(JourneyStory.remapNpcIds(story, npcIdMap, warnings));

        assertEquals(9501L, root.path("call").path("npcId").asLong());
        assertEquals(9501L, root.path("ending").path("epilogues").get(0).path("npcId").asLong());
        assertEquals(9502L, root.path("ending").path("epilogues").get(1).path("npcId").asLong());
        assertTrue(warnings.isEmpty(), "全部映射命中时不该有提示");
    }

    @Test
    void unmappableNpcIdIsNulledWithWarning() throws Exception {
        String story = "{\"schemaVersion\":1,"
                + "\"call\":{\"npcId\":501,\"opener\":\"喂\"},"
                + "\"ending\":{\"epilogues\":[{\"npcId\":601,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"}]}}";
        Map<Long, Long> npcIdMap = new HashMap<Long, Long>();
        npcIdMap.put(501L, 9501L);
        List<String> warnings = new ArrayList<String>();

        JsonNode root = JSON.readTree(JourneyStory.remapNpcIds(story, npcIdMap, warnings));

        assertEquals(9501L, root.path("call").path("npcId").asLong(), "可映射的照常换新 id");
        assertTrue(root.path("ending").path("epilogues").get(0).path("npcId").isNull(),
                "门店分身等不随复制的引用必须置空,不得保留旧 id");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("601"), warnings.get(0));
        assertTrue(warnings.get(0).contains("置空"), warnings.get(0));
    }

    @Test
    void storyWithoutNpcRefsReturnsRawJsonUnchanged() {
        String story = "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":11}]}";
        List<String> warnings = new ArrayList<String>();

        String remapped = JourneyStory.remapNpcIds(story, new HashMap<Long, Long>(), warnings);

        assertEquals(story, remapped, "没有 npcId 引用时不得改写配置(存什么读什么)");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void blankStoryIsReturnedUnchanged() {
        assertNull(JourneyStory.remapNpcIds(null, new HashMap<Long, Long>(), new ArrayList<String>()));
        assertEquals("  ", JourneyStory.remapNpcIds("  ", new HashMap<Long, Long>(), new ArrayList<String>()),
                "与章节重映射同口径:空配置原样返回,调用方自行判空");
    }
}
