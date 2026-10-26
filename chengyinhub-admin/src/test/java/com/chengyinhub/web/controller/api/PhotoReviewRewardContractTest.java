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
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 2026-09-17 用户拍板(第16条):拍照节点不再有人工审核开关,一律机审。
 *
 * <p>契约:
 * <ul>
 *   <li>模板遗留 photo_review=1 不再产生任何「待审」语义:提交即完成、发奖一次;</li>
 *   <li>人工审核时代落下的存量待审行(status=0)重新提交时按机审通过转正,不卡「审核中」;</li>
 *   <li>已完成行重试幂等,不重复发奖;</li>
 *   <li>/nodes 不再下发 photoReview/reviewStatus;</li>
 *   <li>唯一完成裁判仍是 DB 条件更新({@code markPhotoMachineApproved} 返回 1 才发奖),
 *       不是应用层先查后写。真库 CAS 语义另由
 *       {@code PhotoReviewProgressMybatisIntegrationTest} 用 MySQL scratch 验证。</li>
 * </ul>
 */
class PhotoReviewRewardContractTest
{
    private static final Long PLAYER = 81L;
    private static final Long REVIEWER = 88L;
    private static final Long ACTIVITY = 83L;
    private static final Long TOPIC = 84L;
    private static final Long NODE = 85L;
    private static final Long DOWNSTREAM_NODE = 87L;
    private static final Long TEMPLATE = 86L;
    private static final Long PROGRESS = 901L;
    private static final String PIC_URL = "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/"
            + "0123456789abcdef0123456789abcdef.jpg?Expires=4102444800";

    private ICmsTopicNodeService nodeService;
    private ICmsMemberTemplateService templateService;
    private IPlayerNodeProgressService progressService;
    private IPointsAwardService pointsAwardService;
    private IGrowthService growthService;
    private IPlayerProgressionService progressionService;
    private ICmsTopicChapterService chapterService;
    private ApiPlayProgressController controller;
    private CmsMemberTemplate template;
    private CmsTopicNode node;
    private CmsTopicNode downstreamNode;

    private PlayerNodeProgress nodeRow;
    private final List<PlayerNodeProgress> doneRows = new ArrayList<>();

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();

        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        templateService = mock(ICmsMemberTemplateService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        growthService = mock(IGrowthService.class);
        progressionService = mock(IPlayerProgressionService.class);
        ITopicCompletionAwardService completionAwardService = mock(ITopicCompletionAwardService.class);

        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", pointsAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", growthService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progressionService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completionAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", mock(IRewardDropService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", mock(ISmsCouponHistoryService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", mock(IXpBudgetService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper",
                mock(com.chengyinhub.business.mapper.ClubLeadMapper.class));
        var fixtureHintSettings = mock(ICmsPointsSettingService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService",
                mock(com.chengyinhub.business.service.IPuzzlePlayService.class));
        chapterService = mock(ICmsTopicChapterService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", chapterService);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new NodeMerchantOverrideServiceImpl(
                        mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));
        // 拍照来源校验在本类里不重测(PhotoOwnerBindingContractTest 已钉),放行到业务链
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "photoUploadFactVerifier",
                (com.chengyinhub.web.service.play.PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (picUrl, memberId) -> { });

        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setTemplateId(TEMPLATE);
        node.setSortId(1L);
        node.setStatus(1L);

