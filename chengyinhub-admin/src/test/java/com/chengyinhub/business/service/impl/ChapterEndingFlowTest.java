package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 结局章(2026-09-24):不是路线的一站 —— 不放站点、不兼开局、不招商;非兜底要有条件;兜底恰好一章。 */
class ChapterEndingFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void 正常章加两章结局_一个有条件一个兜底_能编译() throws Exception {
        List<ChapterDTO> list = Arrays.asList(normal(), ending("{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]}"),
                ending("{\"fallback\":true}"));
        assertEquals(3, ChapterFlowCompiler.compileChapterFlow(list).getChapters().size());
    }

    @Test
    void 负控_结局章四条规矩() throws Exception {
        ChapterDTO withNode = ending("{\"fallback\":true}");
        withNode.setNodes(Collections.singletonList(new NodeDTO()));
        assertRejected(Arrays.asList(normal(), withNode), "结局章里不能放站点");

        ChapterDTO openingEnding = ending("{\"fallback\":true}");
        openingEnding.setOpening(Boolean.TRUE);
        assertRejected(Arrays.asList(openingEnding, normal()), "开局不能同时是结局");

        assertRejected(Arrays.asList(normal(), ending("{\"when\":[]}"), ending("{\"fallback\":true}")), "要选一个触发条件");
        assertRejected(Arrays.asList(normal(), ending("{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]}")), "恰好有一章是兜底");
        assertRejected(Arrays.asList(normal(), ending("{\"fallback\":true}"), ending("{\"fallback\":true}")), "恰好有一章是兜底");
    }

    private static void assertRejected(List<ChapterDTO> list, String fragment) {
        ChapterFlowValidationException e = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(list));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    private static ChapterDTO normal() {
        ChapterDTO chapter = base();
        chapter.setNodes(Collections.singletonList(new NodeDTO()));
        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setContent("正文");
        ChapterBlockDTO node = new ChapterBlockDTO();
        node.setType("node");
        node.setNodeIndex(Integer.valueOf(0));
        chapter.setBlocks(Arrays.asList(text, node));
        return chapter;
    }

    private static ChapterDTO ending(String json) throws Exception {
        ChapterDTO chapter = base();
        chapter.setNodes(new ArrayList<NodeDTO>());
        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setContent("你推开了窗。");
        chapter.setBlocks(Collections.singletonList(text));
        chapter.setEnding(JSON.readTree(json));
        return chapter;
    }

    private static ChapterDTO base() {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setSchemaVersion(Integer.valueOf(1));
        chapter.setRequired(Integer.valueOf(1));
        return chapter;
    }
}
