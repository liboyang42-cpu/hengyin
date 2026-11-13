package com.chengyinhub.business.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结局 = 结局章(2026-09-24):按章节重建结局列表、章节 id 重映射、玩家章节列表里认出结局章。 */
class JourneyStoryEndingChapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void 按结局章重建结局列表_保留尾声等其它段_站点条件跟着换新id() throws Exception {
        String raw = "{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"old\",\"title\":\"旧\",\"fallback\":true}],"
                + "\"epilogues\":[{\"npcId\":3}]}}";
        Map<Long, Long> nodes = new HashMap<Long, Long>();
        nodes.put(501L, 901L);
        String out = JourneyStory.withEndingChapters(raw, Arrays.asList(
                new JourneyStory.EndingChapter(71L, "窗外", "你推开了窗。", JSON.readTree(
                        "{\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":501},{\"op\":\"HAS_TAG\",\"value\":\"thought.window.done\"}]}")),
                new JourneyStory.EndingChapter(72L, "  ", "", JSON.readTree("{\"fallback\":true}"))), nodes);
        JsonNode ending = JSON.readTree(out).path("ending");
        assertEquals(2, ending.path("endings").size(), "旧列表整份换成结局章");
        JsonNode first = ending.path("endings").get(0);
        assertEquals("ending1", first.path("code").asText());
        assertEquals("窗外", first.path("title").asText());
        assertEquals("你推开了窗。", first.path("summary").asText());
        assertEquals(71L, first.path("chapterId").asLong());
        assertEquals(901L, first.path("when").get(0).path("nodeId").asLong(), "到过某站按新站点 id");
        JsonNode second = ending.path("endings").get(1);
        assertEquals("结局 2", second.path("title").asText(), "章名空着就给个序号名");
        assertTrue(second.path("fallback").asBoolean());
        assertEquals(1, ending.path("epilogues").size(), "尾声原样保留");
    }

    // D-66（业主 2026-11-11）：结局章同样受「最多 6 个（含默认结局）」约束，
    // 作者不能靠多开结局章绕过 ending.endings 的数量上限。
    @Test
    void 负控_结局章超过六个就拒() throws Exception {
        java.util.List<JourneyStory.EndingChapter> chapters = new java.util.ArrayList<JourneyStory.EndingChapter>();
        for (int i = 1; i <= 5; i++) {
            chapters.add(new JourneyStory.EndingChapter((long) i, "结局" + i, "", JSON.readTree(
                    "{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]}")));
        }
        chapters.add(new JourneyStory.EndingChapter(6L, "兜底", "", JSON.readTree("{\"fallback\":true}")));
        // 6 个放行
        assertEquals(6, JSON.readTree(JourneyStory.withEndingChapters("{}", chapters, new HashMap<Long, Long>()))
                .path("ending").path("endings").size());
        // 第 7 个必须被拒
        chapters.add(new JourneyStory.EndingChapter(7L, "多余", "", JSON.readTree("{\"fallback\":false,\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.y\"}]}")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JourneyStory.withEndingChapters("{}", chapters, new HashMap<Long, Long>()));
        assertTrue(error.getMessage().contains("结局最多 6 个"));
    }

    @Test
    void 负控_站点不在本次提交里就拒() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> JourneyStory.withEndingChapters("{}",
                Collections.singletonList(new JourneyStory.EndingChapter(71L, "窗外", "", JSON.readTree(
                        "{\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":999}]}"))), new HashMap<Long, Long>()));
    }

    @Test
    void 重映射章节id时结局章跟着换_那一章没了就摘掉引用() throws Exception {
        String raw = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"ending1\",\"title\":\"窗外\",\"chapterId\":71,\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]},"
                + "{\"code\":\"ending2\",\"title\":\"余波\",\"chapterId\":72,\"fallback\":true}]}}";
        Map<Long, Long> chapters = new HashMap<Long, Long>();
        chapters.put(71L, 171L);
        JsonNode endings = JSON.readTree(JourneyStory.remapChapterIds(raw, chapters)).path("ending").path("endings");
        assertEquals(171L, endings.get(0).path("chapterId").asLong());
        assertFalse(endings.get(1).has("chapterId"), "映射不到就摘掉,不指向旧行");
        assertEquals(new java.util.LinkedHashSet<Long>(Collections.singletonList(71L)),
                JourneyStory.endingChapterIds(raw.replace(",\"chapterId\":72", "")));
        assertTrue(JourneyStory.endingChapterIds("坏的").isEmpty(), "读不出当没有结局章");
    }
}
