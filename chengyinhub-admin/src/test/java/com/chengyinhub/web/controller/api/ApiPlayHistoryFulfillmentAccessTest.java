package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4 历史履约访问(规则16):普通下架(节点 status=0,行仍在)后,已完成者仍应读到自己的终章碎片;
 * 物理删除(行没了)、商家待审/驳回(草稿/违规侧)不得公开;陌生人/未参与者不泄露;
 * 复用已存节点内容,模板变化不改写历史;只读,不回收/重发/清进度。
 */
class ApiPlayHistoryFulfillmentAccessTest
{
    private static final long PLAYER = 55L;
    private static final long OTHER_MEMBER = 56L;
    private static final long TOPIC = 960001L;
    private static final long NODE_A = 7001L;
    private static final long NODE_B = 7002L;
    private static final long ACTIVITY = 501L;

    private IPlayerNodeProgressService progressService;
    private ICmsTopicNodeService nodeService;
    private ICmsTopicService topicService;
    private ICmsActivityService activityService;
    private ICmsRegistrationService registrationService;
    private com.chengyinhub.business.mapper.WxContentCallbackInboxMapper callbackInboxMapper;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp()
    {
        progressService = mock(IPlayerNodeProgressService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        callbackInboxMapper = mock(com.chengyinhub.business.mapper.WxContentCallbackInboxMapper.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "wxContentCallbackInboxMapper", callbackInboxMapper);
        when(callbackInboxMapper.countRiskyCallback(anyInt(), anyLong())).thenReturn(0);

        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic());
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(new ArrayList<>());

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
    }

    private CmsTopic topic()
    {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("主题");
        return t;
    }

    /** 普通下架节点:行仍在、status=0、主办方节点(无商家审核位)。 */
    private CmsTopicNode delistedNode(long id, String name, String fragment)
    {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setStatus(0L);
        n.setName(name);
        n.setFragmentText(fragment);
        return n;
    }

    private PlayerNodeProgress done(Long memberId, long nodeId, long checkinAt)
    {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(memberId);
        p.setTopicId(TOPIC);
        p.setActivityId(0L);
        p.setNodeId(nodeId);
        p.setStatus(1);
        p.setCheckinTime(new Date(checkinAt));
        return p;
    }

    private PlayerNodeProgress arrivedOnly(Long memberId, long nodeId, long checkinAt)
    {
        PlayerNodeProgress p = done(memberId, nodeId, checkinAt);
        p.setStatus(0);
        return p;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fragments(AjaxResult result)
    {
        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
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

    // ---------- S4 反例:普通下架 ----------

    @Test
    void delistedCompletedNodeKeepsFragmentForItsPlayer()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "下架前的碎片"));

        AjaxResult result = endingSelfPlay();

