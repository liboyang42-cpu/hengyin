package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.NodeMerchantOverride;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiPlayProgressController;
import com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class PlayEntryNodesSnapshotRegressionTest {

    private static final long MEMBER_ID = 9004L;
    private static final long ACTIVITY_ID = 992034L;
    private static final long TOPIC_ID = 992001L;
    private static final long NODE_ID = 992021L;
    private static final long REGISTRATION_ID = 992101L;

    private final ObjectMapper json = new ObjectMapper();
    private ICmsActivityService activityService;
    private ICmsRegistrationService registrationService;
    private ICmsTopicService topicService;
    private ICmsTopicNodeService nodeService;
    private ICmsMemberTemplateService templateService;
    private IPlayerNodeProgressService progressService;
    private ITopicRouteRuntimeService runtimeService;
    private ApiPlayProgressController controller;
    private CmsTopic topic;
    private CmsTopicNode node;

    @BeforeEach
    void setUp() {
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        templateService = mock(ICmsMemberTemplateService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        runtimeService = mock(ITopicRouteRuntimeService.class);
        WxContentCallbackInboxMapper callbackInboxMapper = mock(WxContentCallbackInboxMapper.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(MEMBER_ID).when(controller).getAppUserId();
        PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        INodeMerchantOverrideService overrideService = mock(INodeMerchantOverrideService.class);
        when(overrideService.forNode(anyLong())).thenReturn(NodeMerchantOverride.empty());
        PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService", overrideService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
        PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", chapterService);
        PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));
        PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", runtimeService);
        PlayNodeAccessTestFixtures.setField(controller, "wxContentCallbackInboxMapper", callbackInboxMapper);
        when(callbackInboxMapper.countRiskyCallback(anyInt(), anyLong())).thenReturn(0);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        activity.setStatus(1L);
        activity.setDelFlag(0);
        when(activityService.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION_ID);
        registration.setMemberId(MEMBER_ID);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY_ID);
        registration.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration));

        topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setProductType(1);
        topic.setRouteMode("LINEAR");
        topic.setPublishedConfigVersion(1L);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(topicService.selectCmsTopicDetailById(eq(TOPIC_ID), any())).thenReturn(topic);

        node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setChapterId(1L);
        node.setName("起点线索");
        node.setStatus(1L);
        node.setTemplateId(55L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        CmsMemberTemplate liveTemplate = new CmsMemberTemplate();
        liveTemplate.setId(55L);
        liveTemplate.setValidationMethod(1);
        liveTemplate.setQuestionName("现场题干");
        liveTemplate.setQuestionAnswer("hidden-secret");
        when(templateService.selectCmsMemberTemplateById(55L)).thenReturn(liveTemplate);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(1L);
        chapter.setTopicId(TOPIC_ID);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
    }

    @Test
    @SuppressWarnings("unchecked")
    void incompleteSessionSnapshotFallsBackToLiveNodesWith200AndNoAnswerLeak() {
        when(runtimeService.queryNodeContentSnapshot(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, null))
                .thenThrow(new IllegalStateException("本局内容快照不完整"));

        AjaxResult result = controller.nodes(ACTIVITY_ID, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertNotNull(data);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(1, nodes.size());
        assertEquals(NODE_ID, nodes.get(0).get("nodeId"));
        assertNull(nodes.get(0).get("questionAnswer"));
        assertNull(nodes.get(0).get("answerReveal"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialNodeSnapshotWithoutRulesOrPresentationDegradesToLiveNodeProjection() {
        ObjectNode partial = json.createObjectNode().put("contentSnapshotVersion", 1);
        ObjectNode answers = partial.putObject("nodeAnswers");
        answers.putObject(String.valueOf(NODE_ID)).put("questionAnswer", "secret").put("contentSnapshotVersion", 1);
        partial.putObject("nodeConfigs");
        when(runtimeService.queryNodeContentSnapshot(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, null)).thenReturn(partial);

        AjaxResult result = controller.nodes(ACTIVITY_ID, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(1, nodes.size());
        assertEquals(NODE_ID, nodes.get(0).get("nodeId"));
        assertNull(nodes.get(0).get("questionAnswer"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void realRuntimeWithLegacyExtraStateAndIncompleteConfigReturns200Nodes() {
        TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);

        TopicRouteRuntimeServiceImpl realRuntime = new TopicRouteRuntimeServiceImpl(
                routeMapper,
                topicMapper,
                nodeMapper,
                mock(CmsMemberTemplateMapper.class),
                mock(PlayerNodeProgressMapper.class),
                activityMapper,
                registrationMapper,
                mock(PlayerActivityRoleMapper.class),
                () -> 1L,
                Clock.system(ZoneId.of("Asia/Shanghai")),
                RouteReachabilityProvider.UNKNOWN);
        PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", realRuntime);

        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        activity.setStatus(1L);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION_ID);
        registration.setMemberId(MEMBER_ID);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY_ID);
        registration.setRegistrationStatus(2);
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        TopicRouteSession legacySession = new TopicRouteSession();
        legacySession.setId(993804L);
        legacySession.setActivityId(ACTIVITY_ID);
        legacySession.setTopicId(TOPIC_ID);
        legacySession.setMemberId(MEMBER_ID);
        legacySession.setStatus(TopicRouteSession.STATUS_RUNNING);
        legacySession.setVersion(1);
        legacySession.setConfigSnapshotJson("{\"contentSnapshotVersion\":1,\"nodeAnswers\":{\"992021\":{}}}");
        legacySession.setStateJson("{\"status\":\"ACTIVE\",\"earnedXp\":120,\"visitedNodeCount\":1}");
        when(routeMapper.selectCurrentByOwner(anyLong(), anyLong(), anyLong())).thenReturn(legacySession);

        AjaxResult result = controller.nodes(ACTIVITY_ID, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertFalse(nodes.isEmpty());
        assertEquals(NODE_ID, nodes.get(0).get("nodeId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void freshRegistrationWithMissingSessionAndNullEngineStateReturns200Nodes() {
        when(runtimeService.queryNodeContentSnapshot(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, null)).thenReturn(null);
        when(runtimeService.state(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(null);

        AjaxResult result = controller.nodes(ACTIVITY_ID, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(1, nodes.size());
        assertEquals(NODE_ID, nodes.get(0).get("nodeId"));
    }

    @Test
    void serviceExceptionStatusCodeFromRouteStateIsPreserved() {
        when(runtimeService.state(MEMBER_ID, TOPIC_ID, ACTIVITY_ID))
                .thenThrow(new ServiceException("客户端版本过低", HttpStatus.UPGRADE_REQUIRED));

        AjaxResult result = controller.nodes(ACTIVITY_ID, null);

        assertEquals(HttpStatus.UPGRADE_REQUIRED, result.get(AjaxResult.CODE_TAG));
    }
}
