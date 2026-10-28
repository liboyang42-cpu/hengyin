package com.chengyinhub.web.controller.api;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.ServiceException;
import com.aliyun.oss.model.ObjectMetadata;
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
import com.chengyinhub.common.utils.file.PhotoOwnerTestBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C6:照片必须是「当前登录身份上传」的可验证事实。A 的上传只能由 A 用于首次提交;
 * 他人(B)拒绝且无进度/奖励;历史无归属对象首次提交明确可恢复重传;已完成事实保持幂等。
 * 校验用真实生产校验逻辑 + OSS SDK 替身(getObjectMetadata 边界),不触网。
 */
class PhotoOwnerBindingContractTest
{
    private static final Long PLAYER = 81L;
    private static final Long OTHER = 82L;
    private static final Long ACTIVITY = 83L;
    private static final Long TOPIC = 84L;
    private static final Long NODE = 85L;
    private static final Long TEMPLATE = 86L;
    private static final String BUCKET = "lie-channelwin";
    private static final String PIC_URL = "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/"
            + "0123456789abcdef0123456789abcdef.jpg?Expires=4102444800&OSSAccessKeyId=AK&Signature=sig";

    private IPlayerNodeProgressService progressService;
    private IPointsAwardService pointsAwardService;
    private IShopDayProofService proofService;
    private OSSClient ossClient;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp() throws Exception
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
        proofService = mock(IShopDayProofService.class);
        IPlayerProgressionService progressionService = mock(IPlayerProgressionService.class);
        ITopicCompletionAwardService completionAwardService = mock(ITopicCompletionAwardService.class);

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

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setTemplateId(TEMPLATE);
        node.setSortId(1L);
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setValidationMethod(ValidationMethod.PHOTO);
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        // PLAYER 与 OTHER 都是本场报名者：按查询人返回其本人的报名行(与 member_id 过滤的真实查询一致)，
        // 让「B 用 A 的照片」由照片归属闸判定，而不是借 mock 忽略 member 条件。
        when(registrationService.selectCmsRegistrationList(any())).thenAnswer(call -> {
            CmsRegistration probe = call.getArgument(0);
            CmsRegistration registration = new CmsRegistration();
            registration.setId(probe.getMemberId() == null ? null : 9_100_000L + probe.getMemberId());
            registration.setMemberId(probe.getMemberId() == null ? PLAYER : probe.getMemberId());
            registration.setOwnerType(2L);
            registration.setOwnerId(ACTIVITY);
            registration.setRegistrationStatus(2);
            return Collections.singletonList(registration);
        });

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        when(progressionService.onNodeDone(PLAYER)).thenReturn(Collections.emptyList());
        when(completionAwardService.grantOnce(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new TopicCompletionAwardVO());

        ossClient = mock(OSSClient.class);
        doReturn(ownerMetadata(String.valueOf(PLAYER))).when(ossClient).getObjectMetadata(BUCKET, objectKey());
        doReturn(true).when(ossClient).doesObjectExist(BUCKET, objectKey());
        doReturn(new java.net.URL(PIC_URL)).when(ossClient)
                .generatePresignedUrl(any(com.aliyun.oss.model.GeneratePresignedUrlRequest.class));
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
    }

    private static String objectKey()
    {
        return "upload/0123456789abcdef0123456789abcdef.jpg";
    }

    private static ObjectMetadata ownerMetadata(String memberId)
    {
        ObjectMetadata metadata = new ObjectMetadata();
        if (memberId != null) metadata.addUserMetadata("member-id", memberId);
        metadata.addUserMetadata("biz-type", "image_free");
        return metadata;
    }

    private void installVerifier()
    {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "photoUploadFactVerifier",
                (com.chengyinhub.web.service.play.PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (picUrl, memberId) ->
                        PhotoOwnerTestBridge.verifyForMember(picUrl, ossClient, BUCKET, memberId));
    }

