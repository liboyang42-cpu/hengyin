package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.domain.dto.SensorResultRequest;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl;
import com.chengyinhub.business.service.impl.SensorChallengeServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.PreferencePlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则16 收尾(§7-2):五个完成入口 checkin/answer/submitPreference/sensorResult/arrive
 * 与 photo 同口径 —— 本人已正式完成(status==1)的下架节点,幂等重试必须保留原回执,
 * 不得被下架闸拦成「该站已下架」;未完成的新提交仍被下架闸拒(负控)。
 * 只替换下游服务缝,五个入口都走真实方法。
 *
 * <p><b>P1-1 修复后必须注入真实路线 seam</b>:以前不注入 {@code topicRouteRuntimeService},
 * {@code routeAccessError} 走 {@code isPrevDone} 回退,而生产走的是
 * {@code TopicRouteRuntimeServiceImpl.canPlay} —— 它的活节点集只取 status=1,
 * 已下架节点会被判「不属于当前主题」⇒ 旧测试测的不是生产那条路,是假绿。
 * 现在 setUp 装配真实 Runtime(canPlay 走 NodeAccessPolicy + status=1 节点集),
 * 正例必须在「会拒绝下架节点」的真实 seam 上仍拿到幂等回执。
 */
class ApiPlayDelistedCompletedRetryContractTest
{
    private static final Long PLAYER = 81L;
    private static final Long ACTIVITY = 82L;
    private static final Long TOPIC = 83L;
    private static final Long NODE = 84L;
    private static final Long TEMPLATE = 85L;
    private static final Long REGISTRATION = 86L;
    private static final String CHECKIN_CODE = "STORE-CODE-16";

