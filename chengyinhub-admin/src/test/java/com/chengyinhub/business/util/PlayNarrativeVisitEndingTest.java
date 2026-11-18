package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopicNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FE-STORY 5.4 / T-B7:自由探索结局按到访驱动;部分到访时开场白为空、只回已到访碎片。 */
class PlayNarrativeVisitEndingTest {

    private static CmsTopicNode place(long id, String name) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setName(name);
        n.setFragmentText(name + "的碎片");
        return n;
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialVisitReturnsVisitedFragmentsWithoutOpener() {
        Map<String, Object> ending = PlayNarrativeSupport.buildVisitEnding(
                Arrays.asList(place(4L, "示例站点丁"), place(2L, "示例站点乙")), 4);
        assertEquals("", ending.get("opener"));
        assertEquals(2, ending.get("visitedCount"));
        assertEquals(4, ending.get("total"));
        assertFalse((Boolean) ending.get("complete"));
        List<Map<String, Object>> fragments = (List<Map<String, Object>>) ending.get("fragments");
        assertEquals("示例站点丁的碎片", fragments.get(0).get("text"), "按到访先后,不按地点顺序");
    }

    @Test
    void allVisitedInAnyOrderUnlocksOpener() {
        Map<String, Object> ending = PlayNarrativeSupport.buildVisitEnding(
                Arrays.asList(place(3L, "示例站点丙"), place(1L, "示例站点甲")), 2);
        assertTrue((Boolean) ending.get("complete"));
        String opener = (String) ending.get("opener");
        assertTrue(opener.contains("示例站点丙") && opener.contains("示例站点甲"));
    }

    @Test
    void noVisitIsEmpty() {
        Map<String, Object> ending = PlayNarrativeSupport.buildVisitEnding(Collections.<CmsTopicNode>emptyList(), 3);
        assertEquals("", ending.get("opener"));
        assertEquals(0, ending.get("visitedCount"));
    }
}
