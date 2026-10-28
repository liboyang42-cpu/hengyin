package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** /api/play/nodes 对分支路线的服务端可见性合同。 */
class ApiPlayBranchNodeVisibilityTest {
    private static final Long MEMBER = 71L;
    private static final Long ACTIVITY = 72L;
    private static final Long TOPIC = 73L;

    private ApiPlayProgressController controller;
    private CmsTopic topic;
    private ITopicRouteRuntimeService routeRuntimeService;

    @BeforeEach
    void setUp() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        routeRuntimeService = mock(ITopicRouteRuntimeService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(MEMBER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", routeRuntimeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService",
                mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));
        var fixtureHintSettings = mock(com.chengyinhub.business.service.ICmsPointsSettingService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService",
                mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(
                Collections.singletonList(new CmsRegistration()));

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(1);
        topic.setRouteMode(TopicRouteState.LINEAR);
        topic.setPublishedRouteGraphJson("{\"schemaVersion\":1}");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsTopicNode visible = node(101L, "已解锁地点", 1L);
        CmsTopicNode hidden = node(102L, "未选择支线秘密", 2L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(visible, hidden));
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    @SuppressWarnings("unchecked")
    @Test
    void branchGraphReturnsOnlyNodesVisibleInAuthoritativeRouteState() {
        TopicRouteState state = routeState(TopicRouteState.BRANCH_GRAPH);
        state.getNodeStates().put(101L, TopicRouteState.PLAYABLE);
        state.getNodeStates().put(102L, TopicRouteState.HIDDEN);
        when(routeRuntimeService.state(MEMBER, TOPIC, ACTIVITY)).thenReturn(state);

        AjaxResult response = controller.nodes(ACTIVITY, null);

        assertEquals(200, response.get("code"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(1, nodes.size());
        assertEquals(101L, nodes.get(0).get("nodeId"));
        assertFalse(nodes.toString().contains("未选择支线秘密"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void linearRouteKeepsTheExistingCompleteNodeList() {
        topic.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        topic.setPublishedRouteGraphJson(null);
        TopicRouteState state = routeState(TopicRouteState.LINEAR);
        state.getNodeStates().put(102L, TopicRouteState.HIDDEN);
        when(routeRuntimeService.state(MEMBER, TOPIC, ACTIVITY)).thenReturn(state);

        AjaxResult response = controller.nodes(ACTIVITY, null);

        assertEquals(200, response.get("code"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(Arrays.asList(101L, 102L),
                Arrays.asList(nodes.get(0).get("nodeId"), nodes.get(1).get("nodeId")));
    }

    @Test
    void branchGraphFailsClosedWhenAuthoritativeStateIsUnavailable() {
        when(routeRuntimeService.state(MEMBER, TOPIC, ACTIVITY))
                .thenReturn(routeState(TopicRouteState.LINEAR));

        AjaxResult response = controller.nodes(ACTIVITY, null);

        assertEquals(500, response.get("code"));
        assertEquals("路线状态暂时不可用,请重试", response.get("msg"));
        assertFalse(response.containsKey("data"));
    }

    private CmsTopicNode node(Long id, String name, Long sortId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC);
        node.setName(name);
        node.setSortId(sortId);
        node.setStatus(1L);
        return node;
    }

    private TopicRouteState routeState(String routeMode) {
        TopicRouteState state = new TopicRouteState();
        state.setRouteMode(routeMode);
        state.setNodeStates(new LinkedHashMap<Long, String>());
        return state;
    }

    @SuppressWarnings("unchecked")
    @Test
    void freeExploreThoughtsInitializeJourneyWithoutFilteringFreeChoices() {
        topic.setProductType(2);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "npcProfileService",
                mock(com.chengyinhub.business.service.impl.NpcProfileService.class));
        topic.setPublishedRouteGraphJson(null);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":false,"
                + "\"thoughts\":[{\"key\":\"afternoon\",\"name\":\"那个下午\",\"need\":120}]}");
        TopicRouteState state = routeState(TopicRouteState.LINEAR);
        state.setSessionId(91L);
        state.getNodeStates().put(101L, TopicRouteState.PLAYABLE);
        state.getNodeStates().put(102L, TopicRouteState.DISCOVERED_LOCKED);
        when(routeRuntimeService.state(MEMBER, TOPIC, ACTIVITY)).thenReturn(state);

        AjaxResult response = controller.nodes(ACTIVITY, null);

        assertEquals(200, response.get("code"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals(state, data.get("routeState"));
        assertEquals(2, ((List<?>) data.get("nodes")).size());
        org.mockito.Mockito.verify(routeRuntimeService).state(MEMBER, TOPIC, ACTIVITY);
    }
    @Test
    void freeContentSummaryCountsCompletedVisibleNodes() {
        prepareFreeProgress(101L);
        Map<String, Object> data = freeNodesData();
        assertEquals(2, data.get("total"));
        assertEquals(1L, ((Number) data.get("doneCount")).longValue());
    }

    @Test
    void freeMerchantGameWithoutRedemptionDoesNotCountAsDone() {
        prepareFreeProgress(101L, 102L);
        ICmsTopicNodeService nodeService = (ICmsTopicNodeService)
                com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "cmsTopicNodeService");
        CmsTopicNode content = node(101L, "故事地点", 1L);
        CmsTopicNode merchant = node(102L, "等待商家核销", 2L);
        merchant.setMerchantMemberId(501L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(content, merchant));
        Map<String, Object> data = freeNodesData();
        assertEquals(2, data.get("total"));
        assertEquals(1L, ((Number) data.get("doneCount")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(false, nodes.get(1).get("done"));
    }

    @Test
    void freeSummaryDoesNotCountCompletionOutsideCurrentNodeList() {
        prepareFreeProgress(101L, 999L);
        assertEquals(1L, ((Number) freeNodesData().get("doneCount")).longValue());
    }

    private void prepareFreeProgress(Long... completedIds) {
        topic.setProductType(2);
        topic.setPublishedRouteGraphJson(null);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "npcProfileService",
                mock(com.chengyinhub.business.service.impl.NpcProfileService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicOnsiteMetricsMapper",
                mock(com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicTemplateMetaMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "merchantChapterOfferMapper",
                mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "coopPerkMapper",
                mock(com.chengyinhub.business.mapper.CoopPerkMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService",
                mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        com.chengyinhub.business.service.IPlayerNodeProgressService progressService =
                (com.chengyinhub.business.service.IPlayerNodeProgressService)
                        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "playerNodeProgressService");
        List<com.chengyinhub.business.domain.PlayerNodeProgress> progress = new java.util.ArrayList<>();
        for (Long id : completedIds) {
            com.chengyinhub.business.domain.PlayerNodeProgress row =
                    new com.chengyinhub.business.domain.PlayerNodeProgress();
            row.setNodeId(id);
            row.setStatus(1);
            progress.add(row);
        }
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(progress);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> freeNodesData() {
        AjaxResult response = controller.nodes(ACTIVITY, null);
        assertEquals(200, response.get("code"), String.valueOf(response));
        return (Map<String, Object>) response.get("data");
    }

}
