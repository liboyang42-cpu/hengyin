package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.ITopicCompletionCouponRewardService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.chengyinhub.web.service.TopicRouteCompletionCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路线版本冲突回包契约:真实完成入口(/api/play/checkin)经 controller + GlobalExceptionHandler,
 * 冲突业务码必须落到 HTTP 200 的 body code=409;普通错误保持非 409。
 */
class ApiPlayRouteConflictHttpContractTest {

    private static final Long MEMBER = 88L;
    private static final Long TOPIC = 300L;
    private static final Long NODE = 401L;
    private static final Long ACTIVITY = 500L;

    private IPlayerNodeProgressService progressService;
    private ICmsTopicNodeService nodeService;
    private ICmsTopicService cmsTopicService;
    private ICmsActivityService activityService;
    private ICmsRegistrationService registrationService;
    private IPlayerProgressionService playerProgressionService;
    private IGrowthService growthService;
    private IXpBudgetService xpBudgetService;
    private IRewardDropService rewardDropService;
    private IPointsAwardService pointsAwardService;
    private ISmsCouponHistoryService smsCouponHistoryService;
    private ITopicRouteRuntimeService runtimeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        progressService = mock(IPlayerNodeProgressService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        cmsTopicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        playerProgressionService = mock(IPlayerProgressionService.class);
        growthService = mock(IGrowthService.class);
        xpBudgetService = mock(IXpBudgetService.class);
        rewardDropService = mock(IRewardDropService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        smsCouponHistoryService = mock(ISmsCouponHistoryService.class);
        runtimeService = mock(ITopicRouteRuntimeService.class);

        ApiPlayProgressController controller = new TestController();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", cmsTopicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", mock(IPerkLedgerService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", mock(IShopDayProofService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", pointsAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", playerProgressionService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", growthService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", xpBudgetService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", rewardDropService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerBadgeMapper", mock(PlayerBadgeMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper", mock(PlayAttemptLogMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper", mock(MemberCollectibleMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", smsCouponHistoryService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionCouponRewardService",
                mock(ITopicCompletionCouponRewardService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", mock(ICmsMemberTemplateService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "officialEventV2Service", mock(IOfficialEventV2Service.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService",
                mock(ITopicCompletionAwardService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper", mock(ClubLeadMapper.class));
        // 承接方现场层:没有中标商家时返回空对象,取值全部回落主办方模板 —— 用真实现而不是 mock。
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new NodeMerchantOverrideServiceImpl(mock(
                        com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", runtimeService);
        // 真实协调器:验证冲突 code 不被协调器吞掉,而是一路传到全局异常处理器。
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteCompletionCoordinator",
                new TopicRouteCompletionCoordinator(runtimeService));

        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(nodeEntity()));
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(nodeEntity());
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity());
        CmsRegistration ticket = new CmsRegistration(); ticket.setId(901L); ticket.setMemberId(MEMBER);
        ticket.setOwnerType(2L); ticket.setOwnerId(ACTIVITY); ticket.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(ticket));
        when(progressService.insertPlayerNodeProgress(any())).thenAnswer(inv -> {
            PlayerNodeProgress progress = inv.getArgument(0);
            progress.setId(701L);
            return 1;
        });
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(new ArrayList<>());
        when(playerProgressionService.onNodeDone(anyLong())).thenReturn(new ArrayList<>());
        when(runtimeService.canPlay(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(true);

        TopicRouteState branch = new TopicRouteState();
        branch.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        when(runtimeService.state(eq(MEMBER), eq(TOPIC), eq(ACTIVITY))).thenReturn(branch);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
        BasicPlayCommandFixtures.routeVersion(controller, ACTIVITY, TOPIC, 0);
    }

    @Test
    void routeVersionConflictIsHttp200WithBodyCode409() throws Exception {
        when(runtimeService.advanceOriginal(anyLong(), anyLong(), anyLong(), eq(99L), anyInt(), anyString(), anyString(),
                any(NodeOutcome.class)))
                .thenThrow(new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT));

        mockMvc.perform(post("/api/play/checkin")
                        .param("activityId", String.valueOf(ACTIVITY))
                        .param("code", "CHECKIN")
                        .param("nodeId", String.valueOf(NODE))
                        .param("routeSessionId", "99")
                        .param("routeActionId", "client-action")
                        .param("expectedRouteVersion", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.CONFLICT))
                .andExpect(jsonPath("$.msg").value("路线状态已更新，请刷新后重试"));
    }

    @Test
    void routeVersionConflictCarriesCurrentWorldInBodyData() throws Exception {
        TopicRouteState current = new TopicRouteState();
        current.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        current.setVersion(4);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("routeState", current);
        when(runtimeService.advanceOriginal(anyLong(), anyLong(), anyLong(), eq(99L), anyInt(), anyString(), anyString(),
                any(NodeOutcome.class)))
                .thenThrow(new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT).setData(data));

        mockMvc.perform(post("/api/play/checkin")
                        .param("activityId", String.valueOf(ACTIVITY))
                        .param("code", "CHECKIN")
                        .param("nodeId", String.valueOf(NODE))
                        .param("routeSessionId", "99")
                        .param("routeActionId", "client-action")
                        .param("expectedRouteVersion", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.CONFLICT))
                .andExpect(jsonPath("$.data.routeState.version").value(4));
    }

    @Test
    void nonConflictCompletionErrorStaysHttp200Without409() throws Exception {
        when(runtimeService.advanceOriginal(anyLong(), anyLong(), anyLong(), eq(99L), anyInt(), anyString(), anyString(),
                any(NodeOutcome.class)))
                .thenThrow(new IllegalStateException("节点当前不可用"));

        mockMvc.perform(post("/api/play/checkin")
                        .param("activityId", String.valueOf(ACTIVITY))
                        .param("code", "CHECKIN")
                        .param("nodeId", String.valueOf(NODE))
                        .param("routeSessionId", "99")
                        .param("routeActionId", "client-action")
                        .param("expectedRouteVersion", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.ERROR))
                .andExpect(jsonPath("$.msg").value("节点当前不可用"));
    }

    private CmsTopicNode nodeEntity() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        return node;
    }

    private CmsTopic linearTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        return topic;
    }

    private CmsActivity activity() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        return activity;
    }

    private static class TestController extends ApiPlayProgressController {
        @Override
        public Long getAppUserId() {
            return MEMBER;
        }
    }
    @Test
    void pausedRouteRejectionReachesGlobalHandlerBeforeProgressWrites() throws Exception {
        when(runtimeService.canPlay(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("本站尚未开放或已暂停，请联系带队主理人"));
        mockMvc.perform(post("/api/play/checkin")
                        .param("activityId", String.valueOf(ACTIVITY)).param("code", "CHECKIN")
                        .param("nodeId", String.valueOf(NODE))
                        .param("routeSessionId", "99")
                        .param("routeActionId", "paused-route-action")
                        .param("expectedRouteVersion", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("本站尚未开放或已暂停，请联系带队主理人"));
        org.mockito.Mockito.verify(progressService, org.mockito.Mockito.never()).insertPlayerNodeProgress(any());
    }

}
