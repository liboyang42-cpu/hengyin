package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.support.NodeAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验收 SC-CITY-PROGRESS(服务端):线性路线里后面的站在前站完成前不能提前通过;
 * 只有 status=1 的完成事实算「完成」,拍照待审(status=0)不放行下一站;配置了前置关卡的站点同理。
 * 覆盖路线准入的两处真源:NodeAccessPolicy(路线运行时)与控制器无运行时时的 isPrevDone 回退。
 * 端点层「被拒不写进度」已由 ApiPlayDelistedCompletedRetryContractTest / ApiPlaySensorResultTest 覆盖。
 */
class CityStationOrderAcceptanceTest {

    private static final Long TOPIC = 70L;
    private static final Long MEMBER = 7L;
    private static final Long ACT = 9L;

    private CmsTopicNode first;
    private CmsTopicNode second;
    private CmsTopicNode third;

    private static CmsTopicNode node(long id, long sort) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setSortId(sort);
        n.setStatus(1L);
        return n;
    }

    @BeforeEach
    void setUp() {
        first = node(1L, 1L);
        second = node(2L, 2L);
        third = node(3L, 3L);
    }

    @Test
    void linearPolicyRefusesLaterStationUntilEveryEarlierStationIsCompleted() {
        NodeAccessPolicy policy = new NodeAccessPolicy();
        List<CmsTopicNode> nodes = Arrays.asList(first, second, third);

        NodeAccessPolicy.Decision skip = policy.evaluate("LINEAR", third, nodes, new HashSet<>(List.of(1L)), null);
        assertFalse(skip.isAllowed());
        assertEquals("请先完成上一站", skip.getReason());
        assertEquals("请先完成上一站",
                policy.evaluate("LINEAR", second, nodes, Collections.emptySet(), null).getReason());
        assertTrue(policy.evaluate("LINEAR", first, nodes, Collections.emptySet(), null).isAllowed());
        assertTrue(policy.evaluate("LINEAR", third, nodes, new HashSet<>(List.of(1L, 2L)), null).isAllowed());
    }

    @Test
    void unlockAfterNodeMustBeCompletedEvenWhenSortOrderAllows() {
        NodeAccessPolicy policy = new NodeAccessPolicy();
        CmsTopicNode gated = node(4L, 0L);
        gated.setUnlockAfterNodeId(3L);
        List<CmsTopicNode> nodes = Arrays.asList(first, second, third, gated);

        NodeAccessPolicy.Decision d = policy.evaluate("LINEAR", gated, nodes, new HashSet<>(List.of(1L, 2L)), null);
        assertFalse(d.isAllowed());
        assertEquals("本关尚未解锁,先完成前置关卡", d.getReason());
        assertTrue(policy.evaluate("LINEAR", gated, nodes, new HashSet<>(List.of(3L)), null).isAllowed());
    }

    @Test
    void controllerFallbackCountsOnlyApprovedCompletionNotPendingPhotoReview() {
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        IPlayerNodeProgressService progress = mock(IPlayerNodeProgressService.class);
        ApiPlayProgressController controller = new ApiPlayProgressController();
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodes);
        PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progress);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(first, second, third));

        PlayerNodeProgress pendingPhoto = new PlayerNodeProgress();
        pendingPhoto.setNodeId(1L);
        pendingPhoto.setStatus(0);
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(pendingPhoto));
        Boolean allowed = com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "isPrevDone", MEMBER, ACT, second);
        assertFalse(allowed, "拍照待审不算完成,第二站不能提前通过");

        PlayerNodeProgress done = new PlayerNodeProgress();
        done.setNodeId(1L);
        done.setStatus(1);
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(done));
        assertTrue(PlayNodeAccessTestFixtures.<Boolean>invokeMethod(controller, "isPrevDone", MEMBER, ACT, second));
        assertFalse(PlayNodeAccessTestFixtures.<Boolean>invokeMethod(controller, "isPrevDone", MEMBER, ACT, third),
                "第三站还要求第二站完成");
    }
}
