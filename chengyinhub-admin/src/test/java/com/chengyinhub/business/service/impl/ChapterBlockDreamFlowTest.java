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
 * 章节块型 dream(预制人生 §3.2)的写入链与读链路。
 *
 * 契约只点了 ChapterBlockReadSupport,但真正拦新块型写入的是 ChapterFlowCompiler 的白名单 ——
 * 只改读侧的话,C 的梦块一保存就报「块类型只允许 text / node / image / audio」,
 * 梦静默不存在。所以这里两层一起验。
 *
 * ★ 硬约束:description 投影仍在首个 node 块处停,dream 块不得改变这条截断。
 */
class ChapterBlockDreamFlowTest {

    @Test
    void 梦块能存能读_且不改变description的首节点截断() {
        ChapterDTO chapter = chapterWithDream();

        ChapterFlowCompiler.CompiledFlow flow = ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter));
        ChapterFlowCompiler.CompiledChapter compiled = flow.getChapters().get(0);

        // 负控(dream 若错误地把「首个节点之前」判成 false,这里会少掉 dream 之后的「正文二」):
        assertEquals("正文一\n正文二", compiled.getDescription(),
                "description 只收首个 node 块之前的正文;dream 既不投影也不取消该截断");

        String blocksJson = compiled.materializeBlocks(Collections.singletonList(99L));
        assertTrue(blocksJson.contains("\"type\":\"dream\""), blocksJson);
        assertTrue(blocksJson.contains("\"key\":\"d1\""), blocksJson);
        assertTrue(blocksJson.contains("\"url\":\"https://cdn.example.com/a.jpg\""), blocksJson);
        assertTrue(blocksJson.contains("\"line\":\"穿过去\""), blocksJson);

        // 读侧:已物化块原样透传,dream 不被解释也不被丢
        CmsTopicChapter stored = new CmsTopicChapter();
        stored.setId(7L);
        stored.setBlocksJson(blocksJson);
        List<Object> resolved = ChapterBlockReadSupport.resolve(stored, Collections.<com.chengyinhub.business.domain.CmsTopicNode>emptyList());
        assertEquals(findTypes(resolved), Arrays.asList("text", "dream", "text", "node", "text"));

        // 玩家读模型:截断点仍由第一个未完成 node 块决定;dream 在它之前,照常透传
        List<Object> projected = ChapterBlockReadSupport.projectForPlayer(
                resolved, new LinkedHashSet<Long>());
        assertEquals(Arrays.asList("text", "dream", "text", "node"), findTypes(projected),
                "node 块未完成 → 含 node 块截断;站后的 text 不出服务端");
    }

    @Test
    void 负控_梦块必须配图() {
        ChapterDTO chapter = chapterWithDream();
        chapter.getBlocks().get(1).setImages(new ArrayList<ChapterBlockDTO.DreamImageDTO>());

        ChapterFlowValidationException e = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
        assertTrue(e.getMessage().contains("dream"), e.getMessage());
    }

    @Test
    void 负控_梦块每张图都要有url() {
        ChapterDTO chapter = chapterWithDream();
        ChapterBlockDTO.DreamImageDTO blank = new ChapterBlockDTO.DreamImageDTO();
        chapter.getBlocks().get(1).setImages(Collections.singletonList(blank));

        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
    }

    @Test
    void 负控_梦块不能冒充节点块() {
        ChapterDTO chapter = chapterWithDream();
        chapter.getBlocks().get(1).setNodeIndex(Integer.valueOf(0));
        // 这里要的是「dream 带 nodeIndex 就当场打回」;把真 node 块摘掉,避免它先报别的错
        List<ChapterBlockDTO> blocks = new ArrayList<>(chapter.getBlocks());
        blocks.remove(3);
        chapter.setBlocks(blocks);

        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
    }

    @Test
    void 梦块六张完整存读_第七张拒绝() {
        ChapterDTO chapter = chapterWithDream();
        List<ChapterBlockDTO.DreamImageDTO> images = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ChapterBlockDTO.DreamImageDTO image = new ChapterBlockDTO.DreamImageDTO();
            image.setUrl("https://cdn.example.com/" + i + ".jpg");
            images.add(image);
        }
        chapter.getBlocks().get(1).setImages(images);
        String saved = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter))
                .getChapters().get(0).materializeBlocks(Collections.singletonList(99L));
        CmsTopicChapter stored = new CmsTopicChapter();
        stored.setBlocksJson(saved);
        Map<?, ?> dream = (Map<?, ?>) ChapterBlockReadSupport.resolve(stored,
                Collections.<com.chengyinhub.business.domain.CmsTopicNode>emptyList()).get(1);
        assertEquals(6, ((List<?>) dream.get("images")).size());
        images.add(images.get(0));
        ChapterFlowValidationException error = assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
        assertTrue(error.getMessage().contains("dream 块须配 1 至 6 张图"), error.getMessage());
    }

    // ==================== 脚手架 ====================

    /** [正文一, dream, 站前正文二, node(0), 站后正文] */
    private static ChapterDTO chapterWithDream() {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setSchemaVersion(Integer.valueOf(1));
        chapter.setRequired(Integer.valueOf(1));
        chapter.setNodes(Collections.singletonList(new NodeDTO()));

        ChapterBlockDTO text = new ChapterBlockDTO();
        text.setType("text");
        text.setContent("正文一");

        ChapterBlockDTO dream = new ChapterBlockDTO();
        dream.setType("dream");
        dream.setKey("d1");
        ChapterBlockDTO.DreamImageDTO first = new ChapterBlockDTO.DreamImageDTO();
        first.setUrl("https://cdn.example.com/a.jpg");
        first.setLine("穿过去");
        ChapterBlockDTO.DreamImageDTO second = new ChapterBlockDTO.DreamImageDTO();
        second.setUrl("/prod-api/profile/b.jpg");
        dream.setImages(Arrays.asList(first, second));

        ChapterBlockDTO node = new ChapterBlockDTO();
        node.setType("node");
        node.setNodeIndex(Integer.valueOf(0));

        ChapterBlockDTO beforeNode = new ChapterBlockDTO();
        beforeNode.setType("text");
        beforeNode.setContent("正文二");

        ChapterBlockDTO after = new ChapterBlockDTO();
        after.setType("text");
        after.setContent("站后正文");

        chapter.setBlocks(Arrays.asList(text, dream, beforeNode, node, after));
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
