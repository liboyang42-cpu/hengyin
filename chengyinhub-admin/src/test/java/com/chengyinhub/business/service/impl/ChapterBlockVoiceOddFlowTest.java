package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 《预制人生》两种表现块:voice(内心声音)与 odd(越往后越不正常的档位)。
 * 与 mood 同一条口径:不投影进 description,不改变首个节点之前的截断。
 */
class ChapterBlockVoiceOddFlowTest {

    @Test
    void textPresentationRoundTripAndBounds() {
        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text"); text.setKey("role-text"); text.setContent("请坐。");
        text.setWho("陈老师"); text.setLevel(2);
        String rows = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(text, odd(0))))
                .getChapters().get(0).materializeBlocks(Collections.singletonList(99L));
        assertTrue(rows.contains("\"who\":\"陈老师\""), rows);
        assertTrue(rows.contains("\"level\":2"), rows);
        text.setLevel(4);
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(text, odd(0)))));
        text.setLevel(0); text.setWho(String.join("", Collections.nCopies(21, "字")));
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(text, odd(0)))));
    }

    @Test
    void 内心声音与异常档位能存_且不进description() {
        ChapterFlowCompiler.CompiledChapter compiled = ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(voice("规矩", "回到推荐路线上。"), odd(2)))).getChapters().get(0);

        assertEquals("正文一", compiled.getDescription());
        String blocksJson = compiled.materializeBlocks(Collections.singletonList(99L));
        assertTrue(blocksJson.contains("\"type\":\"voice\",\"key\":\"v1\",\"who\":\"规矩\",\"content\":\"回到推荐路线上。\""), blocksJson);
        assertTrue(blocksJson.contains("\"type\":\"odd\",\"key\":\"o1\",\"level\":2"), blocksJson);
    }

    @Test
    void 负控_不认识的声音_空话_越界档位一律拒() {
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(voice("旁白", "随便说"), odd(1)))));
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(voice("心", "  "), odd(1)))));
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(voice("心", "好"), odd(4)))));
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(voice("心", "好"), odd(null)))));
    }

    @Test
    void 反转块存对面的名字与回话_空话判红() {
        ChapterBlockDTO reveal = new ChapterBlockDTO();
        reveal.setType("reveal");
        reveal.setKey("r1");
        reveal.setWho("王倩");
        reveal.setContent("你在跟谁说话?\n我一直在这儿。");
        String blocksJson = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(reveal, odd(0))))
                .getChapters().get(0).materializeBlocks(Collections.singletonList(99L));
        assertTrue(blocksJson.contains("\"type\":\"reveal\",\"key\":\"r1\",\"who\":\"王倩\""), blocksJson);

        reveal.setContent(" ");
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(reveal, odd(0)))));
    }

    @Test
    void 条件正文与条件内心声音_存when_不进摘要_玩家没这个标签就不下发() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setKey("t9");
        text.setContent("你本来想当画师。");
        text.setWhen(json.readTree("{\"op\":\"HAS_TAG\",\"value\":\"tag.m_painter\"}"));
        ChapterBlockDTO voice = voice("窗外", "你看过窗外。");
        voice.setWhen(json.readTree("{\"op\":\"HAS_TAG\",\"value\":\"thought.window.done\"}"));
        ChapterFlowCompiler.CompiledChapter compiled = ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(text, voice))).getChapters().get(0);
        assertEquals("正文一", compiled.getDescription(), "条件正文不进摘要");
        String blocksJson = compiled.materializeBlocks(Collections.singletonList(99L));
        assertTrue(blocksJson.contains("\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.m_painter\"}"), blocksJson);

        java.util.List<Object> rows = json.readValue(blocksJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Object>>() { });
        java.util.List<Object> none = com.chengyinhub.business.util.ChapterBlockReadSupport.projectForPlayer(rows,
                java.util.Collections.<Long>emptySet(), java.util.Collections.<String>emptySet());
        java.util.List<Object> painter = com.chengyinhub.business.util.ChapterBlockReadSupport.projectForPlayer(rows,
                java.util.Collections.<Long>emptySet(), java.util.Collections.singleton("tag.m_painter"));
        assertEquals(none.size() + 1, painter.size(), "有这个标签才多下发那一句");
        assertTrue(!String.valueOf(none).contains("你本来想当画师"), String.valueOf(none));
        assertTrue(!String.valueOf(painter).contains("你看过窗外"), "伏笔没应验,那句内心声音不下发");
    }

    @Test
    void 负控_条件只认选项标签与伏笔应验() throws Exception {
        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setContent("好");
        text.setWhen(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"op\":\"GTE\",\"var\":\"counter.x\",\"value\":1}"));
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(text, odd(0)))));
    }

    // ==================== 脚手架 ====================

    /** [正文一, voice, odd, node(0)] */
    private static ChapterDTO chapter(ChapterBlockDTO voice, ChapterBlockDTO odd) {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setSchemaVersion(Integer.valueOf(1));
        chapter.setRequired(Integer.valueOf(1));
        chapter.setNodes(Collections.singletonList(new NodeDTO()));
        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setContent("正文一");
        ChapterBlockDTO node = new ChapterBlockDTO();
        node.setType("node");
        node.setNodeIndex(Integer.valueOf(0));
        chapter.setBlocks(Arrays.asList(text, voice, odd, node));
        return chapter;
    }

    private static ChapterBlockDTO voice(String who, String line) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("voice");
        block.setKey("v1");
        block.setWho(who);
        block.setContent(line);
        return block;
    }

    private static ChapterBlockDTO odd(Integer level) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("odd");
        block.setKey("o1");
        block.setLevel(level);
        return block;
    }
}