    private AjaxResult submit()
    {
        return BasicPlayCommandFixtures.photo(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), PIC_URL, null);
    }

    private void verifyNoProgressNorReward()
    {
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void ownerCanCompleteWithOwnUploadedPhoto()
    {
        installVerifier();

        AjaxResult result = submit();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        verify(progressService).insertPlayerNodeProgress(any());
    }

    @Test
    void anotherMemberCannotCompleteWithSomeoneElsesPhoto()
    {
        doReturn(OTHER).when(controller).getAppUserId();
        installVerifier();
        doReturn(ownerMetadata(String.valueOf(PLAYER))).when(ossClient).getObjectMetadata(BUCKET, objectKey());

        AjaxResult result = submit();

        assertEquals(500, result.get("code"), "B 不得用 A 的照片完成");
        assertTrue(String.valueOf(result.get("msg")).contains("不是当前账号"),
                String.valueOf(result.get("msg")));
        verifyNoProgressNorReward();
    }

    @Test
    void legacyObjectWithoutOwnerMetadataNeedsExplicitReupload()
    {
        installVerifier();
        doReturn(ownerMetadata(null)).when(ossClient).getObjectMetadata(BUCKET, objectKey());

        AjaxResult first = submit();

        assertEquals(500, first.get("code"));
        assertTrue(String.valueOf(first.get("msg")).contains("重新拍摄上传"),
                String.valueOf(first.get("msg")));
        verifyNoProgressNorReward();

        // 重传后:同一账号的新上传带归属,正常完成(可恢复,不丢权益)
        doReturn(ownerMetadata(String.valueOf(PLAYER))).when(ossClient).getObjectMetadata(BUCKET, objectKey());
        AjaxResult retry = submit();
        assertEquals(200, retry.get("code"), String.valueOf(retry.get("msg")));
        verify(progressService).insertPlayerNodeProgress(any());
    }

    @Test
    void missingObjectIsRejectedAsInvalid()
    {
        installVerifier();
        doThrow(new ServiceException("not found", "NoSuchKey", "rid", "hid"))
                .when(ossClient).getObjectMetadata(BUCKET, objectKey());

        AjaxResult result = submit();

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("不存在"), String.valueOf(result.get("msg")));
        verifyNoProgressNorReward();
    }

    @Test
    void storageOutageIsRecoverableAndRetryKeepsRights()
    {
        installVerifier();
        doThrow(new ClientException("connect timed out"))
                .when(ossClient).getObjectMetadata(BUCKET, objectKey());

        AjaxResult outage = submit();
        assertEquals(503, outage.get("code"));
        assertEquals("照片校验服务暂不可用，请稍后重试", outage.get("msg"));
        verifyNoProgressNorReward();

        doReturn(ownerMetadata(String.valueOf(PLAYER))).when(ossClient).getObjectMetadata(BUCKET, objectKey());
        AjaxResult retry = submit();
        assertEquals(200, retry.get("code"), String.valueOf(retry.get("msg")));
        verify(progressService).insertPlayerNodeProgress(any());
    }

    @Test
    void completedNodeRetryWithLegacyUnboundPhotoStaysIdempotent()
    {
        PlayerNodeProgress existing = new PlayerNodeProgress();
        existing.setId(9001L);
        existing.setStatus(1);
        existing.setPicUrl(PIC_URL);
        when(progressService.insertPlayerNodeProgress(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_progress"));
        when(progressService.selectPlayerNodeProgressList(any())).thenAnswer(invocation -> {
            PlayerNodeProgress probe = invocation.getArgument(0);
            if (probe.getNodeId() != null) return Collections.singletonList(existing);
            PlayerNodeProgress done = new PlayerNodeProgress();
            done.setStatus(1);
            return Collections.singletonList(done);
        });
        doReturn(ownerMetadata(null)).when(ossClient).getObjectMetadata(eq(BUCKET), anyString());
        installVerifier();

        AjaxResult result = submit();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已完成", result.get("msg"));
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }
}
