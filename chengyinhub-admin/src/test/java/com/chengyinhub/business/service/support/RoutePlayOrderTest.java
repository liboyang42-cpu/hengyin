package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlayOrderTest {
    private static CmsTopicNode node(long id, Long chapter, long sort) {
        CmsTopicNode n = new CmsTopicNode(); n.setId(id); n.setTopicId(1L); n.setChapterId(chapter); n.setSortId(sort); return n;
    }
    private static CmsTopicChapter chapter(long id, long sort) {
        CmsTopicChapter c = new CmsTopicChapter(); c.setId(id); c.setTopicId(1L); c.setSortId(sort); return c;
    }
    private static List<Long> ids(List<CmsTopicNode> nodes) {
        List<Long> out = new ArrayList<>(); for (CmsTopicNode n : nodes) out.add(n.getId()); return out;
    }

    private final List<CmsTopicChapter> chapters = Arrays.asList(chapter(503L, 4L), chapter(504L, 3L));
    private final List<CmsTopicNode> nodes = Arrays.asList(node(28L, 503L, 12L), node(31L, 503L, 10L), node(32L, 503L, 11L),
            node(27L, 504L, 20L), node(29L, 504L, 21L), node(30L, 504L, 22L));

    @Test
    void chapterSortThenStationSortThenId() {
        assertEquals(Arrays.asList(27L, 29L, 30L, 31L, 32L, 28L), ids(RoutePlayOrder.sort(nodes, chapters)));
    }

    @Test
    void withoutChaptersFallsBackToStationSort() {
        assertEquals(Arrays.asList(31L, 32L, 28L, 27L, 29L, 30L), ids(RoutePlayOrder.sort(nodes, null)));
    }

    @Test
    void linearAccessFollowsPlayOrderAcrossChapters() {
        List<CmsTopicNode> ordered = RoutePlayOrder.sort(nodes, chapters);
        NodeAccessPolicy policy = new NodeAccessPolicy();
        CmsTopicNode moved = ordered.get(5), next = ordered.get(1);
        assertTrue(policy.evaluate("LINEAR", next, ordered, new HashSet<>(List.of(27L)), null).isAllowed());
        assertFalse(policy.evaluate("LINEAR", moved, ordered, new HashSet<>(List.of(27L)), null).isAllowed());
        assertTrue(policy.evaluate("LINEAR", moved, ordered, new HashSet<>(List.of(27L, 29L, 30L, 31L, 32L)), null).isAllowed());
    }
}
