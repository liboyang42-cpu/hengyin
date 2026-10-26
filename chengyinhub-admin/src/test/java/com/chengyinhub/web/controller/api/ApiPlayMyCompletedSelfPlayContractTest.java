package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.PlayHistoryQueryApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * R10-09:自玩(activity_id=0)已完成主题必须进入 /api/play/my-completed,与活动/探索票完成记录并列,
 * 类型与 ID 明确;未完成/重复读取/跨用户不得混入。
 */
class ApiPlayMyCompletedSelfPlayContractTest
{
    private static final long PLAYER = 77L;
    private static final long SELF_TOPIC = 990062L;
    private static final long ACTIVITY = 501L;
    private static final long ACTIVITY_TOPIC = 990063L;

    private IPlayerNodeProgressService progressService;
    private ICmsRegistrationService registrationService;
    private ICmsActivityService activityService;
    private ICmsTopicService topicService;
    private ICmsTopicNodeService nodeService;
    private IPerkLedgerService perkLedgerService;
    private ITopicRouteRuntimeService routeService;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp()
    {
        progressService = mock(IPlayerNodeProgressService.class);
        registrationService = mock(ICmsRegistrationService.class);
        activityService = mock(ICmsActivityService.class);
        topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        perkLedgerService = mock(IPerkLedgerService.class);
        routeService = mock(ITopicRouteRuntimeService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", perkLedgerService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", routeService);
        com.chengyinhub.business.service.support.CompletedRouteFactReader completedRoutes =
                mock(com.chengyinhub.business.service.support.CompletedRouteFactReader.class);
        when(completedRoutes.findCurrentCompletedRoutes(PLAYER)).thenReturn(Collections.emptyList());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "completedRouteFactReader", completedRoutes);

        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        when(routeService.sessionStatuses(any())).thenReturn(Collections.emptyMap());
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> call() {
        AjaxResult result = controller.myCompleted();
        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        return (List<Map<String, Object>>) result.get("data");
    }

