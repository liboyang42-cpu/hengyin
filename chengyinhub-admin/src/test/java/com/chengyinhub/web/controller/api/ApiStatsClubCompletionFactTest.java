package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 俱乐部看板 /api/stats/club(R10-09 收尾):通关分子只认 status==1 正式完成,
 * 到店/凭证 status=0 行不得把成员算成已通关;到达仍是参与(/participants)与漏斗的合法事实。
 */
class ApiStatsClubCompletionFactTest
{
    private static final long OWNER = 41L;
    private static final long CLUB = 71L;
    private static final long TOPIC = 44L;
    private static final long NODE_A = 46L;
    private static final long NODE_B = 47L;
    private static final long PLAYER_1 = 51L;
    private static final long PLAYER_2 = 52L;

    private IClubService clubService;
    private ICmsTopicService topicService;
    private ICmsTopicNodeService nodeService;
    private IPlayerNodeProgressService progressService;
    private IPerkLedgerService perkLedgerService;
    private ApiStatsController controller;

    @BeforeEach
    void setUp()
    {
        clubService = mock(IClubService.class);
        topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        perkLedgerService = mock(IPerkLedgerService.class);

        controller = spy(new ApiStatsController());
        doReturn(OWNER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(controller, "perkLedgerService", perkLedgerService);

        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubService.selectClubById(CLUB)).thenReturn(club);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("俱乐部主题");
        topic.setProductType(ProductType.ORIENTEERING);
        when(topicService.selectCmsTopicList(any())).thenReturn(Collections.singletonList(topic));
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(
                node(NODE_A), node(NODE_B)));
        when(perkLedgerService.exploreCompletion(anyLong()))
                .thenReturn(new IPerkLedgerService.ExploreCompletion(Collections.emptySet(), Collections.emptySet()));
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
    }

    private CmsTopicNode node(long id)
    {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setStatus(1L);
        return n;
    }

    private PlayerNodeProgress row(long memberId, long nodeId, int status)
    {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(memberId);
        p.setTopicId(TOPIC);
        p.setNodeId(nodeId);
        p.setStatus(status);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call()
    {
        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);
        AjaxResult result = controller.club(body);
        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        return (Map<String, Object>) result.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstTopic(Map<String, Object> data)
    {
        return ((List<Map<String, Object>>) data.get("topics")).get(0);
    }

    @Test
    void freeLinearCompletionUsesLedgerEvenWhenThoughtSessionIsStillRunning() {
        CmsTopic free = new CmsTopic();
        free.setId(TOPIC);
        free.setProductType(ProductType.FREE_EXPLORE);
        when(topicService.selectCmsTopicList(any())).thenReturn(Collections.singletonList(free));
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", runtime);
        when(runtime.participantMemberIds(TOPIC)).thenReturn(Collections.singleton(PLAYER_1));
        when(runtime.completedMemberIds(TOPIC)).thenReturn(Collections.singleton(PLAYER_2));
        when(perkLedgerService.exploreCompletion(TOPIC)).thenReturn(
                new IPerkLedgerService.ExploreCompletion(Collections.singleton(PLAYER_1),
                        Collections.singleton(PLAYER_1)));

        assertEquals(1, firstTopic(call()).get("completed"),
                "已通关玩家不能被未结束的伏笔局排除，伏笔局结束也不能单独冒充主题通关");
        when(perkLedgerService.exploreCompletion(TOPIC)).thenReturn(
                new IPerkLedgerService.ExploreCompletion(Collections.singleton(PLAYER_1), Collections.emptySet()));
        assertEquals(0, firstTopic(call()).get("completed"));
    }

    @Test
    void arrivalOnlyRowsDoNotCountAsCompleted()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(PLAYER_1, NODE_A, 0),
                row(PLAYER_1, NODE_B, 0)));

        Map<String, Object> data = call();
        Map<String, Object> topic = firstTopic(data);

        assertEquals(0, topic.get("completed"), "仅到店(status=0)不得算已通关");
        assertEquals(1, topic.get("participants"), "到店仍是参与者(到达显示保留)");
    }

    @Test
    void mixedArrivalAndCompletionRowsDoNotPadCompletion()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(PLAYER_1, NODE_A, 1),
                row(PLAYER_1, NODE_B, 0)));

        Map<String, Object> topic = firstTopic(call());

        assertEquals(0, topic.get("completed"), "2 站只完成 1 站 + 1 到店不得凑满通关");
    }

    @Test
    void fullyCompletedMemberCountsOnce()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(PLAYER_1, NODE_A, 1),
                row(PLAYER_1, NODE_B, 1)));

        Map<String, Object> topic = firstTopic(call());

        assertEquals(1, topic.get("completed"), "真完成两站的成员必须计入通关");
        assertEquals(1, topic.get("participants"));
    }

    @Test
    void funnelKeepsArrivalCounts()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(PLAYER_1, NODE_A, 0),
                row(PLAYER_2, NODE_A, 1)));

        Map<String, Object> topic = firstTopic(call());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> funnel = (List<Map<String, Object>>) topic.get("funnel");

        assertEquals(2, funnel.get(0).get("doneCount"), "漏斗按节点计打卡人次,到店行照旧计入(显式不改)");
        assertEquals(0, topic.get("completed"));
    }

    @Test
    void nonOwnerCannotReadDashboard()
    {
        Club other = new Club();
        other.setId(CLUB);
        other.setMemberId(999L);
        other.setStatus(1L);
        other.setDelFlag(0);
        when(clubService.selectClubById(CLUB)).thenReturn(other);

        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);
        AjaxResult result = controller.club(body);

        assertEquals(500, result.get("code"));
        assertEquals("仅当前俱乐部主理人可查看数据看板", result.get("msg"));
        verify(progressService, never()).selectPlayerNodeProgressList(any());
    }

    @Test
    void topicsUnavailableIsRecoverableError()
    {
        when(topicService.selectCmsTopicList(any())).thenReturn(null);

        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);
        AjaxResult result = controller.club(body);

        assertEquals(500, result.get("code"));
        assertEquals("俱乐部数据暂时不可用", result.get("msg"));
    }
}
