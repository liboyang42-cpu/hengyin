package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * 规则16:/api/play/nodes 把「当前可玩集合」与「本人已正式完成的历史展示」分开消费——
 * historyNodes 只读回看普通下架的已完成站;total/doneCount/可玩 nodes 分母不变,新玩家看不到;
 * 治理处置/违规(审核驳回或内容安全处置事实)不借历史读取复活;下架节点写端不得再产生新完成。
 */
class ApiPlayHistoryNodesContractTest
{
    private static final long MEMBER = 71L;
    private static final long OTHER = 99L;
    private static final long ACTIVITY = 72L;
    private static final long TOPIC = 73L;
    private static final long ACTIVE_NODE = 201L;
    private static final long DELISTED_DONE = 202L;
    private static final long DELISTED_ARRIVAL = 203L;

    private ICmsActivityService activityService;
    private ICmsRegistrationService registrationService;
    private ICmsTopicService topicService;
    private ICmsTopicNodeService nodeService;
    private IPlayerNodeProgressService progressService;
    private WxContentCallbackInboxMapper callbackInboxMapper;
    private ApiPlayProgressController controller;
    private CmsTopic topic;
    private List<PlayerNodeProgress> progressRows;

    @BeforeEach
    void setUp()
    {
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        callbackInboxMapper = mock(WxContentCallbackInboxMapper.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(MEMBER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        var fixtureHintSettings = mock(ICmsPointsSettingService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", mock(ICmsMemberTemplateService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", mock(ITopicRouteRuntimeService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "wxContentCallbackInboxMapper", callbackInboxMapper);
        when(callbackInboxMapper.countRiskyCallback(anyInt(), anyLong())).thenReturn(0);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsRegistration registration = new CmsRegistration();
        registration.setMemberId(MEMBER);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY);
        registration.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration));

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(1);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        // O-08:未报名者看节点要求主题对其公开可见;本夹具的主题是公开在架主题。
        when(topicService.selectCmsTopicDetailById(eq(TOPIC), any())).thenReturn(topic);

        CmsTopicNode active = node(ACTIVE_NODE, "在线站", 1L, "在线碎片");
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(active));
        when(nodeService.selectCmsTopicNodeById(DELISTED_DONE))
                .thenReturn(node(DELISTED_DONE, "已下架完成站", 0L, "下架前的碎片"));
        when(nodeService.selectCmsTopicNodeById(DELISTED_ARRIVAL))
                .thenReturn(node(DELISTED_ARRIVAL, "仅到店站", 0L, "不该出现"));

        progressRows = new ArrayList<>(Arrays.asList(
                done(MEMBER, DELISTED_DONE, 1000L),
                arrival(MEMBER, DELISTED_ARRIVAL, 2000L),
                done(MEMBER, ACTIVE_NODE, 3000L)));
        when(progressService.selectPlayerNodeProgressList(any())).thenAnswer(inv -> progressRows);
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    private CmsTopicNode node(long id, String name, Long status, String fragment)
    {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setStatus(status);
        n.setSortId(id);
        n.setName(name);
        n.setDescription("描述-" + name);
        n.setFragmentText(fragment);
        return n;
    }

    private PlayerNodeProgress done(long memberId, long nodeId, long checkinAt)
    {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(memberId);
        p.setTopicId(TOPIC);
        p.setActivityId(ACTIVITY);
        p.setNodeId(nodeId);
        p.setStatus(1);
        p.setCheckinTime(new Date(checkinAt));
        return p;
    }

