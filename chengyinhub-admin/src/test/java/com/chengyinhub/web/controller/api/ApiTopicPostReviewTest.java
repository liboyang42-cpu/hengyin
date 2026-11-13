package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-P1(2026-10-20 分级审核):主题创建/编辑先发后审。
 * <ul>
 *   <li>机审建议人工 → 保存,但 Round5B-3 起下架留置并挂复核上架单(不再「照常发布 + 抽检单」);</li>
 *   <li>违规 → 拒绝保存,统一文案;</li>
 *   <li>干净文本 → 发布,不建抽检单(简易版行为不变);</li>
 *   <li>编辑只复检改动过的文本:只改价/票不重跑机审,改了故事才送检。</li>
 * </ul>
 */
class ApiTopicPostReviewTest {

    private static final long MEMBER = 901L;
    private static final long TOPIC = 7001L;

    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private IWxContentSecurityService security;
    private IAuditTaskService auditTaskService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        security = mock(IWxContentSecurityService.class);
        doReturn(MEMBER).when(controller).getAppUserId();
        doReturn("publisher901").when(controller).getAppUserName();
        IQuotaService quotaService = mock(IQuotaService.class);
        when(quotaService.checkCreate(anyLong(), anyString())).thenReturn(null);
        when(quotaService.checkNodesPerTheme(anyLong(), anyInt())).thenReturn(null);
        // 简易版免审直发走在架额度 guard:这里直接执行写库动作
        when(quotaService.runWithThemeOnlineGuard(anyLong(), any(), any())).thenAnswer(
                call -> ((java.util.function.Supplier<?>) call.getArgument(2)).get());
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        when(security.checkText(contains("待复核"), any())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);
        // 2026-11-03 集成:l3 P-17 起发布必带封面,l8 Round5C 对「图片送不了检」的主题留置复检。
        // 本类只测文本机审分支,封面按「可以送检」造;图片送不了检的留置由 l8 自己的用例覆盖。
        when(security.canSubmitMedia(any())).thenReturn(true);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "memberIdentityService",
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(controller, "merchantAccessService", mock(IMerchantAccessService.class));
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", mock(ICmsTopicNodeService.class));
        auditTaskService = TopicContentSafetyTestFixtures.wire(controller, security);
        when(topicService.createTopic(any(), anyLong(), anyString())).thenReturn(TOPIC);
        when(topicService.updateTopicByOwner(any(), anyLong(), anyString())).thenReturn(TOPIC);
    }

    private TopicCreateDTO createDto(String name) {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setImgUrl("media-asset:1"); // P-17:发布必须带封面
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("simple");
        dto.setName(name);
        return dto;
    }

    /** Round5B-3:机审要人工的主题照常保存,但随即下架留置并送人工复核,不再先公开。 */
    @Test
    void reviewSuggestedText_isSavedButHeldForHumanReview() {
        AjaxResult result = controller.createTopic(createDto("待复核的主题名"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(topicService).createTopic(any(), eq(MEMBER), anyString());
        verify(topicService).holdUncheckedTopicForReview(TOPIC, MEMBER);
        verify(auditTaskService, never()).submit(eq(AuditTask.BIZ_TOPIC), eq(TOPIC), eq(MEMBER), eq("post_review_flag"),
                anyString());
        // 第十一轮 h5 第 4 项(j5 H2):留置的主题回执说「已提交审核」,不说「主题发布成功」
        assertEquals("已提交审核", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void cleanPublishStillSaysPublished() {
        AjaxResult result = controller.createTopic(createDto("正常主题名"));

        assertEquals("主题发布成功", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void heldProReceipt_isMarkedPendingNotPublished() {
        com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO receipt =
                new com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO(TOPIC, 9L, "NOT_REQUIRED", java.util.Collections.<Long>emptyList());
        receipt.setPublished(true);
        receipt.markHeldForReview();
        assertEquals(false, receipt.isPublished());
        assertEquals("PENDING", receipt.getReviewState());
    }

    @Test
    void cleanPublishLeavesReviewFlagAlone() {
        controller.createTopic(createDto("正常主题名"));

        verify(topicService, never()).markReviewFlag(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(topicService, never()).holdUncheckedTopicForReview(anyLong(), any());
    }

    @Test
    void riskyText_isRefused() {
        AjaxResult result = controller.createTopic(createDto("违规主题名"));

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("主题内容包含违规信息"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(topicService, never()).createTopic(any(), anyLong(), anyString());
    }

    @Test
    void simpleTopicWithCleanText_publishesWithoutAnyAuditTask() {
        AjaxResult result = controller.createTopic(createDto("正常主题名"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    /** 草稿同样走留置(service 内对草稿只留置不挂单,发布草稿时由留置分支挂复核单)。 */
    @Test
    void flaggedDraft_isNotQueuedForSpotCheck() {
        when(topicService.selectTopicIsDraft(TOPIC)).thenReturn(1);
        TopicCreateDTO dto = createDto("待复核的主题名");
        dto.setSaveDraft(1);

        AjaxResult result = controller.createTopic(dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), any());
        verify(topicService).holdUncheckedTopicForReview(TOPIC, MEMBER);
    }

    private CmsTopic saved() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(MEMBER);
        topic.setName("正常主题名");
        topic.setImgUrl("media-asset:1"); // D-78:编辑保存也必须有封面
        topic.setDescription("正常简介");
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"原开场白\"}}");
        return topic;
    }

    @Test
    void editWithoutTextChange_doesNotRerunMachineCheck() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(saved());
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("正常主题名");
        dto.setDescription("正常简介");
        dto.setJourneyStory("{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"原开场白\"}}");
        dto.setSelfPlayPrice(new java.math.BigDecimal("19.9"));

        AjaxResult result = controller.updateTopic(dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(security, never()).checkText(anyString(), any());
        verify(topicService).updateTopicByOwner(any(), eq(MEMBER), anyString());
    }

    @Test
    void editThatChangesStory_rerunsMachineCheck_andRefusesRiskyStory() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(saved());
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("正常主题名");
        dto.setDescription("正常简介");
        dto.setJourneyStory("{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"违规开场白\"}}");

        AjaxResult result = controller.updateTopic(dto);

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("journeyStory.call.opener"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(security, never()).checkText(contains("正常主题名"), any());
        verify(topicService, never()).updateTopicByOwner(any(), anyLong(), anyString());
    }
}
