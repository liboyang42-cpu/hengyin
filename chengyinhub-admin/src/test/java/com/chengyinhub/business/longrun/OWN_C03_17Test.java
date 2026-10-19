package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.utils.DistanceCalculatorUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Real distance math and projection; no geolocation/API/DB execution evidence. */
class OWN_C03_17Test {
    private CmsTopicServiceImpl service;
    private final DistanceCalculatorUtils distance = new DistanceCalculatorUtils();
    @BeforeEach void setup() {
        service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service, "distanceCalculatorUtils", distance);
    }
    @Test void storyGameDoesNotBreakDistanceBetweenPhysicalLocations() {
        NodeDTO a = node("121.0", "31.0");
        NodeDTO game = node(null, null);
        NodeDTO b = node("121.1", "31.1");
        List<NodeDTO> all = Arrays.asList(a, game, b);
        ChapterDTO chapter = chapter(all, block(0, true), block(1, false), block(2, null));
        List<NodeDTO> physical = project(all, Collections.singletonList(chapter), true);
        assertEquals(Arrays.asList(a, b), physical);
        assertEquals(BigDecimal.valueOf(distance.calculateDistance("121.0", "31.0", "121.1", "31.1")),
                service.calculateTotalDistance(physical));
        assertTrue(service.calculateTotalDistance(physical).signum() > 0);
        assertEquals(3, all.size(), "Original allNodes must remain unchanged for other statistics");
        assertEquals(Long.valueOf(15), service.calculateTotalTime(all));
    }
    @Test void missingCoordinatePhysicalNodeStillBreaksEdgesAsBefore() {
        NodeDTO a = node("121.0", "31.0");
        NodeDTO game = node(null, null);
        NodeDTO unknownPhysical = node(null, null);
        NodeDTO b = node("121.1", "31.1");
        List<NodeDTO> all = Arrays.asList(a, game, unknownPhysical, b);
        ChapterDTO chapter = chapter(all, block(0, true), block(1, false), block(2, true), block(3, true));
        List<NodeDTO> physical = project(all, Collections.singletonList(chapter), true);
        assertEquals(Arrays.asList(a, unknownPhysical, b), physical);
        assertEquals(0, service.calculateTotalDistance(physical).compareTo(BigDecimal.ZERO));
    }
    @Test void legacyPathDoesNotFilterEvenIfBlocksWereSupplied() {
        NodeDTO a = node("121.0", "31.0");
        NodeDTO game = node(null, null);
        NodeDTO b = node("121.1", "31.1");
        List<NodeDTO> all = Arrays.asList(a, game, b);
        ChapterDTO chapter = chapter(all, block(0, true), block(1, false), block(2, true));
        assertSame(all, project(all, Collections.singletonList(chapter), false));
        assertEquals(0, service.calculateTotalDistance(all).compareTo(BigDecimal.ZERO));
    }
    @Test void noBlocksKeepsExistingListAndCoordinates() {
        List<NodeDTO> all = Arrays.asList(node("121.0", "31.0"), node("121.1", "31.1"));
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(all);
        assertSame(all, project(all, Collections.singletonList(chapter), true));
    }
    @Test void projectionPreservesAlreadyCompiledFlowOrder() {
        NodeDTO a = node("121.0", "31.0");
        NodeDTO game = node(null, null);
        NodeDTO b = node("121.1", "31.1");
        ChapterDTO chapter = chapter(Arrays.asList(a, game, b), block(2, true), block(1, false), block(0, true));
        assertEquals(Arrays.asList(b, a), project(Arrays.asList(b, game, a), Collections.singletonList(chapter), true));
    }
    @Test void explicitStoryGameAcrossChapterBoundaryIsExcluded() {
        NodeDTO a = node("121.0", "31.0");
        NodeDTO game = node(null, null);
        NodeDTO b = node("121.1", "31.1");
        ChapterDTO first = chapter(Collections.singletonList(a), block(0, true));
        ChapterDTO second = chapter(Arrays.asList(game, b), block(0, false), block(1, true));
        assertEquals(Arrays.asList(a, b), project(Arrays.asList(a, game, b), Arrays.asList(first, second), true));
    }
    private NodeDTO node(String longitude, String latitude) {
        NodeDTO node = new NodeDTO();
        node.setLongitude(longitude);
        node.setLatitude(latitude);
        node.setNodeTime(5);
        return node;
    }
    private ChapterBlockDTO block(int index, Boolean required) {
        ChapterBlockDTO block = new ChapterBlockDTO();
        block.setType("node");
        block.setNodeIndex(index);
        block.setLocationRequired(required);
        return block;
    }
    private ChapterDTO chapter(List<NodeDTO> nodes, ChapterBlockDTO... blocks) {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(nodes);
        chapter.setBlocks(Arrays.asList(blocks));
        return chapter;
    }
    private List<NodeDTO> project(List<NodeDTO> all, List<ChapterDTO> chapters, boolean v2) {
        return ReflectionTestUtils.invokeMethod(service, "nodesForDistance", all, chapters, v2);
    }
}