    private PlayerNodeProgress arrival(long memberId, long nodeId, long checkinAt)
    {
        PlayerNodeProgress p = done(memberId, nodeId, checkinAt);
        p.setStatus(0);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodesData()
    {
        AjaxResult result = controller.nodes(ACTIVITY, null);
        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        return (Map<String, Object>) result.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> data, String key)
    {
        return (List<Map<String, Object>>) data.get(key);
    }

    @Test
    void delistedCompletedNodeGoesToHistoryOnlyAndDenominatorsStayActiveOnly()
    {
        Map<String, Object> data = nodesData();

        List<Map<String, Object>> playable = list(data, "nodes");
        assertEquals(1, playable.size(), "可玩集合只含在线节点");
        assertEquals(Long.valueOf(ACTIVE_NODE), ((Number) playable.get(0).get("nodeId")).longValue());
        assertEquals(1, data.get("total"), "分母保持当前可玩集合大小");

        List<Map<String, Object>> history = list(data, "historyNodes");
        assertEquals(1, history.size(), "仅普通下架的已完成站进入历史展示");
        Map<String, Object> h = history.get(0);
        assertEquals(Long.valueOf(DELISTED_DONE), ((Number) h.get("nodeId")).longValue());
        assertEquals(Boolean.TRUE, h.get("done"));
        assertEquals(Boolean.TRUE, h.get("history"));
        assertEquals("下架前的碎片", h.get("fragmentText"));
        assertFalse(h.containsKey("validationMethod"), "历史条目不得带可写玩法字段");
        assertFalse(h.containsKey("question"), "历史条目不得带题面");
        assertFalse(h.containsKey("checkinCode"), "历史条目不得带打卡码");
        assertFalse(h.containsKey("locked"), "历史条目不是未解锁状态");
    }

    @Test
    void arrivalOnlyDelistedNodeIsNotHistory()
    {
        Map<String, Object> data = nodesData();
        List<Map<String, Object>> history = list(data, "historyNodes");
        for (Map<String, Object> h : history)
        {
            assertNotEquals(Long.valueOf(DELISTED_ARRIVAL), ((Number) h.get("nodeId")).longValue(),
                    "仅到店 status=0 不是正式完成,不得进历史");
        }
        assertEquals(1, history.size());
    }

    @Test
    void strangerGetsNoHistory()
    {
        progressRows.clear();
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> data = nodesData();

        assertTrue(list(data, "historyNodes").isEmpty(), "无本人完成事实不得看到任何历史内容");
        verify(nodeService, never()).selectCmsTopicNodeById(DELISTED_DONE);
    }

    @Test
    void crossTopicNodeIsNeverPulledIn()
    {
        CmsTopicNode foreign = node(DELISTED_DONE, "别的主题站", 0L, "外来碎片");
        foreign.setTopicId(TOPIC + 999);
        when(nodeService.selectCmsTopicNodeById(DELISTED_DONE)).thenReturn(foreign);

        Map<String, Object> data = nodesData();

        assertTrue(list(data, "historyNodes").isEmpty(), "跨主题节点不得进入历史");
    }

    @Test
    void governanceRejectedTopicBlocksHistory()
    {
        topic.setStatus(2);
        topic.setCheckStatus(2);

        Map<String, Object> data = nodesData();

        assertTrue(list(data, "historyNodes").isEmpty(), "审核驳回/治理处置主题不得借历史读取复活");
        verify(nodeService, never()).selectCmsTopicNodeById(DELISTED_DONE);
    }

    @Test
    void contentSecurityViolationFactBlocksHistory()
    {
        topic.setUserStatus(0);
        when(callbackInboxMapper.countRiskyCallback(1, TOPIC)).thenReturn(1);

        Map<String, Object> data = nodesData();

        assertTrue(list(data, "historyNodes").isEmpty(), "内容安全违规处置事实存在时历史读取失败关闭");
    }

    @Test
    void restoredTopicAfterViolationIsReadableAgain()
    {
        topic.setUserStatus(1);
        when(callbackInboxMapper.countRiskyCallback(anyInt(), anyLong())).thenReturn(1);

        Map<String, Object> data = nodesData();

        assertEquals(1, list(data, "historyNodes").size(),
                "处置已恢复(user_status=1)后历史读取随之恢复,不永久封禁");
    }

    @Test
    void callbackFactQueryFailureFailsClosed()
    {
        topic.setUserStatus(0);
        when(callbackInboxMapper.countRiskyCallback(anyInt(), anyLong()))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> data = nodesData();

        assertTrue(list(data, "historyNodes").isEmpty(), "处置事实读不到时不得开放可能违规内容");
    }

    @Test
    void missingDisposalFactReaderFailsClosed()
    {
        // 负控(§7-3):运行态处置事实读取件缺失(未注入/装配失败)属于「读不到处置事实」,
        // 旧行为对 mapper==null 是 fail-open(下架主题的历史照放),现在必须失败关闭。
        topic.setUserStatus(0);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "wxContentCallbackInboxMapper", null);

        Map<String, Object> data = nodesData();

        assertTrue(list(data, "historyNodes").isEmpty(),
                "处置事实读取件缺失时不得放行历史(失败关闭)");
        verify(nodeService, never()).selectCmsTopicNodeById(DELISTED_DONE);
    }

