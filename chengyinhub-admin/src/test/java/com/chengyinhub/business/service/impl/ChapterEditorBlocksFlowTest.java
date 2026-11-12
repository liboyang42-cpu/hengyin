package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑器原型 v2 的三种块(2026-11-10 l-editor):图片组 gallery(1–4 格一组图,整组一个块)、
 * 分支任务 branch(问题 + 2 至 3 条分支,每条写去向)、章节卡 cap(开场白只能在最前,结语只能在最后)。
 * 落库原样保存、重开再保存逐字一致;都不进 description 投影;形状不对的当场打回。
 */
class ChapterEditorBlocksFlowTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void galleryBranchAndCapsRoundTripAndStayOutOfDescription() throws Exception {
        ChapterBlockDTO open = cap("c0", "open", "雨刚停,石板路上还有积水。");
        ChapterBlockDTO para = text("p1", "她推开咖啡馆的门。");
        ChapterBlockDTO gallery = gallery("g1", 2, "/profile/upload/a.jpg", "/profile/upload/b.jpg");
        gallery.setAltText("吧台上的那杯咖啡");
        ChapterBlockDTO branch = branch("b1", "先去哪一处?", option("直接去邮局", "continue"), option("先回旧书店", "chapter:1"));
        ChapterBlockDTO end = cap("c9", "end", "门铃响了一声,她没有回头。");
        ChapterFlowCompiler.CompiledChapter compiled = ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(open, para, gallery, branch, end))).getChapters().get(0);
        assertEquals("她推开咖啡馆的门。", compiled.getDescription(), "开场白 / 图片组 / 分支 / 结语都不进摘要");

        JsonNode rows = json.readTree(compiled.materializeBlocks(Collections.<Long>emptyList()));
        assertEquals(5, rows.size());
        assertEquals("cap", rows.get(0).path("type").asText());
        assertEquals("open", rows.get(0).path("cap").asText());
        assertEquals(2, rows.get(2).path("layout").asInt());
        assertEquals("/profile/upload/b.jpg", rows.get(2).path("images").get(1).path("url").asText());
        assertEquals("吧台上的那杯咖啡", rows.get(2).path("altText").asText());
        assertEquals("先去哪一处?", rows.get(3).path("content").asText());
        assertEquals("chapter:1", rows.get(3).path("options").get(1).path("to").asText());
        assertEquals("end", rows.get(4).path("cap").asText());

        List<ChapterBlockDTO> reopened = new ArrayList<>();
        for (JsonNode row : rows) reopened.add(json.treeToValue(row, ChapterBlockDTO.class));
        JsonNode again = json.readTree(ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(reopened.toArray(new ChapterBlockDTO[0])))).getChapters().get(0)
                .materializeBlocks(Collections.<Long>emptyList()));
        assertEquals(rows, again, "重开再保存逐字一致");
    }

    @Test
    void rejectsMalformedGalleryBranchAndMisplacedCaps() {
        ChapterBlockDTO tooWide = gallery("g", 2, "/a.jpg", "/b.jpg", "/c.jpg");
        assertRejected("图片组张数超过版式", tooWide);
        ChapterBlockDTO noLayout = gallery("g", 9, "/a.jpg");
        assertRejected("版式越界", noLayout);
        ChapterBlockDTO emptyGallery = gallery("g", 1);
        assertRejected("图片组没有图", emptyGallery);

        ChapterBlockDTO oneOption = branch("b", "问题", option("只有一条", "continue"));
        assertRejected("分支少于两条", oneOption);
        ChapterBlockDTO badTo = branch("b", "问题", option("甲", "continue"), option("乙", "somewhere"));
        assertRejected("去向不认识", badTo);
        ChapterBlockDTO missingChapter = branch("b", "问题", option("甲", "continue"), option("乙", "chapter:7"));
        assertRejected("跳到不存在的章", missingChapter);
        ChapterBlockDTO noQuestion = branch("b", "  ", option("甲", "continue"), option("乙", "end"));
        assertRejected("没有问题", noQuestion);

        ChapterBlockDTO openLate = cap("c", "open", "开场白");
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(text("p", "正文"), openLate))), "开场白不在最前");
        ChapterBlockDTO endEarly = cap("c", "end", "结语");
        assertThrows(ChapterFlowValidationException.class, () -> ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(endEarly, text("p", "正文")))), "结语不在最后");
        ChapterBlockDTO blankCap = cap("c", "open", " ");
        assertRejected("章节卡没有话", blankCap);
        ChapterBlockDTO badCap = cap("c", "middle", "话");
        assertRejected("卡位不认识", badCap);
    }

    private static void assertRejected(String why, ChapterBlockDTO block) {
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(block))), why);
    }

    private static ChapterDTO chapter(ChapterBlockDTO... blocks) {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setName("第一章");
        chapter.setSchemaVersion(1);
        chapter.setRequired(1);
        chapter.setNodes(Collections.emptyList());
        chapter.setBlocks(Arrays.asList(blocks));
        return chapter;
    }

    private static ChapterBlockDTO text(String key, String content) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setKey(key);
        block.setType("text");
        block.setContent(content);
        return block;
    }

    private static ChapterBlockDTO cap(String key, String cap, String content) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setKey(key);
        block.setType("cap");
        block.setCap(cap);
        block.setContent(content);
        return block;
    }

    private static ChapterBlockDTO gallery(String key, int layout, String... urls) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setKey(key);
        block.setType("gallery");
        block.setLayout(layout);
        List<ChapterBlockDTO.DreamImageDTO> images = new ArrayList<>();
        for (String url : urls) {
            ChapterBlockDTO.DreamImageDTO image = new ChapterBlockDTO.DreamImageDTO();
            image.setUrl(url);
            images.add(image);
        }
        block.setImages(images);
        return block;
    }

    private static ChapterBlockDTO branch(String key, String question, ChapterBlockDTO.BranchOptionDTO... options) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setKey(key);
        block.setType("branch");
        block.setContent(question);
        block.setOptions(Arrays.asList(options));
        return block;
    }

    private static ChapterBlockDTO.BranchOptionDTO option(String text, String to) {
        ChapterBlockDTO.BranchOptionDTO option = new ChapterBlockDTO.BranchOptionDTO();
        option.setText(text);
        option.setTo(to);
        return option;
    }
}
