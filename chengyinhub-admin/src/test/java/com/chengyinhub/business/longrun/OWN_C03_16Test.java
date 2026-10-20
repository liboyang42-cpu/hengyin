package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** NOT_RUN. Actual first-address projection, independently stated chapter/block order fixtures. */
class OWN_C03_16Test {
    @Test void reorderedBlocksChooseActualFirstLocation() {
        ChapterDTO chapter = chapter("A", "B");
        chapter.setBlocks(Arrays.asList(block(1, null), block(0, null)));
        assertEquals("B", address(chapter));
    }
    @Test void storyGameAddressNeverBecomesCollectionAddress() {
        ChapterDTO chapter = chapter("story address", "physical address");
        chapter.setBlocks(Arrays.asList(block(0, false), block(1, true)));
        assertEquals("physical address", address(chapter));
    }
    @Test void openingIsSkippedBeforePickingNextChapterLocation() {
        ChapterDTO opening = chapter("opening address");
        opening.setOpening(true);
        assertEquals("route address", address(opening, chapter("route address")));
    }
    @Test void blankFirstBlockAddressUsesNextPhysicalBlock() {
        ChapterDTO chapter = chapter("A", "  ", "C");
        chapter.setBlocks(Arrays.asList(block(1, true), block(2, null), block(0, true)));
        assertEquals("C", address(chapter));
    }
    @Test void legacyWithoutBlocksKeepsNodeArrayOrder() {
        assertEquals("A", address(chapter("A", "B")));
    }
    @Test void storyOnlyChapterDoesNotFallbackToRawNodeAddress() {
        ChapterDTO story = chapter("story address");
        story.setBlocks(Collections.singletonList(block(0, false)));
        assertEquals("", address(story));
    }
    private ChapterDTO chapter(String... addresses) {
        ChapterDTO chapter = new ChapterDTO();
        java.util.List<NodeDTO> nodes = new java.util.ArrayList<NodeDTO>();
        for (String address : addresses) {
            NodeDTO node = new NodeDTO();
            node.setAddress(address);
            nodes.add(node);
        }
        chapter.setNodes(nodes);
        return chapter;
    }
    private ChapterBlockDTO block(int index, Boolean locationRequired) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("node");
        block.setNodeIndex(index);
        block.setLocationRequired(locationRequired);
        return block;
    }
    private String address(ChapterDTO... chapters) {
        return ReflectionTestUtils.invokeMethod(new CmsTopicServiceImpl(), "firstLocationAddress", Arrays.asList(chapters));
    }
}