    @Test
    void merchantPendingOrRejectedDelistedNodeIsNotHistory()
    {
        CmsTopicNode pending = node(DELISTED_DONE, "待审站", 0L, "草稿");
        pending.setMerchantMemberId(OTHER);
        pending.setNodeAuditStatus(0);
        when(nodeService.selectCmsTopicNodeById(DELISTED_DONE)).thenReturn(pending);

        assertTrue(list(nodesData(), "historyNodes").isEmpty(), "商家待审不得公开");

        CmsTopicNode rejected = node(DELISTED_DONE, "驳回站", 0L, "驳回内容");
        rejected.setMerchantMemberId(OTHER);
        rejected.setNodeAuditStatus(2);
        when(nodeService.selectCmsTopicNodeById(DELISTED_DONE)).thenReturn(rejected);

        assertTrue(list(nodesData(), "historyNodes").isEmpty(), "商家驳回/违规侧不得公开");
    }

    @Test
    void coldRepeatReadReturnsSameHistory()
    {
        List<Map<String, Object>> first = list(nodesData(), "historyNodes");
        List<Map<String, Object>> second = list(nodesData(), "historyNodes");

        assertEquals(first.size(), second.size());
        assertEquals(((Number) first.get(0).get("nodeId")).longValue(),
                ((Number) second.get(0).get("nodeId")).longValue());
    }

    // ---------- 写端:下架节点不得再产生新完成 ----------

    @Test
    void delistedNodeCannotBeNewlyCompletedThroughAnswerOrSensor()
    {
        // 负控收紧(§7-2):已完成者(status=1)的幂等重试现在走原回执豁免,不再是「新完成」;
        // 本用例钉住的正是**未完成**玩家——无本人完成行,下架闸必须原样拦住。
        progressRows.clear();
        CmsTopicNode delisted = node(DELISTED_DONE, "已下架站", 0L, "碎片");
        delisted.setTemplateId(88L);
        when(nodeService.selectCmsTopicNodeById(DELISTED_DONE)).thenReturn(delisted);

        AjaxResult answer = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(DELISTED_DONE), "A", null, null, null);
        assertEquals(500, answer.get("code"));
        assertEquals("该站已下架，暂时无法完成", answer.get("msg"));

        com.chengyinhub.business.domain.dto.SensorResultRequest request =
                new com.chengyinhub.business.domain.dto.SensorResultRequest();
        request.setActivityId(ACTIVITY);
        request.setNodeId(DELISTED_DONE);
        request.setSensorType("still");
        request.setPayload(Collections.singletonMap("heldSec", 12));
        AjaxResult sensor = BasicPlayCommandFixtures.sensorResult(controller, request);
        assertEquals(500, sensor.get("code"));
        assertEquals("该站已下架，暂时无法完成", sensor.get("msg"));

        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedNodeCannotBeNewlyCheckedInByOldCode()
    {
        // 负控收紧(§7-2):同上,只钉未完成玩家的旧码不得产生新完成
        progressRows.clear();
        CmsTopicNode delisted = node(DELISTED_DONE, "已下架站", 0L, "碎片");
        delisted.setCheckinCode("OLD-CODE");
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(delisted));

        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), "OLD-CODE", null, null, null, null, String.valueOf(DELISTED_DONE));

        assertEquals(500, result.get("code"));
        assertEquals("该站已下架，暂时无法完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void activeNodeStillPassesDelistedGuard()
    {
        CmsTopicNode active = node(ACTIVE_NODE, "在线站", 1L, "碎片");
        active.setTemplateId(88L);
        when(nodeService.selectCmsTopicNodeById(ACTIVE_NODE)).thenReturn(active);

        AjaxResult answer = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(ACTIVE_NODE), "A", null, null, null);

        assertNotEquals("该站已下架，暂时无法完成", answer.get("msg"),
                "正常在线节点不得被下架闸误伤");
    }

    @Test
    void historyReadPathNeverWrites()
    {
        nodesData();
        nodesData();

        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(progressService, times(2)).selectPlayerNodeProgressList(any());
        assertNotNull(controller);
    }
}
