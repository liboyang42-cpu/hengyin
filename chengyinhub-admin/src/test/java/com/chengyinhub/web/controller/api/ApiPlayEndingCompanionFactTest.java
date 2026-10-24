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
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10-09 收尾:终章结局信 /ending 与途中台词 /companionLine 必须以正式完成事实(status==1)
 * 表达完成——到店/凭证 status=0 行是独立事实,不得拼进「按完成顺序」的碎片,也不得推进完成槽位;
 * 同时:碎片只取本人、按完成时间排序、按节点去重;已有参与者(含通行证已过期)可读,
 * 未参与者看不到任何未获得碎片;未知主题清晰失败。
 */
class ApiPlayEndingCompanionFactTest
{
    private static final long PLAYER = 55L;
    private static final long OTHER_MEMBER = 56L;
    private static final long TOPIC = 960001L;
    private static final long NODE_A = 7001L;
    private static final long NODE_B = 7002L;
    private static final long FOREIGN_NODE = 7999L;
    private static final long ACTIVITY = 501L;

    private IPlayerNodeProgressService progressService;
    private ICmsTopicNodeService nodeService;
    private ICmsTopicService topicService;
    private ICmsActivityService activityService;
    private ICmsRegistrationService registrationService;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp()
    {
        progressService = mock(IPlayerNodeProgressService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "npcProfileMapper",
                mock(com.chengyinhub.business.mapper.NpcProfileMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "npcLineCacheMapper",
                mock(com.chengyinhub.business.mapper.NpcLineCacheMapper.class));

        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(
                node(NODE_A, 1L, "第一站", "碎片A"),
                node(NODE_B, 2L, "第二站", "碎片B")));
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic(TOPIC, "自由定向主题"));
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
    }

    private CmsTopicNode node(long id, long sortId, String name, String fragmentText)
    {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setSortId(sortId);
        n.setStatus(1L);
        n.setName(name);
        n.setFragmentText(fragmentText);
        return n;
    }

    private CmsTopic topic(long id, String name)
    {
        CmsTopic t = new CmsTopic();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private PlayerNodeProgress row(long activityId, long topicId, long nodeId, Integer status, long checkinAt)
    {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(PLAYER);
        p.setActivityId(activityId);
        p.setTopicId(topicId);
        p.setNodeId(nodeId);
        p.setStatus(status);
        p.setCheckinTime(checkinAt > 0 ? new Date(checkinAt) : null);
        return p;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fragments(AjaxResult result)
    {
        return (List<Map<String, Object>>) ((Map<String, Object>) result.get("data")).get("fragments");
    }

    private List<Long> fragmentNodeIds(AjaxResult result)
    {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> f : fragments(result)) ids.add(((Number) f.get("nodeId")).longValue());
        return ids;
    }

    private AjaxResult endingSelfPlay()
    {
        return controller.ending(null, TOPIC);
    }

    // ---------- ending:完成事实 ----------

    @Test
    void arrivedOnlyRowsNeverBecomeFragments()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, TOPIC, NODE_A, 0, 1000L),
                row(0L, TOPIC, NODE_B, 0, 2000L)));

        AjaxResult result = endingSelfPlay();

        assertEquals(200, result.get("code"));
        assertTrue(fragments(result).isEmpty(), "仅到店(status=0)不得拼进按完成顺序的碎片");
    }

    @Test
    void onlyCompletedNodesBecomeFragmentsInCheckinOrder()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, TOPIC, NODE_A, 1, 5000L),
                row(0L, TOPIC, NODE_B, 0, 1000L)));

        AjaxResult result = endingSelfPlay();

        assertEquals(200, result.get("code"));
        assertEquals(Collections.singletonList(NODE_A), fragmentNodeIds(result),
                "到达行不排除会把未玩站点拼进结局信");
    }

    @Test
    void fragmentsFollowCompletionTimeNotArrivalTime()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, TOPIC, NODE_A, 1, 9000L),
                row(0L, TOPIC, NODE_B, 1, 3000L)));

        AjaxResult result = endingSelfPlay();

        assertEquals(Arrays.asList(NODE_B, NODE_A), fragmentNodeIds(result), "必须按完成时间排序");
    }

    @Test
    void duplicateRowsCollapseToOneFragment()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(0L, TOPIC, NODE_A, 1, 1000L),
                row(0L, TOPIC, NODE_A, 1, 1000L),
                row(0L, TOPIC, NODE_B, 1, 2000L)));

        AjaxResult result = endingSelfPlay();

        assertEquals(Arrays.asList(NODE_A, NODE_B), fragmentNodeIds(result), "同节点重复行只出一条碎片");
    }

    @Test
    void activityArrivalOnlyRowIsNotAFragment()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                row(ACTIVITY, TOPIC, NODE_A, 0, 1000L)));

        AjaxResult result = controller.ending(ACTIVITY, null);

        assertEquals(200, result.get("code"));
        assertTrue(fragments(result).isEmpty(), "活动会话的到店行同样不是完成碎片");
    }

    @Test
    void foreignTopicRowsNeverAppearInFragments()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                row(0L, TOPIC, FOREIGN_NODE, 1, 1000L)));

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "不属于本主题的节点不得进入碎片");
    }

    // ---------- ending:探针与访问合同 ----------

    @Test
    void unknownTopicFailsClearly()
    {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(null);

        AjaxResult result = endingSelfPlay();

        assertEquals(500, result.get("code"));
        assertEquals("主题不存在", result.get("msg"));
    }

    @Test
    void selfPlayProbeIsScopedToCallerAndTopic()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                row(0L, TOPIC, NODE_A, 1, 1000L)));

        endingSelfPlay();

        ArgumentCaptor<PlayerNodeProgress> probe = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).selectPlayerNodeProgressList(probe.capture());
        assertEquals(PLAYER, probe.getValue().getMemberId(), "碎片只能按当前登录会员查询");
        assertEquals(Long.valueOf(0L), probe.getValue().getActivityId());
        assertEquals(TOPIC, probe.getValue().getTopicId(), "自玩探针必须按主题收窄,不能跨主题混读");
    }

    @Test
    void strangerGetsNoFragments()
    {
        // 无报名/通行证、无任何进度:即便主题节点存在,也不得看到任何碎片
        AjaxResult result = endingSelfPlay();

        assertEquals(200, result.get("code"));
        assertTrue(fragments(result).isEmpty());
    }

    @Test
    void expiredPassFinisherStillReadsOwnFragments()
    {
        CmsRegistration expiredPass = new CmsRegistration();
        expiredPass.setMemberId(PLAYER);
        expiredPass.setOwnerType(1L);
        expiredPass.setOwnerId(TOPIC);
        expiredPass.setRegistrationStatus(2);
        expiredPass.setExpiresAt(new Date(System.currentTimeMillis() - 86400000L));
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(expiredPass));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                row(0L, TOPIC, NODE_A, 1, 1000L)));

        AjaxResult result = endingSelfPlay();

        assertEquals(Collections.singletonList(NODE_A), fragmentNodeIds(result),
                "通行证过期属于历史履约,不能挡已有完成者看自己的碎片");
    }

    // ---------- companionLine:完成槽位 ----------

    @SuppressWarnings("unchecked")
    private String companionSlot(AjaxResult result)
    {
        return (String) ((Map<String, Object>) result.get("data")).get("slot");
    }

    @Test
    void companionLineArrivalOnlyDoesNotAdvanceCompletionSlot()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                row(0L, TOPIC, NODE_A, 0, 1000L)));

        AjaxResult result = controller.companionLine(null, TOPIC);

        assertEquals(200, result.get("code"));
        assertEquals("explore_first", companionSlot(result),
                "仅到店(status=0)不得把途中台词推进到完成槽位");
    }

    @Test
    void companionLineCompletedNodeAdvancesCompletionSlot()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                row(0L, TOPIC, NODE_A, 1, 1000L)));

        AjaxResult result = controller.companionLine(null, TOPIC);

        assertEquals("explore_middle", companionSlot(result), "真完成一站(total=2)应进入 middle 档");
    }
}