    private void seedNodes(long topicId, int total) {
        List<CmsTopicNode> nodes = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            CmsTopicNode node = new CmsTopicNode();
            node.setId(1000L + i);
            node.setTopicId(topicId);
            node.setStatus(1L);
            nodes.add(node);
        }
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(nodes);
    }

    private PlayerNodeProgress row(Long activityId, Long topicId, Long nodeId, Integer status) {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(PLAYER);
        p.setActivityId(activityId);
        p.setTopicId(topicId);
        p.setNodeId(nodeId);
        p.setStatus(status);
        return p;
    }

    private CmsTopic topic(long id, String name) {
        CmsTopic t = new CmsTopic();
        t.setId(id);
        t.setName(name);
        t.setImgUrl("https://cdn/topic-" + id + ".png");
        return t;
    }

    @Test
    void selfPlayCompletedTopicAppearsWithExplicitTypeAndIds()
    {
        seedNodes(SELF_TOPIC, 2);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(topic(SELF_TOPIC, "自玩主题"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, SELF_TOPIC, 11L, 1),
                row(0L, SELF_TOPIC, 12L, 1)));

        List<Map<String, Object>> list = call();

        assertEquals(1, list.size(), "自玩两站完成必须进入完成列表");
        Map<String, Object> item = list.get(0);
        assertEquals(2, item.get("dataType"), "自玩完成对象类型必须是 2=主题");
        assertNull(item.get("activityId"), "自玩没有活动场次,不得伪造 activityId");
        assertEquals(SELF_TOPIC, ((Number) item.get("topicId")).longValue());
        assertEquals(SELF_TOPIC, ((Number) item.get("dataId")).longValue(), "统一关联 ID 必须指向主题");
        assertEquals("自玩主题", item.get("name"));
        assertEquals(2, item.get("total"));
        assertEquals(2, item.get("doneCount"));
        assertEquals(Boolean.TRUE, item.get("completed"));
    }

    @Test
    void incompleteSelfPlayTopicIsExcluded()
    {
        seedNodes(SELF_TOPIC, 2);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(topic(SELF_TOPIC, "自玩主题"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                row(0L, SELF_TOPIC, 11L, 1)));

        assertTrue(call().isEmpty(), "两站只完成一站不得进入完成列表");
    }

    @Test
    void duplicateOrRepeatedSelfPlayRowsCollapseToOneItem()
    {
        seedNodes(SELF_TOPIC, 2);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(topic(SELF_TOPIC, "自玩主题"));
        // 同一节点重复行 / 多次会话残留:必须去重且只出一条
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, SELF_TOPIC, 11L, 1),
                row(0L, SELF_TOPIC, 11L, 1),
                row(0L, SELF_TOPIC, 12L, 1)));

        List<Map<String, Object>> list = call();

        assertEquals(1, list.size(), "同主题只能一条");
        assertEquals(2, list.get(0).get("doneCount"), "重复行不得把完成数刷大");
    }

    @Test
    void selfPlayBranchRouteCompletedIsIncludedEvenWithoutLinearFullCount()
    {
        seedNodes(SELF_TOPIC, 5);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(topic(SELF_TOPIC, "分支自玩"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                row(0L, SELF_TOPIC, 11L, 1)));
        Map<String, Integer> statuses = new HashMap<>();
        statuses.put(ITopicRouteRuntimeService.sessionKey(PLAYER, SELF_TOPIC, 0L), com.chengyinhub.business.domain.TopicRouteSession.STATUS_COMPLETED);
        when(routeService.sessionStatuses(any())).thenReturn(statuses);

        List<Map<String, Object>> list = call();

        assertEquals(1, list.size(), "分支图路线 COMPLETED 必须进入完成列表");
        assertEquals(2, list.get(0).get("dataType"));
    }

    @Test
    void selfPlayBranchRouteUnfinishedIsExcludedEvenWhenNodeCountLooksFull()
    {
        seedNodes(SELF_TOPIC, 2);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(topic(SELF_TOPIC, "分支自玩"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, SELF_TOPIC, 11L, 1),
                row(0L, SELF_TOPIC, 12L, 1)));
        Map<String, Integer> statuses = new HashMap<>();
        statuses.put(ITopicRouteRuntimeService.sessionKey(PLAYER, SELF_TOPIC, 0L), com.chengyinhub.business.domain.TopicRouteSession.STATUS_RUNNING);
        when(routeService.sessionStatuses(any())).thenReturn(statuses);

        assertTrue(call().isEmpty(), "分支会话未完成不得因节点计数像满就进入");
    }

    @Test
    void activityCompletionKeepsType1AndExistingFields()
    {
        seedNodes(ACTIVITY_TOPIC, 2);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setCancelTime(new java.util.Date());
        activity.setTopicId(ACTIVITY_TOPIC);
        activity.setName("活动场次");
        activity.setImgUrl("https://cdn/act.png");
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(topicService.selectCmsTopicById(ACTIVITY_TOPIC)).thenReturn(topic(ACTIVITY_TOPIC, "活动主题"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(ACTIVITY, ACTIVITY_TOPIC, 21L, 1),
                row(ACTIVITY, ACTIVITY_TOPIC, 22L, 1)));

        List<Map<String, Object>> list = call();

        assertEquals(1, list.size());
        Map<String, Object> item = list.get(0);
        assertEquals(1, item.get("dataType"), "活动完成对象仍是 1=活动");
        assertEquals(ACTIVITY, ((Number) item.get("activityId")).longValue());
        assertEquals(ACTIVITY, ((Number) item.get("dataId")).longValue(), "统一关联 ID 指向活动");
        assertEquals("活动场次", item.get("name"));
        assertEquals(2, item.get("doneCount"));
        assertEquals(list, controller.myCompleted(false).get("data"), "default preserves cancelled completed history");
    }

    @Test
    void explorePassCompletionStillReturnedWithoutNodeRows()
    {
        seedNodes(ACTIVITY_TOPIC, 3);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(ACTIVITY_TOPIC);
        activity.setName("探索票活动");
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(topicService.selectCmsTopicById(ACTIVITY_TOPIC)).thenReturn(topic(ACTIVITY_TOPIC, "探索票主题"));
        CmsRegistration pass = new CmsRegistration();
        pass.setId(700L);
        pass.setMemberId(PLAYER); pass.setOwnerType(2L); pass.setOwnerId(ACTIVITY);
        pass.setPurchaseKind(com.chengyinhub.common.constant.PurchaseKind.EXPLORE_PASS);
        pass.setRegistrationStatus(2); pass.setPaymentStatus(2);
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.singletonList(pass));
        when(perkLedgerService.isExploreCompleted(700L, ACTIVITY_TOPIC)).thenReturn(true);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(new ArrayList<>());

        List<Map<String, Object>> list = call();

        assertEquals(1, list.size(), "探索票核销完成口径必须保留");
        assertEquals(0, list.get(0).get("doneCount"));
    }

    @Test
    void selfPlayTopicMissingFromCatalogIsSkippedWithoutFabricatingName()
    {
        seedNodes(SELF_TOPIC, 2);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(null);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, SELF_TOPIC, 11L, 1),
                row(0L, SELF_TOPIC, 12L, 1)));

        assertTrue(call().isEmpty(), "主题不可读时不得伪造名称/封面进入列表");
    }

    @Test
    void anotherMembersFactsCannotAppearThroughCallerScopedProbe()
    {
        seedNodes(SELF_TOPIC, 2);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(topic(SELF_TOPIC, "自玩主题"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, SELF_TOPIC, 11L, 1),
                row(0L, SELF_TOPIC, 12L, 1)));

        call();

        // 只允许按当前登录会员查询:探针必须带 memberId,不允许全表/他人记录
        org.mockito.ArgumentCaptor<PlayerNodeProgress> probe =
                org.mockito.ArgumentCaptor.forClass(PlayerNodeProgress.class);
        org.mockito.Mockito.verify(progressService).selectPlayerNodeProgressList(probe.capture());
        assertEquals(PLAYER, probe.getValue().getMemberId());
    }

    @Test
    void notLoggedInIsRejected()
    {
        doReturn(0L).when(controller).getAppUserId();
        AjaxResult result = controller.myCompleted();
        assertFalse(Integer.valueOf(200).equals(result.get("code")));
    }

    @Test
    void arrivedOnlyStatusZeroSelfPlayTopicIsNotCompleted()
    {
        seedNodes(SELF_TOPIC, 2);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(topic(SELF_TOPIC, "自玩主题"));
        // mode2 自由探索:扫码到店只建 status=0 凭证行,游戏/核销完成才 0→1。
        // 仅到店行凑满节点数不是完成事实(与 /nodes completedNodeIds、route completed() 同口径)。
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, SELF_TOPIC, 11L, 0),
                row(0L, SELF_TOPIC, 12L, 0)));

        assertTrue(call().isEmpty(), "仅到店(status=0)行不得计成自玩完成主题");
    }

    @Test
    void arrivedOnlyStatusZeroActivityIsNotCompleted()
    {
        seedNodes(ACTIVITY_TOPIC, 2);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(ACTIVITY_TOPIC);
        activity.setName("活动场次");
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(topicService.selectCmsTopicById(ACTIVITY_TOPIC)).thenReturn(topic(ACTIVITY_TOPIC, "活动主题"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(ACTIVITY, ACTIVITY_TOPIC, 21L, 0),
                row(ACTIVITY, ACTIVITY_TOPIC, 22L, 0)));

        assertTrue(call().isEmpty(), "活动会话的仅到店行同样不得计成完成");
    }

    @Test
    void statusZeroRowDoesNotPadCompletionCount()
    {
        seedNodes(SELF_TOPIC, 2);
        when(topicService.selectCmsTopicById(SELF_TOPIC)).thenReturn(topic(SELF_TOPIC, "自玩主题"));
        // 一站真完成(status=1)+一站仅到店(status=0):完成数只能是 1,不得凑满 2
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, SELF_TOPIC, 11L, 1),
                row(0L, SELF_TOPIC, 12L, 0)));

        assertTrue(call().isEmpty(), "status=0 行不得把完成数刷满");
    }

    @Test
    void sameTopicActivityAndSelfPlayEntriesBothPreservedForPicker()
    {
        long sharedTopic = 990070L;
        seedNodes(sharedTopic, 2);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(sharedTopic);
        activity.setName("同主题活动");
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(topicService.selectCmsTopicById(sharedTopic)).thenReturn(topic(sharedTopic, "同主题"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(ACTIVITY, sharedTopic, 21L, 1),
                row(ACTIVITY, sharedTopic, 22L, 1),
                row(0L, sharedTopic, 11L, 1),
                row(0L, sharedTopic, 12L, 1)));

        List<Map<String, Object>> list = call();

        // F2:活动对象与自玩对象都要保留(发帖关联需要各自 dataId),不机械删活动
        assertEquals(2, list.size(), "同主题活动+自玩必须并列出两条真实对象");
        Map<String, Object> activityItem = null;
        Map<String, Object> selfPlayItem = null;
        for (Map<String, Object> item : list) {
            if (Integer.valueOf(1).equals(item.get("dataType"))) activityItem = item;
            if (Integer.valueOf(2).equals(item.get("dataType"))) selfPlayItem = item;
        }
        assertTrue(activityItem != null && selfPlayItem != null, "两条对象类型必须分别为 1/2");
        assertEquals(ACTIVITY, ((Number) activityItem.get("dataId")).longValue());
        assertEquals(ACTIVITY, ((Number) activityItem.get("activityId")).longValue());
        assertEquals(sharedTopic, ((Number) selfPlayItem.get("dataId")).longValue());
        assertNull(selfPlayItem.get("activityId"));
    }
    @Test
    void publicReferencePurposePreservesDefaultHistoryAndErrors()
    {
        com.chengyinhub.business.service.ICommunityPostService references =
                mock(com.chengyinhub.business.service.ICommunityPostService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "communityPostService", references);
        // The boolean HTTP adapter now delegates to the application; stub its original-history seam.
        PlayHistoryQueryApplication historyReader = spy((PlayHistoryQueryApplication)
                PlayNodeAccessTestFixtures.owner(controller, PlayHistoryQueryApplication.class));
        ReflectionTestUtils.setField(controller, "playHistoryQueryApplication", historyReader);
        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Object> cancelled = new LinkedHashMap<>();
        cancelled.put("dataType", 1); cancelled.put("dataId", 63L); cancelled.put("completed", true);
        history.add(cancelled);
        AjaxResult original = AjaxResult.success(history);
        doReturn(original).when(historyReader).myCompleted(PLAYER);
        org.junit.jupiter.api.Assertions.assertSame(original, controller.myCompleted(false));
        org.mockito.Mockito.verify(references, org.mockito.Mockito.never()).filterPublicCompletedReferences(anyLong(), any());
        when(references.filterPublicCompletedReferences(PLAYER, history)).thenReturn(Collections.emptyList());
        AjaxResult publicResult = controller.myCompleted(true);
        assertTrue(publicResult.isSuccess());
        assertEquals(Collections.emptyList(), publicResult.get("data"));
        assertEquals(1, history.size(), "filtering must not erase historical rows");
        AjaxResult failure = AjaxResult.error("please login");
        doReturn(failure).when(historyReader).myCompleted(PLAYER);
        org.junit.jupiter.api.Assertions.assertSame(failure, controller.myCompleted(true));
        org.mockito.Mockito.verify(references, org.mockito.Mockito.times(1)).filterPublicCompletedReferences(anyLong(), any());
    }

}
