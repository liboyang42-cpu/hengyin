package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F07:城市定向只露第一站与已到站的照片;自由探索不遮。 */
class StationImageVisibilityTest {

    private static CmsTopicNode node(long id, Long sortId) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setSortId(sortId);
        return n;
    }

    @Test
    void cityRouteHidesUnreachedLaterStations() {
        assertTrue(StationImageVisibility.visible(1, true, false));
        assertTrue(StationImageVisibility.visible(1, false, true));
        assertFalse(StationImageVisibility.visible(1, false, false));
        assertFalse(StationImageVisibility.visible(null, false, false), "模式缺省按城市定向,宁可多遮");
    }

    @Test
    void freeExploreNeverHides() {
        assertTrue(StationImageVisibility.visible(2, false, false));
    }

    @Test
    void firstStationIsLowestSortThenLowestId() {
        assertEquals(Long.valueOf(7L), StationImageVisibility.firstStationId(
                Arrays.asList(node(9L, 3L), node(8L, 2L), node(7L, 2L), node(5L, null))));
        assertNull(StationImageVisibility.firstStationId(null));
    }
}
