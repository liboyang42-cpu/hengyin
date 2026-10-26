package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl;
import com.chengyinhub.business.service.impl.SensorChallengeServiceImpl;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 独立审查 R1b/R2a/R2b/R2c/R3 的合同:来源校验只拦「首次写入」;
 * 已有权威完成事实/已记录凭证的合法重试必须走幂等回执;编程异常不得伪装 503。
 * 只替换下游服务与校验缝,photo() 走真实方法入口。
 */
class ApiPlayPhotoRetryAndFailureContractTest
{
    private static final Long PLAYER = 81L;
    private static final Long ACTIVITY = 82L;
    private static final Long TOPIC = 83L;
    private static final Long NODE = 84L;
    private static final Long TEMPLATE = 85L;
    private static final String RECORDED_URL = "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/"
            + "0123456789abcdef0123456789abcdef.jpg?Expires=4102444800&OSSAccessKeyId=AK&Signature=sig";

    private IPlayerNodeProgressService progressService;
    private IPointsAwardService pointsAwardService;
    private ITopicCompletionAwardService completionAwardService;
    private IShopDayProofService proofService;
    private ApiPlayProgressController controller;
    private CmsTopicNode node;

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
        pointsAwardService = mock(IPointsAwardService.class);
        completionAwardService = mock(ITopicCompletionAwardService.class);
        proofService = mock(IShopDayProofService.class);
        IPlayerProgressionService progressionService = mock(IPlayerProgressionService.class);

        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
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
        SensorChallengeServiceImpl sensorChallenges = new SensorChallengeServiceImpl();
        ReflectionTestUtils.setField(sensorChallenges, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "sensorChallengeService", sensorChallenges);

        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setTemplateId(TEMPLATE);
        node.setSortId(1L);
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setValidationMethod(ValidationMethod.PHOTO);
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsRegistration registration = new CmsRegistration();
        registration.setMemberId(PLAYER);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY);
        registration.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration));

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        when(progressionService.onNodeDone(PLAYER)).thenReturn(Collections.emptyList());
        when(completionAwardService.grantOnce(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new TopicCompletionAwardVO());
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
    }

    private void verifierRejects(AtomicBoolean called, RuntimeException failure)
    {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "photoUploadFactVerifier",
                (com.chengyinhub.web.service.play.PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (picUrl, memberId) -> {
                    called.set(true);
                    throw failure;
                });
    }

    private AjaxResult submit(String picUrl)
    {
        return BasicPlayCommandFixtures.photo(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), picUrl, null);
    }

    @Test
    void completedNodeRetryWithExpiredOrRotatedUrlKeepsAuthoritativeReceipt()
    {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(new DuplicateKeyException("uk_progress"));
        PlayerNodeProgress existing = new PlayerNodeProgress();
        existing.setId(9001L);
        existing.setStatus(1);
        existing.setPicUrl(RECORDED_URL);
        when(progressService.selectPlayerNodeProgressList(any())).thenAnswer(invocation -> {
            PlayerNodeProgress probe = invocation.getArgument(0);
            if (probe.getNodeId() != null) return Collections.singletonList(existing);
            PlayerNodeProgress done = new PlayerNodeProgress();
            done.setStatus(1);
            return Collections.singletonList(done);
        });
        AtomicBoolean verifierCalled = new AtomicBoolean(false);
        verifierRejects(verifierCalled,
                new IllegalArgumentException("照片上传凭证已过期，请重新拍摄上传"));

        AjaxResult result = submit(RECORDED_URL);

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已完成", result.get("msg"));
        assertEquals(false, verifierCalled.get(), "已完成节点的幂等回执不得再跑来源校验");
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void delistedCompletedPhotoNodeRetryKeepsIdempotentReceipt()
    {
        node.setStatus(0L); // 规则16 普通下架:已完成照片的历史回执不得被下架闸打断
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(new DuplicateKeyException("uk_progress"));
        PlayerNodeProgress existing = new PlayerNodeProgress();
        existing.setId(9001L);
        existing.setStatus(1);
        existing.setPicUrl(RECORDED_URL);
        when(progressService.selectPlayerNodeProgressList(any())).thenAnswer(invocation -> {
            PlayerNodeProgress probe = invocation.getArgument(0);
            if (probe.getNodeId() != null) return Collections.singletonList(existing);
            PlayerNodeProgress done = new PlayerNodeProgress();
            done.setStatus(1);
            return Collections.singletonList(done);
        });
        AtomicBoolean verifierCalled = new AtomicBoolean(false);
        verifierRejects(verifierCalled,
                new IllegalArgumentException("照片上传凭证已过期，请重新拍摄上传"));

        AjaxResult result = submit(RECORDED_URL);

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已完成", result.get("msg"));
        assertEquals(false, verifierCalled.get(), "下架站的已完成照片幂等重试不得再跑来源校验");
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void delistedNodeWithoutCompletionStillCannotBeNewlyCompleted()
    {
        node.setStatus(0L);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "photoUploadFactVerifier",
                (com.chengyinhub.web.service.play.PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (picUrl, memberId) -> { });

        AjaxResult result = submit(RECORDED_URL);

        assertEquals(500, result.get("code"));
        assertEquals("该站已下架，暂时无法完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void recordedVoucherRetryWithInvalidUrlKeepsRecordedProof()
    {
        CmsTopic voucherTopic = new CmsTopic();
        voucherTopic.setId(TOPIC);
        voucherTopic.setProductType(PlayMode.FREE_COLLECT);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService",
                (ICmsTopicService) mockTopicReturning(voucherTopic));
        PlayerNodeProgress recorded = new PlayerNodeProgress();
        recorded.setId(7001L);
        recorded.setStatus(1);
        recorded.setPicUrl(RECORDED_URL);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(recorded));
        when(proofService.submitPhoto(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(recorded);
        AtomicBoolean verifierCalled = new AtomicBoolean(false);
        verifierRejects(verifierCalled,
                new IllegalArgumentException("照片上传凭证已过期，请重新拍摄上传"));

        AjaxResult result = submit(RECORDED_URL);

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("到店照片已记录", result.get("msg"));
        assertEquals(false, verifierCalled.get(), "已记录凭证的幂等重试不得再跑来源校验");
        verify(proofService).submitPhoto(PLAYER, ACTIVITY, NODE, RECORDED_URL);
    }

    private ICmsTopicService mockTopicReturning(CmsTopic topic)
    {
        ICmsTopicService service = mock(ICmsTopicService.class);
        when(service.selectCmsTopicById(TOPIC)).thenReturn(topic);
        return service;
    }

    @Test
    void firstSubmissionStillRequiresSourceVerification()
    {
        AtomicBoolean verifierCalled = new AtomicBoolean(false);
        verifierRejects(verifierCalled,
                new IllegalArgumentException("照片上传凭证无效，请重新拍摄上传"));

        AjaxResult result = submit("not-an-uploaded-photo");

        assertEquals(500, result.get("code"));
        assertEquals("照片上传凭证无效，请重新拍摄上传", result.get("msg"));
        assertEquals(true, verifierCalled.get());
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void programmingExceptionIsNotDisguisedAs503()
    {
        AtomicBoolean verifierCalled = new AtomicBoolean(false);
        verifierRejects(verifierCalled, new NullPointerException("review-programming-bug"));

        AjaxResult result = submit(RECORDED_URL);

        assertEquals(500, result.get("code"), "编程异常必须 500 暴露,不得伪装 503");
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void storageFailureIsRecoverable503()
    {
        AtomicBoolean verifierCalled = new AtomicBoolean(false);
        verifierRejects(verifierCalled, new IllegalStateException("对象存储查询失败: connect timed out"));

        AjaxResult result = submit(RECORDED_URL);

        assertEquals(503, result.get("code"));
        assertEquals("照片校验服务暂不可用，请稍后重试", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }
}