    private IPlayerNodeProgressService progressService;
    private IShopDayProofService proofService;
    private PreferencePlayService preferencePlayService;
    private ApiPlayProgressController controller;
    private CmsTopicNode node;
    private CmsMemberTemplate template;
    private CmsTopicNodeMapper routeNodeMapper;
    private PlayerNodeProgressMapper routeProgressMapper;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();

        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        proofService = mock(IShopDayProofService.class);
        preferencePlayService = mock(PreferencePlayService.class);
        IPointsAwardService pointsAwardService = mock(IPointsAwardService.class);
        IPlayerProgressionService progressionService = mock(IPlayerProgressionService.class);
        ITopicCompletionAwardService completionAwardService = mock(ITopicCompletionAwardService.class);

        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        when(registrationMapper.markFirstSuccessfulScan(eq(REGISTRATION), eq(PLAYER),
                eq(2L), eq(ACTIVITY), any(Date.class))).thenReturn(1);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationMapper", registrationMapper);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", pointsAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", mock(IRewardDropService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", mock(ISmsCouponHistoryService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progressionService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", mock(IGrowthService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completionAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", proofService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", mock(IXpBudgetService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper",
                mock(com.chengyinhub.business.mapper.ClubLeadMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new NodeMerchantOverrideServiceImpl(
                        mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", mock(IPerkLedgerService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "preferencePlayService", preferencePlayService);
        SensorChallengeServiceImpl sensorChallenges = new SensorChallengeServiceImpl();
        ReflectionTestUtils.setField(sensorChallenges, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "sensorChallengeService", sensorChallenges);

        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setCheckinCode(CHECKIN_CODE);
        node.setSortId(1L);
        node.setStatus(0L); // 规则16 普通下架:已完成回执不得被下架闸拦,未完成仍拦
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setValidationMethod(ValidationMethod.APP_SENSOR);
        template.setSensorType("still");
        template.setSensorConfig("{\"durationSec\":10}");
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION);
        registration.setMemberId(PLAYER);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY);
        registration.setRegistrationStatus(2);
        registration.setPaymentStatus(2);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration));

        // P1-1:注入**真实** TopicRouteRuntimeServiceImpl 作为路线 seam(与生产同一条路)。
        // canPlay 的活节点集只取 status=1,下架节点会被 NodeAccessPolicy 判「不属于当前主题」——
        // 正例要证明:已完成事实在 canPlay 之前先被认到,未被这条路线 seam 拦成「该地点尚未解锁」。
        TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper routeTopicMapper = mock(CmsTopicMapper.class);
        routeNodeMapper = mock(CmsTopicNodeMapper.class);
        routeProgressMapper = mock(PlayerNodeProgressMapper.class);
        CmsActivityMapper routeActivityMapper = mock(CmsActivityMapper.class);
        CmsRegistrationMapper routeRegistrationMapper = mock(CmsRegistrationMapper.class);

        CmsTopic routeTopic = new CmsTopic();
        routeTopic.setId(TOPIC);
        routeTopic.setPublishedConfigVersion(1L); // 已发布但无分支图 ⇒ 生产按 LINEAR 路线走
        when(routeTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(routeTopic);

        CmsActivity routeActivity = new CmsActivity();
        routeActivity.setId(ACTIVITY);
        routeActivity.setTopicId(TOPIC);
        routeActivity.setStatus(1L);
        routeActivity.setDelFlag(0);
        when(routeActivityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(routeActivity);
        when(routeRegistrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration));
        // C包路线新使用按 activity→registration 当前锁读(CurrentPlayRegistration)；锁读回同一原行。
        when(routeActivityMapper.selectCmsActivityByIdForUpdate(ACTIVITY)).thenReturn(routeActivity);
        when(routeRegistrationMapper.selectCmsRegistrationByIdForUpdate(REGISTRATION)).thenReturn(registration);
        when(routeMapper.selectByOwner(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(null);
        when(routeNodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        // 真实 nodes() 口径:只有 status=1 的活节点进集合;下架节点查不到(注 0=下架)
        when(routeNodeMapper.selectCmsTopicNodeList(any())).thenAnswer(invocation -> {
            CmsTopicNode probe = invocation.getArgument(0);
            return Long.valueOf(1L).equals(probe.getStatus())
                    ? Collections.emptyList() : Collections.singletonList(node);
        });
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService",
                new TopicRouteRuntimeServiceImpl(routeMapper, routeTopicMapper, routeNodeMapper,
                        routeProgressMapper, routeActivityMapper, routeRegistrationMapper));

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        when(progressionService.onNodeDone(PLAYER)).thenReturn(Collections.emptyList());
        when(completionAwardService.grantOnce(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new TopicCompletionAwardVO());
        when(completionAwardService.grantOnce(anyLong(), anyLong(), anyLong(), any(), any(RewardPolicy.class)))
                .thenReturn(new TopicCompletionAwardVO());
        when(proofService.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(true);
        mockProgressRows(true);
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
    }

    /**
     * 进度探针替身:带 nodeId 的探针(幂等豁免回查/传感器已完判)按 completed/arrivalOnly 返本人行;
     * 不带 nodeId 的探针(done 计数)固定返一行 status=1 完成事实。
     * <p>route 侧进度 mapper 是 canPlay 的 completed 真源,必须与 service 替身同一份事实
     * (否则「已完成」只对被测的豁免分支成立,canPlay 仍按空集判路线解锁)。
     */
    private void mockProgressRows(boolean completed)
    {
        Answer<List<PlayerNodeProgress>> rows = invocation -> {
            PlayerNodeProgress probe = invocation.getArgument(0);
            if (probe.getNodeId() != null) {
                if (!completed) return Collections.emptyList();
                PlayerNodeProgress row = new PlayerNodeProgress();
                row.setId(9001L);
                row.setMemberId(PLAYER);
                row.setActivityId(probe.getActivityId());
                row.setTopicId(TOPIC);
                row.setNodeId(NODE);
                row.setStatus(1);
                return Collections.singletonList(row);
            }
            PlayerNodeProgress done = new PlayerNodeProgress();
            done.setStatus(1);
            return Collections.singletonList(done);
        };
        doAnswer(rows).when(progressService).selectPlayerNodeProgressList(any());
        doAnswer(rows).when(routeProgressMapper).selectPlayerNodeProgressList(any());
    }

    private void mockDuplicateInsert()
    {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(new DuplicateKeyException("uk_progress"));
    }

    private AjaxResult checkin()
    {
        return BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CHECKIN_CODE, null, null, null, null, String.valueOf(NODE));
    }

    private AjaxResult answer()
    {
        return BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "A", null, null, null);
    }

    private AjaxResult preference()
    {
        PreferenceSubmitRequest request = new PreferenceSubmitRequest();
        request.setActivityId(String.valueOf(ACTIVITY));
        return BasicPlayCommandFixtures.submitPreference(controller, NODE, request);
    }

    private AjaxResult sensor()
    {
        SensorResultRequest request = new SensorResultRequest();
        request.setActivityId(ACTIVITY);
        request.setNodeId(NODE);
        request.setSensorType("still");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("heldSec", 12);
        request.setPayload(payload);
        return BasicPlayCommandFixtures.sensorResult(controller, request);
    }

    private AjaxResult arrive()
    {
        return BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null, null, null);
    }

    // ---------- 已完成重试:下架闸必须放行原幂等回执 ----------

    @Test
    void delistedCompletedCheckinRetryKeepsIdempotentReceipt()
    {
        mockDuplicateInsert();

        AjaxResult result = checkin();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已打卡", result.get("msg"));
        verify(progressService, times(1)).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedCompletedAnswerRetryKeepsIdempotentReceipt()
    {
        node.setTemplateId(TEMPLATE);
        template.setValidationMethod(3);
        template.setCorrectAnswer("A");
        mockDuplicateInsert();

        AjaxResult result = answer();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已完成", result.get("msg"));
        verify(progressService, times(1)).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedCompletedPreferenceRetryReachesSubmitAndKeepsReceipt()
    {
        node.setTemplateId(TEMPLATE);
        template.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("firstTime", false);
        when(preferencePlayService.submit(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(stored);

        AjaxResult result = preference();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("偏好结果已生成", result.get("msg"));
        verify(preferencePlayService).submit(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedCompletedSensorRetryKeepsIdempotentReceipt()
    {
        node.setTemplateId(TEMPLATE);
        mockDuplicateInsert();

        AjaxResult result = sensor();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已完成", result.get("msg"));
        verify(progressService, times(1)).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedCompletedArriveRetryKeepsIdempotentReceipt()
    {
        mockDuplicateInsert();

        AjaxResult result = arrive();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已完成", result.get("msg"));
        verify(progressService, times(1)).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedCompletedPhotoRetryKeepsIdempotentReceipt()
    {
        node.setTemplateId(TEMPLATE);
        template.setValidationMethod(ValidationMethod.PHOTO);
        mockDuplicateInsert();

        AjaxResult result = BasicPlayCommandFixtures.photo(controller, String.valueOf(ACTIVITY), String.valueOf(NODE),
                "https://cdn.example.com/p/1.jpg", null, null, null);

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已完成", result.get("msg"));
        verify(progressService, times(1)).insertPlayerNodeProgress(any());
    }

    // ---------- 负控:未完成的新提交仍被下架闸拒 ----------

    @Test
    void delistedNodeWithoutCompletionStillRejectedOnCheckin()
    {
        mockProgressRows(false);

        AjaxResult result = checkin();

        assertEquals(500, result.get("code"));
        assertEquals("该站已下架，暂时无法完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedNodeWithoutCompletionStillRejectedOnAnswer()
    {
        node.setTemplateId(TEMPLATE);
        template.setValidationMethod(3);
        template.setCorrectAnswer("A");
        mockProgressRows(false);

        AjaxResult result = answer();

        assertEquals(500, result.get("code"));
        assertEquals("该站已下架，暂时无法完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedNodeWithoutCompletionStillRejectedOnPreference()
    {
        node.setTemplateId(TEMPLATE);
        template.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);
        mockProgressRows(false);

        AjaxResult result = preference();

        // ⚠️ 偏好提交的 ctx 解析(内含路线 seam)在下架闸之前,未完成的下架节点被路线 seam 拒成
        // 「该地点尚未解锁」;这是既有次序(不是本次修复引入),关键是回执没被放行、也没写进度。
        assertEquals(500, result.get("code"));
        assertEquals("该地点尚未解锁", result.get("msg"));
        verify(preferencePlayService, never()).submit(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void delistedNodeWithoutCompletionStillRejectedOnSensor()
    {
        node.setTemplateId(TEMPLATE);
        mockProgressRows(false);

        AjaxResult result = sensor();

        assertEquals(500, result.get("code"));
        assertEquals("该站已下架，暂时无法完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void delistedNodeWithoutCompletionStillRejectedOnArrive()
    {
        mockProgressRows(false);

        AjaxResult result = arrive();

        assertEquals(500, result.get("code"));
        assertEquals("该站已下架，暂时无法完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    /** 边界:到店/凭证行(status=0)不是正式完成,不享受豁免(§7-1 维持现状)。 */
    @Test
    void arrivalOnlyRowStillDoesNotExemptDelistedCheckin()
    {
        doAnswer(invocation -> {
            PlayerNodeProgress probe = invocation.getArgument(0);
            if (probe.getNodeId() != null) {
                PlayerNodeProgress voucher = new PlayerNodeProgress();
                voucher.setId(9002L);
                voucher.setMemberId(PLAYER);
                voucher.setActivityId(probe.getActivityId());
                voucher.setTopicId(TOPIC);
                voucher.setNodeId(NODE);
                voucher.setStatus(0);
                return Collections.singletonList(voucher);
            }
            return Collections.emptyList();
        }).when(progressService).selectPlayerNodeProgressList(any());

        AjaxResult result = checkin();

        assertEquals(500, result.get("code"));
        assertEquals("该站已下架，暂时无法完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    /**
     * 负控:豁免必须只对**下架**节点生效。在架节点(status=1)即使本人已完成、canPlay 因前序
     * 未完成而拒绝,仍要报「该地点尚未解锁」—— 不能把修复做成「已完成一律绕开路线 seam」。
     */
    @Test
    void onlineCompletedNodeStillRespectsRouteGate()
    {
        node.setStatus(1L);
        CmsTopicNode first = new CmsTopicNode();
        first.setId(999L);
        first.setTopicId(TOPIC);
        first.setSortId(0L);
        // doReturn 而非 when():setUp 已给同一 matcher 装过会解引用的 answer,when(...) 的回放会先炸。
        doReturn(Arrays.asList(first, node)).when(routeNodeMapper).selectCmsTopicNodeList(any());

        AjaxResult result = checkin();

        assertEquals(500, result.get("code"));
        assertEquals("该地点尚未解锁", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }
}
