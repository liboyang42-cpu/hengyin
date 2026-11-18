package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
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
 * 正文段样式与段内格式(2026-11-10 r2-create,X Articles 式故事编辑器)。
 * content 仍是纯文字;style / marks 只挂普通正文段,落库原样保存,重开再保存逐字一致。
 */
class ChapterTextFormatFlowTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void styleAndMarksRoundTripAndDescriptionStaysPlainText() throws Exception {
        ChapterBlockDTO heading = text("h", "雨夜");
        heading.setStyle("h2");
        ChapterBlockDTO body = text("p", "她推开门。");
        body.setMarks(Arrays.asList(mark(0, 1, "bold"), mark(2, 4, "strike")));
        var compiled = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(heading, body)))
                .getChapters().get(0);
        assertEquals("雨夜\n她推开门。", compiled.getDescription());
        JsonNode rows = json.readTree(compiled.materializeBlocks(Collections.singletonList(91L)));
        assertEquals("h2", rows.get(0).path("style").asText());
        assertEquals(json.readTree("[{\"from\":0,\"to\":1,\"type\":\"bold\"},{\"from\":2,\"to\":4,\"type\":\"strike\"}]"),
                rows.get(1).path("marks"));
        assertFalse(rows.get(1).has("style"));

        ChapterBlockDTO reopenedHeading = json.treeToValue(rows.get(0), ChapterBlockDTO.class);
        ChapterBlockDTO reopenedBody = json.treeToValue(rows.get(1), ChapterBlockDTO.class);
        JsonNode again = json.readTree(ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(reopenedHeading, reopenedBody))).getChapters().get(0)
                .materializeBlocks(Collections.singletonList(91L)));
        assertEquals(rows.get(0), again.get(0));
        assertEquals(rows.get(1), again.get(1));
    }

    /** 内容 1117:可选阅读块(optional:true)落库保留、重开再存一致;普通段不多出字段;非正文块不许设。 */
    @Test
    void optionalReadingBlockRoundTripsAndOnlyAppliesToText() throws Exception {
        ChapterBlockDTO side = text("side", "示例站点的掌故。");
        side.setWho("几个人一起来的话");
        side.setOptional(Boolean.TRUE);
        ChapterBlockDTO main = text("main", "你站在示例站点门口。");
        JsonNode rows = json.readTree(ChapterFlowCompiler.compileChapterFlow(
                Collections.singletonList(chapter(side, main))).getChapters().get(0)
                .materializeBlocks(Collections.singletonList(91L)));
        assertTrue(rows.get(0).path("optional").asBoolean());
        assertEquals("几个人一起来的话", rows.get(0).path("who").asText());
        assertFalse(rows.get(1).has("optional"));

        ChapterBlockDTO reopened = json.treeToValue(rows.get(0), ChapterBlockDTO.class);
        assertEquals(Boolean.TRUE, reopened.getOptional());

        ChapterBlockDTO divider = new ChapterBlockDTO();
        divider.setType("divider");
        divider.setKey("d");
        divider.setOptional(Boolean.TRUE);
        assertRejected(divider);
    }

    @Test
    void rejectsUnknownStyleBadMarksAndFormatOnNonTextOrSpeakerBlocks() {
        ChapterBlockDTO badStyle = text("a", "甲乙");
        badStyle.setStyle("h3");
        assertRejected(badStyle);

        ChapterBlockDTO outOfRange = text("b", "甲乙");
        outOfRange.setMarks(Collections.singletonList(mark(1, 3, "bold")));
        assertRejected(outOfRange);

        ChapterBlockDTO empty = text("c", "甲乙");
        empty.setMarks(Collections.singletonList(mark(1, 1, "bold")));
        assertRejected(empty);

        ChapterBlockDTO underline = text("d", "甲乙");
        underline.setMarks(Collections.singletonList(mark(0, 1, "underline")));
        assertRejected(underline);

        ChapterBlockDTO speaker = text("e", "欢迎");
        speaker.setWho("店主");
        speaker.setStyle("quote");
        assertRejected(speaker);

        ChapterBlockDTO divider = new ChapterBlockDTO();
        divider.setType("divider");
        divider.setKey("f");
        divider.setStyle("h1");
        assertRejected(divider);

        ChapterBlockDTO tooMany = text("g", "甲乙丙");
        List<ChapterBlockDTO.TextMarkDTO> marks = new ArrayList<>();
        for (int i = 0; i < 201; i++) marks.add(mark(0, 1, "bold"));
        tooMany.setMarks(marks);
        assertRejected(tooMany);
    }

    private void assertRejected(ChapterBlockDTO block) {
        assertThrows(ChapterFlowValidationException.class,
                () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(block))));
    }

    private static ChapterBlockDTO text(String key, String content) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("text");
        block.setKey(key);
        block.setContent(content);
        return block;
    }

    private static ChapterBlockDTO.TextMarkDTO mark(int from, int to, String type) {
        ChapterBlockDTO.TextMarkDTO mark = new ChapterBlockDTO.TextMarkDTO();
        mark.setFrom(from);
        mark.setTo(to);
        mark.setType(type);
        return mark;
    }

    private static ChapterDTO chapter(ChapterBlockDTO... blocks) {
        ChapterBlockDTO node = new ChapterBlockDTO();
        node.setType("node");
        node.setKey("n");
        node.setNodeIndex(0);
        List<ChapterBlockDTO> all = new ArrayList<>(Arrays.asList(blocks));
        all.add(node);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setSchemaVersion(1);
        chapter.setRequired(1);
        chapter.setNodes(Collections.singletonList(new NodeDTO()));
        chapter.setBlocks(all);
        return chapter;
    }
}
