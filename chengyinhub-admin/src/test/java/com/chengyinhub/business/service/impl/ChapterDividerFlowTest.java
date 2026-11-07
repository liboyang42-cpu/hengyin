package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ChapterDividerFlowTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void dividerRoundTripsWithoutBecomingTextOrAProgressNode() throws Exception {
        var flow = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(divider())));
        var compiled = flow.getChapters().get(0);
        assertEquals("已获得的正文", compiled.getDescription());
        String stored = compiled.materializeBlocks(Collections.singletonList(91L));
        var rows = json.readTree(stored);
        assertEquals(json.readTree("{\"type\":\"divider\",\"key\":\"section-break\"}"), rows.get(1));
        assertEquals(91L, rows.get(2).path("nodeId").asLong());
        ChapterBlockDTO reopened = json.treeToValue(rows.get(1), ChapterBlockDTO.class);
        String savedAgain = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(reopened)))
                .getChapters().get(0).materializeBlocks(Collections.singletonList(91L));
        assertEquals(rows.get(1), json.readTree(savedAgain).get(1));
        assertEquals(rows.get(2).path("nodeId"), json.readTree(savedAgain).get(2).path("nodeId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dividerPassesTheExistingEarnedContentBoundaryWithoutRevealingLaterText() throws Exception {
        String stored = ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(divider())))
                .getChapters().get(0).materializeBlocks(Collections.singletonList(91L));
        List<Object> blocks = json.readValue(stored, List.class);
        List<Object> before = ChapterBlockReadSupport.projectForPlayer(blocks, Collections.emptySet());
        assertEquals(3, before.size());
        assertEquals("divider", ((Map<String, Object>) before.get(1)).get("type"));
        assertFalse(before.toString().contains("尚未获得的正文"));
        assertEquals(blocks, ChapterBlockReadSupport.projectForPlayer(blocks, Collections.singleton(91L)));
    }

    @Test
    void dividerCannotHideNodeBindingsNarrativeConditionsOrMediaPayload() {
        List<Consumer<ChapterBlockDTO>> payloads = Arrays.asList(
                b -> b.setNodeIndex(0), b -> b.setContent("不可丢失的正文"),
                b -> b.setUrl("https://example.test/image.jpg"), b -> b.setBeat("outcome"),
                b -> b.setWhen(json.createObjectNode().put("op", "HAS_TAG").put("value", "choice.a")),
                b -> b.setImages(Collections.emptyList()), b -> b.setThoughtKey("secret"));
        for (Consumer<ChapterBlockDTO> payload : payloads) {
            ChapterBlockDTO block = divider();
            payload.accept(block);
            assertThrows(ChapterFlowValidationException.class,
                    () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter(block))));
        }
    }

    private static ChapterBlockDTO divider() {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("divider"); block.setKey("section-break");
        return block;
    }

    private static ChapterDTO chapter(ChapterBlockDTO divider) {
        ChapterBlockDTO before = new ChapterBlockDTO(); before.setType("text"); before.setContent("已获得的正文");
        ChapterBlockDTO node = new ChapterBlockDTO(); node.setType("node"); node.setNodeIndex(0);
        ChapterBlockDTO after = new ChapterBlockDTO(); after.setType("text"); after.setContent("尚未获得的正文");
        ChapterDTO chapter = new ChapterDTO(); chapter.setSchemaVersion(1); chapter.setRequired(1);
        chapter.setNodes(Collections.singletonList(new NodeDTO()));
        chapter.setBlocks(Arrays.asList(before, divider, node, after));
        return chapter;
    }
}
