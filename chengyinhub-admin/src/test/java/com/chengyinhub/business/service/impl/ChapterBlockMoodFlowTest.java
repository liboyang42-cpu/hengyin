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
 * 章节块型 mood:正文流里的氛围标记,滚到它就把整屏基调切过去。
 *
 * 配色预设复用章节那套(2026-09-06 用户拍板的黑/蓝/红/黄/白纯色,无渐变),不另立名单 ——
 * mood 块只是把「整章一个颜色」变成「段落级可切换」。
 *
 * 定层依据见《通用故事游戏_施工与验收_20260921》「思维阁与氛围:落到哪一层」——
 * 原型把氛围标在正文节点上(n.dataset.cos)且章内会切多次,所以它是块、不是章节属性,
 * 也不是状态(不参与任何权威结算)。
 *
 * ★ 与 dream 同一条硬约束:mood 既不投影进 description,也不改变「首个节点之前」的截断。
 */
class ChapterBlockMoodFlowTest {

    @Test
    void 氛围块能存能读_且不改变description的首节点截断() {
        ChapterDTO chapter = chapterWithMood();

        ChapterFlowCompiler.CompiledFlow flow = ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter));
        ChapterFlowCompiler.CompiledChapter compiled = flow.getChapters().get(0);

        // 负控:mood 若错误地把「首个节点之前」判成 false,这里会少掉 mood 之后的「正文二」
        assertEquals("正文一\n正文二", compiled.getDescription(),
                "description 只收首个 node 块之前的正文;mood 既不投影也不取消该截断");

        String blocksJson = compiled.materializeBlocks(Collections.singletonList(99L));
        assertTrue(blocksJson.contains("\"type\":\"mood\""), blocksJson);
        assertTrue(blocksJson.contains("\"key\":\"m1\""), blocksJson);
        assertTrue(blocksJson.contains("\"mood\":\"YELLOW\""), blocksJson);

        CmsTopicChapter stored = new CmsTopicChapter();
        stored.setId(7L);
        stored.setBlocksJson(blocksJson);
        List<Object> resolved = ChapterBlockReadSupport.resolve(
                stored, Collections.<com.chengyinhub.business.domain.CmsTopicNode>emptyList());
        assertEquals(Arrays.asList("text", "mood", "text", "node", "text"), findTypes(resolved));

        // 玩家读模型:截断点仍由第一个未完成 node 块决定;mood 在它之前,照常透传
        List<Object> projected = ChapterBlockReadSupport.projectForPlayer(
                resolved, new LinkedHashSet<Long>());
        assertEquals(Arrays.asList("text", "mood", "text", "node"), findTypes(projected),
                "截断点是未完成的 node 块之后;node 自身保留,它前面的 mood 照常透传");
    }

    @Test
    void 负控_氛围必须是章节配色预设之一() {
        ChapterDTO chapter = chapterWithMood();
        ((ChapterBlockDTO) chapter.getBlocks().get(1)).setMood("随便写的调子");
        ChapterFlowValidationException e = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
        assertTrue(e.getMessage().contains("氛围"), e.getMessage());
    }

    @Test
    void 氛围复用章节配色_存量别名收敛成新档而不是报错() {
        ChapterDTO chapter = chapterWithMood();
        // 库里存着 2026-09-06 换档前的 NIGHT/ARCHIVE/MOSS/NEON;老章节不能一保存就抛
        ((ChapterBlockDTO) chapter.getBlocks().get(1)).setMood("NIGHT");
        String blocksJson = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter))
                .getChapters().get(0).materializeBlocks(Collections.singletonList(99L));
        assertTrue(blocksJson.contains("\"mood\":\"BLUE\""), "NIGHT 要收敛成 BLUE:" + blocksJson);
    }

    @Test
    void 负控_氛围块不能留空() {
        ChapterDTO chapter = chapterWithMood();
        ((ChapterBlockDTO) chapter.getBlocks().get(1)).setMood(null);
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
    }

    @Test
    void 负控_氛围块不能冒充节点块也不能夹带正文() {
        ChapterDTO withNodeIndex = chapterWithMood();
        ((ChapterBlockDTO) withNodeIndex.getBlocks().get(1)).setNodeIndex(Integer.valueOf(0));
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(withNodeIndex)));

        ChapterDTO withContent = chapterWithMood();
        ((ChapterBlockDTO) withContent.getBlocks().get(1)).setContent("偷偷塞正文");
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(withContent)));
    }

    // ==================== 脚手架 ====================

    /** [正文一, mood, 站前正文二, node(0), 站后正文] */
    private static ChapterDTO chapterWithMood() {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setSchemaVersion(Integer.valueOf(1));
        chapter.setRequired(Integer.valueOf(1));
        chapter.setNodes(Collections.singletonList(new NodeDTO()));

        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setContent("正文一");

        ChapterBlockDTO mood = new ChapterBlockDTO();
        mood.setType("mood");
        mood.setKey("m1");
        mood.setMood("YELLOW");

        ChapterBlockDTO beforeNode = new ChapterBlockDTO();
        beforeNode.setType("text");
        beforeNode.setContent("正文二");

        ChapterBlockDTO node = new ChapterBlockDTO();
        node.setType("node");
        node.setNodeIndex(Integer.valueOf(0));

        ChapterBlockDTO after = new ChapterBlockDTO();
        after.setType("text");
        after.setContent("站后正文");

        chapter.setBlocks(Arrays.asList(text, mood, beforeNode, node, after));
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