        assertEquals(Collections.singletonList(NODE_A), fragmentNodeIds(result),
                "普通下架节点的已完成碎片必须保留给本人(规则16历史履约)");
        assertEquals("下架前的碎片", fragments(result).get(0).get("text"), "复用已存节点内容");
    }

    @Test
    void delistedCompletedNodesFollowCompletionTimeAcrossActiveList()
    {
        CmsTopicNode active = new CmsTopicNode();
        active.setId(NODE_B);
        active.setTopicId(TOPIC);
        active.setStatus(1L);
        active.setName("在线站");
        active.setFragmentText("在线碎片");
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(active));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "下架碎片"));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                done(PLAYER, NODE_A, 9000L),
                done(PLAYER, NODE_B, 3000L)));

        AjaxResult result = endingSelfPlay();

        assertEquals(Arrays.asList(NODE_B, NODE_A), fragmentNodeIds(result),
                "下架站按完成时间参与排序,不因下架丢失");
    }

    @Test
    void coldRepeatReadIsStable()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "碎片"));

        AjaxResult first = endingSelfPlay();
        AjaxResult second = endingSelfPlay();

        assertEquals(fragmentNodeIds(first), fragmentNodeIds(second), "冷恢复/重复读取结果稳定");
        assertEquals(200, second.get("code"));
    }

    @Test
    void activityPathAlsoKeepsDelistedCompletion()
    {
        CmsRegistration reg = new CmsRegistration();
        reg.setMemberId(PLAYER);
        reg.setOwnerType(2L);
        reg.setOwnerId(ACTIVITY);
        reg.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(reg));
        PlayerNodeProgress activityDone = done(PLAYER, NODE_A, 1000L);
        activityDone.setActivityId(ACTIVITY);
        when(progressService.selectPlayerNodeProgressList(any()))
                .thenReturn(Collections.singletonList(activityDone));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "碎片"));

        AjaxResult result = controller.ending(ACTIVITY, null);

        assertEquals(Collections.singletonList(NODE_A), fragmentNodeIds(result),
                "活动会话的历史完成同样不因下架丢失");
    }

    // ---------- 边界:不泄露 / 不猜 / 区分删除与违规 ----------

    @Test
    void strangerCannotSeeDelistedNodeContent()
    {
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "碎片"));

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "无本人完成事实不得看到下架内容");
        verify(nodeService, never()).selectCmsTopicNodeById(anyLong());
    }

    @Test
    void physicallyDeletedNodeIsNotGuessed()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A)).thenReturn(null);

        AjaxResult result = endingSelfPlay();

        assertEquals(200, result.get("code"));
        assertTrue(fragments(result).isEmpty(), "行已删除:无可靠内容不猜,不伪造历史碎片");
    }

    @Test
    void merchantPendingNodeIsNotPubliclyGuessed()
    {
        CmsTopicNode pending = delistedNode(NODE_A, "商家待审站", "未过审内容");
        pending.setMerchantMemberId(99L);
        pending.setNodeAuditStatus(0);
        when(nodeService.selectCmsTopicNodeById(NODE_A)).thenReturn(pending);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "待审草稿不得借历史读取公开");
    }

    @Test
    void merchantRejectedNodeIsNotResurfaced()
    {
        CmsTopicNode rejected = delistedNode(NODE_A, "商家驳回站", "违规/驳回内容");
        rejected.setMerchantMemberId(99L);
        rejected.setNodeAuditStatus(2);
        when(nodeService.selectCmsTopicNodeById(NODE_A)).thenReturn(rejected);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "驳回/违规处置与普通下架区分,不借历史读取复活");
    }

    @Test
    void merchantApprovedDelistedNodeIsReadable()
    {
        CmsTopicNode approved = delistedNode(NODE_A, "商家过审站", "过审碎片");
        approved.setMerchantMemberId(99L);
        approved.setNodeAuditStatus(1);
        when(nodeService.selectCmsTopicNodeById(NODE_A)).thenReturn(approved);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));

        AjaxResult result = endingSelfPlay();

        assertEquals(Collections.singletonList(NODE_A), fragmentNodeIds(result),
                "已过审商家节点的普通下架仍是历史履约内容");
    }

    @Test
    void foreignTopicNodeCannotBePulledIn()
    {
        CmsTopicNode foreign = delistedNode(NODE_A, "别的主题站", "外来碎片");
        foreign.setTopicId(TOPIC + 999);
        when(nodeService.selectCmsTopicNodeById(NODE_A)).thenReturn(foreign);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "节点必须属于本主题,不能跨主题拼装");
    }

    @Test
    void deletedTopicStillFailsClearly()
    {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(null);

        AjaxResult result = endingSelfPlay();

        assertEquals(500, result.get("code"));
        assertEquals("主题不存在", result.get("msg"), "主题物理删除:清晰失败,不猜历史");
    }

    // ---------- 只读合同:不回收/重发/清进度 ----------

    @Test
    void endingNeverWritesProgressOrRewards()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "碎片"));

        endingSelfPlay();
        endingSelfPlay();

        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(progressService, times(2)).selectPlayerNodeProgressList(any());
    }

    @Test
    void governanceRejectedTopicBlocksEndingHistory()
    {
        CmsTopic rejected = topic();
        rejected.setStatus(2);
        rejected.setCheckStatus(2);
        rejected.setReason("违规处置");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(rejected);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "碎片"));

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "审核驳回/治理处置主题不得借历史读取复活");
        verify(nodeService, never()).selectCmsTopicNodeById(anyLong());
    }

    @Test
    void contentSecurityViolationFactBlocksEndingHistory()
    {
        CmsTopic riskyDown = topic();
        riskyDown.setUserStatus(0);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(riskyDown);
        when(callbackInboxMapper.countRiskyCallback(anyInt(), anyLong())).thenReturn(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "碎片"));

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "内容安全违规处置事实存在时不得开放历史");
    }

    @Test
    void ordinaryDelistTopicStillServesHistoryWhenNoDisposalFact()
    {
        CmsTopic plainDelist = topic();
        plainDelist.setStatus(0);
        plainDelist.setUserStatus(0);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(plainDelist);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "普通下架站", "历史碎片"));

        AjaxResult result = endingSelfPlay();

        assertEquals(Collections.singletonList(NODE_A), fragmentNodeIds(result),
                "无处置事实的普通下架仍按规则16对历史参与者可读");
    }

    @Test
    void missingDisposalFactReaderFailsClosedForEnding()
    {
        // 负控(§7-3):处置事实读取件缺失时,下架态主题的历史必须失败关闭,不得 fail-open 放行。
        CmsTopic riskUnknown = topic();
        riskUnknown.setStatus(0);
        riskUnknown.setUserStatus(0);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(riskUnknown);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                done(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "普通下架站", "历史碎片"));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "wxContentCallbackInboxMapper", null);

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "处置事实读取件缺失时历史读取必须失败关闭");
        verify(nodeService, never()).selectCmsTopicNodeById(anyLong());
    }

    @Test
    void arrivalOnlyRowsStillDoNotBecomeFragments()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(
                arrivedOnly(PLAYER, NODE_A, 1000L)));
        when(nodeService.selectCmsTopicNodeById(NODE_A))
                .thenReturn(delistedNode(NODE_A, "已下架站", "碎片"));

        AjaxResult result = endingSelfPlay();

        assertTrue(fragments(result).isEmpty(), "到店 status=0 不是完成,下架也不改这个口径");
    }
}
