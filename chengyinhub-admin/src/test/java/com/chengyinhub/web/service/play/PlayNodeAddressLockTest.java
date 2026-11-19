package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.NodeMerchantOverride;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.common.constant.PlayMode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CG-SH04-P1-3:前置关解出前，这一站的地址文字不下发;解出后照常下发。 */
class PlayNodeAddressLockTest {

    @Test
    void lockedStationHidesAddressUntilPredecessorSolved() {
        CmsTopicNode node = node();
        Map<String, Object> locked = project(node, Set.of(), PlayMode.LINEAR_ORIENTEERING);
        assertEquals(true, locked.get("locked"));
        assertNull(locked.get("address"));
        assertEquals(true, locked.get("addressHidden"));

        Map<String, Object> open = project(node, Set.of(10L), PlayMode.LINEAR_ORIENTEERING);
        assertEquals(false, open.get("locked"));
        assertEquals("第四个街角", open.get("address"));
        assertFalse(open.containsKey("addressHidden"));
    }

    @Test
    void freeExploreStoresKeepAddress() {
        Map<String, Object> view = project(node(), Set.of(), PlayMode.FREE_COLLECT);
        assertEquals("第四个街角", view.get("address"));
        assertFalse(view.containsKey("addressHidden"));
    }

    @Test
    void stationWithoutPredecessorKeepsAddress() {
        CmsTopicNode node = node();
        node.setUnlockAfterNodeId(null);
        Map<String, Object> view = project(node, Set.of(), PlayMode.LINEAR_ORIENTEERING);
        assertEquals("第四个街角", view.get("address"));
        assertTrue(!view.containsKey("addressHidden"));
    }

    private Map<String, Object> project(CmsTopicNode node, Set<Long> completed, int playMode) {
        PlayNodeQueryApplication query = new PlayNodeQueryApplication();
        INodeMerchantOverrideService overrides = mock(INodeMerchantOverrideService.class);
        when(overrides.forNode(11L)).thenReturn(NodeMerchantOverride.empty());
        ReflectionTestUtils.setField(query, "nodeMerchantOverrideService", overrides);
        ReflectionTestUtils.setField(query, "playNodeContext", new PlayNodeContext());
        PlayNodeQueryApplication.NodeProjection read = new PlayNodeQueryApplication.NodeProjection(
                7L, true, 21L, 0L, null, true, false, null, List.of(node), List.of(),
                Map.of(), Map.of(), completed, 10, Set.of(), new ArrayList<>(), null, playMode, null, false, null);
        read.playableNodeIds = new HashSet<>();
        read.puzzleHintLevelByNode = Map.of();
        read.nodeNpcCache = new java.util.HashMap<>();
        read.nodeNpcCache.put(11L, null);
        read.merchantClosedCache = new java.util.HashMap<>();
        read.merchantClosedCache.put(null, false);
        read.entitlementByChapter = new java.util.HashMap<>();
        read.perkByChapterMerchant = new java.util.HashMap<>();
        read.inStoreLoadByChapter = new java.util.HashMap<>();
        read.capacityByChapter = new java.util.HashMap<>();
        read.nodeXpCache = new java.util.HashMap<>();
        read.visitConfigByNode = new java.util.HashMap<>();
        read.visitedChapterIds = new HashSet<>();
        read.placeChapterIds = new HashSet<>();
        read.teamVisitedChapterIds = new HashSet<>();
        return query.projectNodeBeforeRuntime(read, node).view();
    }

    private static CmsTopicNode node() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(11L);
        node.setTopicId(21L);
        node.setName("终点");
        node.setAddress("第四个街角");
        node.setUnlockAfterNodeId(10L);
        node.setStatus(1L);
        return node;
    }
}
