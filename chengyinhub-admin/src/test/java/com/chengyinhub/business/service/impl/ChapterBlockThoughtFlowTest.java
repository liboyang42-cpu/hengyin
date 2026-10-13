package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节块型 thought:在正文流的这个位置「得到一个念头」。
 *
 * 定层依据见《通用故事游戏_施工与验收_20260921》「思维阁与氛围:落到哪一层」——
 * 念头的定义与效果住在 journeyRules(主题级、跨章节),而「在哪里得到它」是正文流里的
 * 一个位置标记,所以是块。与 mood 同样:不投影进 description,不改变首个节点之前的截断。
 *
 * ★ thoughtKey 的**存在性**这一层校验不了(编译器只拿得到章节流,拿不到 journeyRules),
 *   这里只管格式;引用了不存在的念头由发布闸兜。
 */
class ChapterBlockThoughtFlowTest {

    @Test
    void 念头块能存能读_且不改变description的首节点截断() {
        ChapterDTO chapter = chapterWithThought();

        ChapterFlowCompiler.CompiledFlow flow = ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter));
        ChapterFlowCompiler.CompiledChapter compiled = flow.getChapters().get(0);

        assertEquals("正文一\n正文二", compiled.getDescription(),
                "description 只收首个 node 块之前的正文;thought 既不投影也不取消该截断");

        String blocksJson = compiled.materializeBlocks(Collections.singletonList(99L));
        assertTrue(blocksJson.contains("\"type\":\"thought\""), blocksJson);
        assertTrue(blocksJson.contains("\"thoughtKey\":\"afternoon\""), blocksJson);
        assertTrue(blocksJson.contains("\"key\":\"t1\""), blocksJson);

        CmsTopicChapter stored = new CmsTopicChapter();
        stored.setId(7L);
        stored.setBlocksJson(blocksJson);
        List<Object> resolved = ChapterBlockReadSupport.resolve(
                stored, Collections.<com.chengyinhub.business.domain.CmsTopicNode>emptyList());
        assertEquals(Arrays.asList("text", "thought", "text", "node", "text"), findTypes(resolved));

        List<Object> projected = ChapterBlockReadSupport.projectForPlayer(
                resolved, new LinkedHashSet<Long>());
        assertEquals(Arrays.asList("text", "thought", "text", "node"), findTypes(projected),
                "未完成节点之后不下发;它前面的念头照常透传");
    }

    @Test
    void 负控_念头块必须指明是哪个念头() {
        ChapterDTO empty = chapterWithThought();
        ((ChapterBlockDTO) empty.getBlocks().get(1)).setThoughtKey(null);
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(empty)));

        ChapterDTO illegal = chapterWithThought();
        ((ChapterBlockDTO) illegal.getBlocks().get(1)).setThoughtKey("Not A Key");
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(illegal)));
    }

    @Test
    void 负控_同一个念头不能在一章里领两次() {
        ChapterDTO chapter = chapterWithThought();
        ChapterBlockDTO again = new ChapterBlockDTO();
        again.setType("thought");
        again.setKey("t2");
        again.setThoughtKey("afternoon");
        List<ChapterBlockDTO> blocks = new ArrayList<>(chapter.getBlocks());
        blocks.add(2, again);
        chapter.setBlocks(blocks);

        ChapterFlowValidationException e = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
        assertTrue(e.getMessage().contains("念头"), e.getMessage());
    }

    @Test
    void 负控_念头块不能冒充节点块也不能夹带正文() {
        ChapterDTO withNodeIndex = chapterWithThought();
        ((ChapterBlockDTO) withNodeIndex.getBlocks().get(1)).setNodeIndex(Integer.valueOf(0));
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(withNodeIndex)));

        ChapterDTO withContent = chapterWithThought();
        ((ChapterBlockDTO) withContent.getBlocks().get(1)).setContent("偷偷塞正文");
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(withContent)));
    }

    // ==================== 脚手架 ====================

    /** [正文一, thought, 站前正文二, node(0), 站后正文] */
    private static ChapterDTO chapterWithThought() {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setSchemaVersion(Integer.valueOf(1));
        chapter.setRequired(Integer.valueOf(1));
        chapter.setNodes(Collections.singletonList(new NodeDTO()));

        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setContent("正文一");

        ChapterBlockDTO thought = new ChapterBlockDTO();
        thought.setType("thought");
        thought.setKey("t1");
        thought.setThoughtKey("afternoon");

        ChapterBlockDTO beforeNode = new ChapterBlockDTO();
        beforeNode.setType("text");
        beforeNode.setContent("正文二");

        ChapterBlockDTO node = new ChapterBlockDTO();
        node.setType("node");
        node.setNodeIndex(Integer.valueOf(0));

        ChapterBlockDTO after = new ChapterBlockDTO();
        after.setType("text");
        after.setContent("站后正文");

        chapter.setBlocks(Arrays.asList(text, thought, beforeNode, node, after));
        return chapter;
    }

    private static List<String> findTypes(List<Object> blocks) {
        List<String> types = new ArrayList<>();
        for (Object item : blocks) {
            if (item instanceof Map) {
                types.add(String.valueOf(((Map<?, ?>) item).get("type")));
            }
        }
        return types;
    }
}
