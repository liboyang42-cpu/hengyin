package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.SensorResultRequest;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.SensorChallengeServiceImpl;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiPlaySensorResultTest {
    private static final long MEMBER = 81L, ACTIVITY = 82L, TOPIC = 83L, NODE = 84L;
    private final ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
    private final ICmsTopicService topicService = mock(ICmsTopicService.class);
    private final ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
    private final IPlayerNodeProgressService progressService = mock(IPlayerNodeProgressService.class);
    private final IPointsAwardService points = mock(IPointsAwardService.class);
    private final IRewardDropService drops = mock(IRewardDropService.class);
    private final ISmsCouponHistoryService coupons = mock(ISmsCouponHistoryService.class);
    private final IPlayerProgressionService progression = mock(IPlayerProgressionService.class);
    private final IGrowthService growth = mock(IGrowthService.class);
    private final ITopicCompletionAwardService completion = mock(ITopicCompletionAwardService.class);
    private final IShopDayProofService proof = mock(IShopDayProofService.class);
    private final RedisCache redis = mock(RedisCache.class);
    private final com.chengyinhub.business.mapper.MemberCollectibleMapper memberCollectibles =
            mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class);
    private ApiPlayProgressController controller;
    private CmsTopic topic;
    private CmsActivity activity;
    private CmsTopicNode node;
    private CmsMemberTemplate template;
    private ICmsRegistrationService registrations;
    private com.chengyinhub.business.mapper.ClubLeadMapper clubLeadMapper;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiPlayProgressController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ICmsActivityService activities = mock(ICmsActivityService.class);
        registrations = mock(ICmsRegistrationService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activities);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", points);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", drops);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", coupons);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progression);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", growth);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completion);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", proof);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl(
                        mock(ICmsRegistrationMerchantService.class)));
        SensorChallengeServiceImpl sensorChallenges = new SensorChallengeServiceImpl();
        ReflectionTestUtils.setField(sensorChallenges, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "sensorChallengeService", sensorChallenges);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", mock(IXpBudgetService.class));
        clubLeadMapper = mock(com.chengyinhub.business.mapper.ClubLeadMapper.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper", clubLeadMapper);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper", memberCollectibles);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));

        node = new CmsTopicNode();
        node.setId(NODE); node.setTopicId(TOPIC); node.setTemplateId(85L); node.setSortId(1L); node.setStatus(1L);
        node.setFragmentText("不应由 vm7 发放的收集物");
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        template = new CmsMemberTemplate();
        template.setId(85L); template.setValidationMethod(ValidationMethod.APP_SENSOR);
        template.setSensorType("still"); template.setSensorConfig("{\"durationSec\":10}");
        when(templateService.selectCmsMemberTemplateById(85L)).thenReturn(template);

        activity = new CmsActivity(); activity.setId(ACTIVITY); activity.setTopicId(TOPIC);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        CmsRegistration registration = new CmsRegistration();
        registration.setMemberId(MEMBER); registration.setOwnerType(2L); registration.setOwnerId(ACTIVITY);
        registration.setRegistrationStatus(2);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
        topic = new CmsTopic(); topic.setId(TOPIC); topic.setName("传感器挑战");
        topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(redis.setIfAbsent(anyString(), any(), eq(2), any())).thenReturn(true);
        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenAnswer(invocation -> {
            PlayerNodeProgress probe = invocation.getArgument(0);
            if (probe.getNodeId() != null) return Collections.emptyList();
            PlayerNodeProgress done = new PlayerNodeProgress(); done.setStatus(1);
            return Collections.singletonList(done);
        });
        when(progression.onNodeDone(MEMBER)).thenReturn(Collections.emptyList());
        when(completion.grantOnce(MEMBER, TOPIC, ACTIVITY, null, RewardPolicy.XP_BADGE_ONLY))
                .thenReturn(new TopicCompletionAwardVO());
        when(completion.grantOnce(MEMBER, TOPIC, ACTIVITY, null))
                .thenReturn(new TopicCompletionAwardVO());
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
    }

    @Test
    void vm7WithoutTrustedProofCannotCompleteOrGrantRewards() {
        AjaxResult result = BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12)));
        assertEquals(500, result.get("code"));
        assertEquals("传感器可信证明缺失，暂不能完成挑战", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
        verify(progression, never()).onNodeDone(anyLong());
        verify(completion, never()).grantOnce(anyLong(), anyLong(), anyLong(), any(), any(RewardPolicy.class));
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(drops, never()).rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(coupons, never()).grantCouponById(anyLong(), anyLong());
        verify(memberCollectibles, never()).insertMemberCollectible(any());
    }

    @Test
    void sensorResultCannotBypassNodeUnlock() {
        node.setUnlockAfterNodeId(NODE - 1);

        AjaxResult result = BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12)));

        assertEquals(500, result.get("code"));
        assertEquals("本关尚未解锁,先完成前置关卡", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void originalUnboundSensorStillUsesOriginalConfigAndRefusesUntrustedCompletion() {
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);
        PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",route);
        when(route.canPlay(eq(MEMBER),eq(TOPIC),eq(ACTIVITY),eq(NODE),nullable(String.class))).thenReturn(true);
        var content=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        content.put("contentSnapshotVersion",1).put("templateId",85L).put("validationMethod",ValidationMethod.APP_SENSOR)
                .put("sensorType","still").put("sensorConfig","{\"durationSec\":10}");
        content.putObject("nodePresentation").put("id",NODE).put("topicId",TOPIC).put("templateId",85L);
        content.putObject("templatePresentation");
        // 写命令只消费入口锁定的原局快照(PlayNodeContext#frozenAnswer),不再走实时 nodeAnswers 读口
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,0,content);
        node.setTemplateId(null);template.setSensorType("later-author-type");
        AjaxResult result=BasicPlayCommandFixtures.sensorResult(controller, request("still",payload("heldSec",12)));
        assertEquals(500,result.get("code"));assertEquals("传感器可信证明缺失，暂不能完成挑战",result.get("msg"));
        verify(templateService,never()).selectCmsMemberTemplateById(any());
        verify(progressService,never()).insertPlayerNodeProgress(any());
        verify(points,never()).award(anyLong(),anyLong(),anyLong(),anyString());
        verify(growth,never()).addExp(anyLong(),anyInt(),anyString());
    }

    @Test
    void filterShotPhotoGrantsXpAndBadgesOnly() {
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"night_vision\"}");
        allowVerifiedUpload();

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "filter-shot.png", null);

        assertEquals(200, result.get("code"));
        verify(growth).addExp(eq(MEMBER), anyInt(), eq("节点探索"));
        verify(progression).onNodeDone(MEMBER);
        verify(completion).grantOnce(MEMBER, TOPIC, ACTIVITY, null, RewardPolicy.XP_BADGE_ONLY);
        verify(completion, never()).grantOnce(MEMBER, TOPIC, ACTIVITY, null);
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(drops, never()).rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(coupons, never()).grantCouponById(anyLong(), anyLong());
        verify(memberCollectibles, never()).insertMemberCollectible(any());
    }

    @Test
    void freeCollectFilterShotRequiresArrivalAndCompletesWithXpOnly() {
        topic.setProductType(PlayMode.FREE_COLLECT);
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"night_vision\"}");
        PlayerNodeProgress arrived = new PlayerNodeProgress();
        // markGameDone 已把该行 0→1(DuplicateKey 分支 rows=1):夹具必须返回升级后的状态,
        // 否则按 status==1 的正式完成口径会数成 0 行(单独到店没有游戏完成不算通关)。
        arrived.setStatus(1);
        arrived.setPicUrl("filter-shot.png");
        when(proof.hasArrived(MEMBER, ACTIVITY, NODE)).thenReturn(true);
        when(proof.submitPhoto(MEMBER, ACTIVITY, NODE, "filter-shot.png")).thenReturn(arrived);
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(new DuplicateKeyException("uk_progress"));
        doReturn(Collections.singletonList(arrived)).when(progressService).selectPlayerNodeProgressList(any());
        when(proof.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE)).thenReturn(true);
        allowVerifiedUpload();

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "filter-shot.png", null);

        assertEquals(200, result.get("code"));
        verify(proof).hasArrived(MEMBER, ACTIVITY, NODE);
        verify(proof).submitPhoto(MEMBER, ACTIVITY, NODE, "filter-shot.png");
        verify(proof).markGameDone(MEMBER, ACTIVITY, TOPIC, NODE);
        verify(growth).addExp(eq(MEMBER), anyInt(), eq("节点探索"));
        verify(progression).onNodeDone(MEMBER);
        verify(completion).grantOnce(MEMBER, TOPIC, ACTIVITY, null, RewardPolicy.XP_BADGE_ONLY);
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(drops, never()).rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(coupons, never()).grantCouponById(anyLong(), anyLong());
        verify(memberCollectibles, never()).insertMemberCollectible(any());
    }

    @Test
    void freeCollectDoneCountIgnoresArrivedOnlyRowsSoNoPrematureTopicCompletion() {
        topic.setProductType(PlayMode.FREE_COLLECT);
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"night_vision\"}");
        CmsTopicNode other = new CmsTopicNode();
        other.setId(NODE + 1);
        other.setTopicId(TOPIC);
        other.setStatus(1L);
        other.setSortId(2L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(node, other));
        PlayerNodeProgress doneRow = new PlayerNodeProgress();
        doneRow.setId(9001L);
        doneRow.setStatus(1);
        doneRow.setNodeId(NODE);
        PlayerNodeProgress arrivedOnly = new PlayerNodeProgress();
        arrivedOnly.setId(9002L);
        arrivedOnly.setStatus(0);
        arrivedOnly.setNodeId(NODE + 1);
        when(proof.hasArrived(MEMBER, ACTIVITY, NODE)).thenReturn(true);
        when(proof.submitPhoto(MEMBER, ACTIVITY, NODE, "filter-shot.png")).thenReturn(doneRow);
        when(proof.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE)).thenReturn(true);
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(new DuplicateKeyException("uk_progress"));
        doReturn(Arrays.asList(doneRow, arrivedOnly)).when(progressService).selectPlayerNodeProgressList(any());
        allowVerifiedUpload(); // 此用例验证完成计数，上传真实性由独立来源校验用例覆盖。

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "filter-shot.png", null);

        assertEquals(200, result.get("code"));
        // 另一站只有到店凭证(status=0):完成数只能是 1,不得提前判通关并发通关奖
        verify(completion, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
        verify(completion, never()).grantOnce(anyLong(), anyLong(), anyLong(), any(), any(RewardPolicy.class));
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void freeCollectFilterShotCannotRunBeforeArrival() {
        topic.setProductType(PlayMode.FREE_COLLECT);
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"pet_pov\"}");
        when(proof.hasArrived(MEMBER, ACTIVITY, NODE)).thenReturn(false);

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "filter-shot.png", null);

        assertEquals(500, result.get("code"));
        assertEquals("先扫店内静态码进店，再开始任务", result.get("msg"));
        verify(proof, never()).submitPhoto(anyLong(), anyLong(), anyLong(), anyString());
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void freeCollectFilterShotCannotBypassNodeUnlock() {
        topic.setProductType(PlayMode.FREE_COLLECT);
        node.setUnlockAfterNodeId(NODE - 1);
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"night_vision\"}");
        when(proof.hasArrived(MEMBER, ACTIVITY, NODE)).thenReturn(true);

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "filter-shot.png", null);

        assertEquals(500, result.get("code"));
        assertEquals("本关尚未解锁,先完成前置关卡", result.get("msg"));
        verify(proof, never()).submitPhoto(anyLong(), anyLong(), anyLong(), anyString());
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void freeCollectFilterShotCannotBypassLeadGate() {
        topic.setProductType(PlayMode.FREE_COLLECT);
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"pet_pov\"}");
        ClubLeadState gathering = new ClubLeadState();
        gathering.setStatus(1);
        when(clubLeadMapper.selectByActivity(ACTIVITY)).thenReturn(gathering);

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "filter-shot.png", null);

        assertEquals(500, result.get("code"));
        assertEquals("队伍集合中,等队长开始行程", result.get("msg"));
        verify(proof, never()).submitPhoto(anyLong(), anyLong(), anyLong(), anyString());
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void ordinaryPhotoKeepsFullRewardPolicy() {
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType(null);
        template.setSensorConfig(null);
        Map<String, Object> couponDrop = new HashMap<>();
        couponDrop.put("dropType", 3);
        couponDrop.put("couponTemplateId", 909L);
        when(drops.rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(couponDrop);
        when(coupons.grantCouponById(MEMBER, 909L)).thenReturn(true);
        allowVerifiedUpload();

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "ordinary-photo.png", null);

        assertEquals(200, result.get("code"));
        verify(points).award(MEMBER, 12L, NODE, "节点打卡");
        verify(drops).rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(coupons).grantCouponById(MEMBER, 909L);
        verify(progression).onNodeDone(MEMBER);
        verify(completion).grantOnce(MEMBER, TOPIC, ACTIVITY, null);
        verify(completion, never()).grantOnce(
                MEMBER, TOPIC, ACTIVITY, null, RewardPolicy.XP_BADGE_ONLY);
    }

    @Test
    void photoMustProveUploadedObjectFactBeforeCompleting() {
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType(null);
        template.setSensorConfig(null);
        rejectUploadAsInvalid();

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "not-an-uploaded-photo", null);

        assertEquals(500, result.get("code"));
        assertEquals("照片上传凭证无效，请重新拍摄上传", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(progression, never()).onNodeDone(anyLong());
    }

    @Test
    void storeVoucherMustProveUploadedObjectFactBeforeRecording() {
        topic.setProductType(PlayMode.FREE_COLLECT);
        template.setValidationMethod(3);
        template.setSensorType(null);
        template.setSensorConfig(null);
        when(proof.hasArrived(MEMBER, ACTIVITY, NODE)).thenReturn(true);
        rejectUploadAsInvalid();

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "not-an-uploaded-photo", null);

        assertEquals(500, result.get("code"));
        verify(proof, never()).submitPhoto(anyLong(), anyLong(), anyLong(), anyString());
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void verifiedUploadStoresPictureAndReviewSlotOnOrdinaryPhoto() {
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType(null);
        template.setSensorConfig(null);
        allowVerifiedUpload();
        String picUrl = "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/"
                + "0123456789abcdef0123456789abcdef.jpg?Expires=4102444800&OSSAccessKeyId=k&Signature=s";

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), picUrl, null);

        assertEquals(200, result.get("code"));
        org.mockito.ArgumentCaptor<PlayerNodeProgress> inserted =
                org.mockito.ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).insertPlayerNodeProgress(inserted.capture());
        assertEquals(picUrl, inserted.getValue().getPicUrl());
        // 2026-09-17 拍照一律机审:首写即为机审通过(1),与 markPhotoMachineApproved 同口径;
        // 0 会被定向结算 countPendingProgress 当成待审而永远挡住结算。
        assertEquals(Integer.valueOf(1), inserted.getValue().getReviewStatus());
        verify(points).award(MEMBER, 12L, NODE, "节点打卡");
    }

    @Test
    void storageUnavailablePhotoVerificationIsRecoverableAndRetryKeepsRights() {
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType(null);
        template.setSensorConfig(null);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "photoUploadFactVerifier",
                (com.chengyinhub.web.service.play.PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (picUrl, memberId) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("对象存储查询失败");
                    }
                });
        String picUrl = "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/"
                + "0123456789abcdef0123456789abcdef.jpg?Expires=4102444800&OSSAccessKeyId=k&Signature=s";

        AjaxResult first = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), picUrl, null);
        assertEquals(503, first.get("code"));
        assertEquals("照片校验服务暂不可用，请稍后重试", first.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());

        AjaxResult retry = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), picUrl, null);
        assertEquals(200, retry.get("code"));
        verify(progressService).insertPlayerNodeProgress(any());
        verify(points).award(MEMBER, 12L, NODE, "节点打卡");
    }

    @Test
    void verifiedStoreVoucherStillRecordsProof() {
        topic.setProductType(PlayMode.FREE_COLLECT);
        template.setValidationMethod(3);
        template.setSensorType(null);
        template.setSensorConfig(null);
        when(proof.hasArrived(MEMBER, ACTIVITY, NODE)).thenReturn(true);
        PlayerNodeProgress proofRow = new PlayerNodeProgress();
        proofRow.setStatus(0);
        proofRow.setPicUrl("stored-proof");
        when(proof.submitPhoto(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(proofRow);
        allowVerifiedUpload();
        String picUrl = "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/"
                + "0123456789abcdef0123456789abcdef.jpg?Expires=4102444800&OSSAccessKeyId=k&Signature=s";

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), picUrl, null);

        assertEquals(200, result.get("code"));
        assertEquals("到店照片已记录", result.get("msg"));
        verify(proof).submitPhoto(MEMBER, ACTIVITY, NODE, picUrl);
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void photoCannotCompleteANodeWithAnotherValidationMethod() {
        for (Integer method : new Integer[] {null, 0, 1, 3, 4, 5, 6, 8, 9, 10, 99}) {
            template.setValidationMethod(method);
            template.setSensorType(null);
            AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                    String.valueOf(ACTIVITY), String.valueOf(NODE), "photo.png", null);
            assertEquals(500, result.get("code"), "validationMethod=" + method);
        }
        verifyNoInteractions(progressService, points, drops, coupons, progression, completion, proof);
    }

    @Test
    void photoCannotCompleteANodeWithoutATemplate() {
        node.setTemplateId(null);
        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "photo.png", null);
        assertEquals(500, result.get("code"));
        verifyNoInteractions(progressService, points, drops, coupons, progression, completion, proof);
    }

    @Test
    void filterShotCannotBypassPhotoThroughCheckin() {
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"pet_pov\"}");

        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), "NODE-CODE", null, null, null, null, String.valueOf(NODE));

        assertEquals(500, result.get("code"));
        assertEquals("该站需通过 App 滤镜相机完成", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void filterShotWithInvalidPersistedConfigFailsClosedAtRuntime() {
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"unknown\"}");

        AjaxResult result = BasicPlayCommandFixtures.photo(controller,
                String.valueOf(ACTIVITY), String.valueOf(NODE), "filter-shot.png", null);

        assertEquals(500, result.get("code"));
        assertEquals("滤镜样式无效", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void filterShotCannotUseSensorResult() {
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"night_vision\"}");

        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("filter_shot", payload("heldSec", 12))).get("code"));
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void mode2RequiresExistingArrivalFact() {
        topic.setProductType(PlayMode.FREE_COLLECT);
        node.setMerchantMemberId(MEMBER + 1);
        when(proof.hasArrived(MEMBER, ACTIVITY, NODE)).thenReturn(false);
        AjaxResult result = BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12)));
        assertEquals(500, result.get("code"));
        assertEquals("先扫店内静态码进店，再开始任务", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void contextsAndConfiguredThresholdFailClosed() {
        SensorResultRequest both = request("still", payload("heldSec", 12));
        both.setTopicId(TOPIC);
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, both).get("code"));
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 9))).get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void missingOrWrongRuntimeSensorConfigFailsClosed() {
        template.setSensorConfig("{}");
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));
        template.setSensorConfig("{\"durationSec\":\"10\"}");
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void stepsRequiresPositiveDurationSec() {
        template.setSensorType("steps");
        template.setSensorConfig("{\"targetSteps\":100,\"windowSec\":300}");

        Map<String, Object> missing = payload("steps", 100);
        Map<String, Object> zero = payload("steps", 100);
        zero.put("durationSec", 0);

        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("steps", missing)).get("code"));
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("steps", zero)).get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void stepsDurationCannotExceedConfiguredWindow() {
        template.setSensorType("steps");
        template.setSensorConfig("{\"targetSteps\":100,\"windowSec\":300}");
        Map<String, Object> payload = payload("steps", 100);
        payload.put("durationSec", 301);

        AjaxResult result = BasicPlayCommandFixtures.sensorResult(controller, request("steps", payload));

        assertEquals(500, result.get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void validSelfReportedStepsStillRequireTrustedProof() {
        template.setSensorType("steps");
        template.setSensorConfig("{\"targetSteps\":100,\"windowSec\":300}");
        Map<String, Object> payload = payload("steps", 100);
        payload.put("durationSec", 300);

        AjaxResult result = BasicPlayCommandFixtures.sensorResult(controller, request("steps", payload));

        assertEquals(500, result.get("code"));
        assertEquals("传感器可信证明缺失，暂不能完成挑战", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void contextVmRegistrationOwnershipTimeAndPredecessorGatesFailClosed() {
        SensorResultRequest missing = request("still", payload("heldSec", 12));
        missing.setActivityId(null);
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, missing).get("code"));

        template.setValidationMethod(3);
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));
        template.setValidationMethod(ValidationMethod.APP_SENSOR);

        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));
        CmsRegistration registration = new CmsRegistration();
        registration.setMemberId(MEMBER); registration.setOwnerType(2L); registration.setOwnerId(ACTIVITY);
        registration.setRegistrationStatus(2);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));

        activity.setTopicId(TOPIC + 1);
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));
        activity.setTopicId(TOPIC);

        activity.setStartDate(new java.util.Date(System.currentTimeMillis() + 60000));
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));
        activity.setStartDate(null);

        CmsTopicNode previous = new CmsTopicNode(); previous.setId(NODE - 1); previous.setSortId(1L);
        node.setSortId(2L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(java.util.Arrays.asList(previous, node));
        doReturn(Collections.emptyList()).when(progressService).selectPlayerNodeProgressList(any());
        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));

        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void completedDuplicateBypassesFrequencyLockAndReturnsIdempotently() {
        PlayerNodeProgress done = new PlayerNodeProgress(); done.setStatus(1);
        doReturn(Collections.singletonList(done)).when(progressService).selectPlayerNodeProgressList(any());
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(new DuplicateKeyException("uk_progress"));

        AjaxResult result = BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12)));

        assertEquals(200, result.get("code"));
        verify(redis, never()).setIfAbsent(anyString(), any(), anyInt(), any());
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void uncompletedReplayCannotBypassTrustedProofWithRateWindowState() {
        when(redis.setIfAbsent(anyString(), any(), eq(2), any())).thenReturn(false);
        when(redis.getCacheObject(anyString())).thenReturn("still|12");
        AjaxResult result = BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12)));

        assertEquals(500, result.get("code"));
        assertEquals("传感器可信证明缺失，暂不能完成挑战", result.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void differentPayloadInsideRateWindowIsRejected() {
        when(redis.setIfAbsent(anyString(), any(), eq(2), any())).thenReturn(false);
        when(redis.getCacheObject(anyString())).thenReturn("still|11");

        assertEquals(500, BasicPlayCommandFixtures.sensorResult(controller, request("still", payload("heldSec", 12))).get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    void vm7CannotBypassSensorValidationThroughLegacyCompletionEndpoints() {
        assertEquals(500, BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), "NODE-CODE", null, null, null, null, String.valueOf(NODE)).get("code"));
        assertEquals(500, BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null).get("code"));
        assertEquals(500, BasicPlayCommandFixtures.photo(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "photo.jpg", null).get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    /** 控制器合同测试只关心“校验通过后是否推进”;真实校验逻辑由 FileOssUploadUtils 专属测试覆盖。 */
    private void allowVerifiedUpload() {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "photoUploadFactVerifier",
                (com.chengyinhub.web.service.play.PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (picUrl, memberId) -> { });
    }

    private void rejectUploadAsInvalid() {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "photoUploadFactVerifier",
                (com.chengyinhub.web.service.play.PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (picUrl, memberId) -> {
                    throw new IllegalArgumentException("照片上传凭证无效，请重新拍摄上传");
                });
    }

    private SensorResultRequest request(String type, Map<String, Object> payload) {
        SensorResultRequest request = new SensorResultRequest();
        request.setActivityId(ACTIVITY); request.setNodeId(NODE); request.setSensorType(type); request.setPayload(payload);
        return request;
    }

    private Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new HashMap<>(); payload.put(key, value); return payload;
    }
}