        downstreamNode = new CmsTopicNode();
        downstreamNode.setId(DOWNSTREAM_NODE);
        downstreamNode.setTopicId(TOPIC);
        downstreamNode.setSortId(2L);
        downstreamNode.setStatus(1L);
        downstreamNode.setUnlockAfterNodeId(NODE);

        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeById(DOWNSTREAM_NODE)).thenReturn(downstreamNode);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(node, downstreamNode));

        template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setMemberId(REVIEWER);
        template.setValidationMethod(ValidationMethod.PHOTO);
        template.setPhotoReview(1);   // 存量遗留:开关开着也必须被忽略
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

        when(progressService.selectPlayerNodeProgressList(any())).thenAnswer(invocation -> {
            PlayerNodeProgress probe = invocation.getArgument(0);
            if (probe.getNodeId() != null) {
                return nodeRow == null ? Collections.emptyList() : Collections.singletonList(nodeRow);
            }
            return doneRows;
        });
        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        when(progressionService.onNodeDone(PLAYER)).thenReturn(Collections.emptyList());
        when(completionAwardService.grantOnce(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new TopicCompletionAwardVO());
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    private AjaxResult submit()
    {
        return BasicPlayCommandFixtures.photo(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), PIC_URL, null);
    }

    private PlayerNodeProgress row(int status, int reviewStatus)
    {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setId(PROGRESS);
        p.setMemberId(PLAYER);
        p.setActivityId(ACTIVITY);
        p.setTopicId(TOPIC);
        p.setNodeId(NODE);
        p.setStatus(status);
        p.setReviewStatus(reviewStatus);
        p.setPicUrl(PIC_URL);
        p.setCheckinTime(new Date());
        p.setDelFlag(0);
        return p;
    }

    private static PlayerNodeProgress capturedInsert(IPlayerNodeProgressService service)
    {
        ArgumentCaptor<PlayerNodeProgress> captor = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(service).insertPlayerNodeProgress(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(AjaxResult result)
    {
        return (Map<String, Object>) result.get("data");
    }

    // ── 遗留 photo_review=1:一律机审 ─────────────────────────

    @Test
    void legacyPhotoReviewFlagIsIgnoredAndSubmissionCompletesWithReward()
    {
        AjaxResult result = submit();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("已提交,打卡成功", result.get("msg"));
        PlayerNodeProgress inserted = capturedInsert(progressService);
        assertEquals(Integer.valueOf(1), inserted.getStatus(), "开关开着也必须提交即完成");
        assertEquals(Integer.valueOf(1), inserted.getReviewStatus(), "既有机审完成事实必须可被计分读取，不能持久化为待审");
        assertEquals(PIC_URL, inserted.getPicUrl());
        verify(pointsAwardService, times(1)).award(PLAYER, 12L, NODE, "节点打卡");
        verify(growthService, times(1)).addExp(PLAYER, 12, "节点探索");
        assertNull(data(result).get("pendingReview"), "人工审核下线后不存在待审语义");
        verify(progressService, never()).markPhotoMachineApproved(anyLong());
    }

    // ── 存量待审/驳回行:重新提交按机审转正 ────────────────────

    @Test
    void legacyPendingRowIsMachineApprovedOnResubmitAndRewardsOnce()
    {
        nodeRow = row(0, 0);                       // 人工审核时代落下的待审行
        doneRows.add(nodeRow);
        when(progressService.insertPlayerNodeProgress(any()))
                .thenThrow(new DuplicateKeyException("uk_progress"));
        when(progressService.markPhotoMachineApproved(PROGRESS)).thenReturn(1);

        AjaxResult result = submit();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("已提交,打卡成功", result.get("msg"));
        assertEquals(Boolean.TRUE, data(result).get("firstTime"), "转正必须走完成链");
        verify(progressService, times(1)).markPhotoMachineApproved(PROGRESS);
        verify(pointsAwardService, times(1)).award(PLAYER, 12L, NODE, "节点打卡");
        verify(growthService, times(1)).addExp(PLAYER, 12, "节点探索");
    }

    @Test
    void legacyRejectedRowIsAlsoMachineApprovedOnResubmit()
    {
        nodeRow = row(0, 2);                       // 驳回态(谓词不再看 review_status)
        doneRows.add(nodeRow);
        when(progressService.insertPlayerNodeProgress(any()))
                .thenThrow(new DuplicateKeyException("uk_progress"));
        when(progressService.markPhotoMachineApproved(PROGRESS)).thenReturn(1);

        AjaxResult result = submit();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals(Boolean.TRUE, data(result).get("firstTime"));
        verify(pointsAwardService, times(1)).award(PLAYER, 12L, NODE, "节点打卡");
    }

    @Test
    void completedRowResubmitIsIdempotentAndDoesNotDoubleReward()
    {
        nodeRow = row(1, 0);                       // 已完成的存量行
        when(progressService.insertPlayerNodeProgress(any()))
                .thenThrow(new DuplicateKeyException("uk_progress"));

        AjaxResult result = submit();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertEquals("该节点已完成", result.get("msg"));
        verify(progressService, never()).markPhotoMachineApproved(anyLong());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void concurrentResubmitLosingTheCasDoesNotReward()
    {
        nodeRow = row(0, 0);
        doneRows.add(nodeRow);
        when(progressService.insertPlayerNodeProgress(any()))
                .thenThrow(new DuplicateKeyException("uk_progress"));
        when(progressService.markPhotoMachineApproved(PROGRESS)).thenReturn(0); // CAS 输给并发

        AjaxResult result = submit();

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        assertFalse(Boolean.TRUE.equals(data(result).get("firstTime")), "CAS 没命中就不许发奖");
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    // ── /nodes 投影:不再下发审核态 ───────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void nodesProjectionNoLongerExposesPhotoReviewState()
    {
        nodeRow = row(0, 0);
        doneRows.add(nodeRow);

        AjaxResult result = controller.nodes(ACTIVITY, null);

        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        Map<String, Object> payload = data(result);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) payload.get("nodes");
        Map<String, Object> pending = null;
        Map<String, Object> downstream = null;
        for (Map<String, Object> n : nodes) {
            if (NODE.equals(n.get("nodeId"))) pending = n;
            if (DOWNSTREAM_NODE.equals(n.get("nodeId"))) downstream = n;
        }
        assertNotNull(pending);
        assertNotNull(downstream);
        assertFalse(pending.containsKey("photoReview"), "人工审核下线后不再下发开关");
        assertFalse(pending.containsKey("reviewStatus"), "不再下发审核态");
        assertEquals(Boolean.FALSE, pending.get("done"), "待审行不是完成");
        assertEquals(Boolean.TRUE, downstream.get("locked"));
        assertEquals(0L, ((Number) payload.get("doneCount")).longValue());
    }
}
