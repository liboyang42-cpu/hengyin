package com.chengyinhub.business.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R13-NPC:节点块配置 enter / outcome 的 npcId 重映射。 */
class NarrativeNpcRefsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void remapsEnterAndOutcomeNpcIds() throws Exception {
        String config = "{\"schemaVersion\":1,"
                + "\"enter\":{\"npcId\":501,\"opener\":\"来了\"},"
                + "\"outcome\":{\"npcId\":502,\"line\":\"辛苦了\"},"
                + "\"effects\":[]}";
        Map<Long, Long> npcIdMap = new HashMap<Long, Long>();
        npcIdMap.put(501L, 9501L);
        npcIdMap.put(502L, 9502L);
        List<String> warnings = new ArrayList<String>();

        assertTrue(NarrativeNpcRefs.hasNpcRefs(config));
        JsonNode root = JSON.readTree(NarrativeNpcRefs.remapNpcIds(config, npcIdMap, warnings));

        assertEquals(9501L, root.path("enter").path("npcId").asLong());
        assertEquals(9502L, root.path("outcome").path("npcId").asLong());
        assertEquals(0, root.path("effects").size(), "其他字段不动");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void unmappableNpcIdIsNulledWithWarning() throws Exception {
        String config = "{\"schemaVersion\":1,\"enter\":{\"npcId\":601,\"opener\":\"来了\"}}";
        List<String> warnings = new ArrayList<String>();

        JsonNode root = JSON.readTree(NarrativeNpcRefs.remapNpcIds(config,
                new HashMap<Long, Long>(), warnings));

        assertTrue(root.path("enter").path("npcId").isNull(), "映射不到的 npcId 必须置空");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("enter.npcId=601"), warnings.get(0));
    }

    @Test
    void configWithoutNpcRefsReturnsSameInstance() {
        String config = "{\"schemaVersion\":1,\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.x\"}]}";
        List<String> warnings = new ArrayList<String>();

        String remapped = NarrativeNpcRefs.remapNpcIds(config, new HashMap<Long, Long>(), warnings);

        assertFalse(NarrativeNpcRefs.hasNpcRefs(config));
        assertEquals(config, remapped);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void invalidJsonIsNotTreatedAsNpcRefs() {
        assertFalse(NarrativeNpcRefs.hasNpcRefs("{\"enter\":"));
    }

    /**
     * R19:叙事搬进章节故事流后,beat 块上的 npcId 与模板 enter/outcome 同一套 old→new
     * 重映射;映射不到置空并告警(fail-closed,不静默指向来源主题)。
     *
     * <p>负控:让 remapChapterBlockNpcIds 直接原样返回,本用例立刻红。
     */
    @Test
    void remapsChapterStoryFlowBeatNpcIds() throws Exception {
        String blocks = "[{\"type\":\"text\",\"content\":\"章节旁白\"},"
                + "{\"type\":\"node\",\"nodeId\":11},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"nodeId\":11,\"npcId\":501,"
                + "\"content\":\"来了\"},"
                + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"line\",\"nodeId\":11,\"npcId\":502,"
                + "\"content\":\"辛苦了\"}]";
        Map<Long, Long> npcIdMap = new HashMap<Long, Long>();
        npcIdMap.put(501L, 9501L);
        npcIdMap.put(502L, 9502L);
        List<String> warnings = new ArrayList<String>();

        String remapped = NarrativeNpcRefs.remapChapterBlockNpcIds(blocks, npcIdMap, warnings);

        JsonNode stored = JSON.readTree(remapped);
        assertEquals(9501L, stored.get(2).path("npcId").asLong());
        assertEquals(9502L, stored.get(3).path("npcId").asLong());
        assertEquals("来了", stored.get(2).path("content").asText(), "正文等字段不动");
        assertEquals("章节旁白", stored.get(0).path("content").asText());
        assertTrue(warnings.isEmpty());
    }

    /**
     * R22-SEC S13:字符串形态的 npcId 同样要重映射(与模板 enter/outcome 的 canConvertToLong 口径一致)。
     *
     * <p>负控:改回只认 instanceof Number,本用例立刻红(字符串 npcId 静默留在来源主题 id)。
     */
    @Test
    void remapsStringShapedChapterNpcId() throws Exception {
        String blocks = "[{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"nodeId\":11,"
                + "\"npcId\":\"501\",\"content\":\"来了\"}]";
        Map<Long, Long> npcIdMap = new HashMap<Long, Long>();
        npcIdMap.put(501L, 9501L);
        List<String> warnings = new ArrayList<String>();

        JsonNode stored = JSON.readTree(NarrativeNpcRefs.remapChapterBlockNpcIds(blocks, npcIdMap, warnings));

        assertEquals(9501L, stored.get(0).path("npcId").asLong(), "字符串 npcId 必须重映射");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void unmappableChapterNpcIdIsNulledWithWarning() throws Exception {
        String blocks = "[{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"nodeId\":11,"
                + "\"npcId\":601,\"content\":\"来了\"}]";
        List<String> warnings = new ArrayList<String>();

        JsonNode stored = JSON.readTree(NarrativeNpcRefs.remapChapterBlockNpcIds(blocks,
                new HashMap<Long, Long>(), warnings));

        assertTrue(stored.get(0).path("npcId").isNull(), "映射不到的 npcId 必须置空");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("=601"), warnings.get(0));
    }

    /** 没有 npcId 的块(含不带 npcId 的 beat 块)原样返回,调用方据此跳过回填。 */
    @Test
    void chapterBlocksWithoutNpcRefsReturnSameInstance() {
        String blocks = "[{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"body\",\"nodeId\":11,"
                + "\"content\":\"委托正文\"}]";
        List<String> warnings = new ArrayList<String>();

        assertEquals(blocks,
                NarrativeNpcRefs.remapChapterBlockNpcIds(blocks, new HashMap<Long, Long>(), warnings));
        assertTrue(warnings.isEmpty());
    }

    /** 复制 / 重开是新写入:解不开的故事流不能原样搬过去。 */
    @Test
    void brokenChapterBlocksFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> NarrativeNpcRefs.remapChapterBlockNpcIds("{broken", new HashMap<Long, Long>(), null));
        assertThrows(IllegalArgumentException.class,
                () -> NarrativeNpcRefs.remapChapterBlockNpcIds("{\"npcId\":501}", new HashMap<Long, Long>(), null));
    }
}
