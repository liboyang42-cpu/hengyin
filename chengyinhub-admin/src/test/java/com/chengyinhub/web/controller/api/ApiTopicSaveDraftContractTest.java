package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R18-DRAFT 服务端草稿:复用 /api/topic/create + saveDraft=1 作为「存草稿」入口
 * (第一次保存拿到服务端 topicId,后续重复保存走 /api/topic/update),发布仍走 /api/topic/publish-draft。
 *
 * <p>本文件钉死三件事:
 * <ul>
 *   <li>存草稿返回服务端 id,且消息是「草稿已保存」而不是把存草稿说成发布;</li>
 *   <li>草稿不进创意广场 —— 公开 feed(ums_creative_square dataType=2)与草稿的可见性契约相反,
 *       客户端表单里 publishToCreative 开关可能还开着,服务端必须按 saveDraft 兜住,传了开关也不放行;</li>
 *   <li>发布接口沿用同一个 id(草稿 id 就是发布 id,不另起一条)。</li>
 * </ul>
 *
 * <p>负控:把 createTopic 里创意广场那行的 {@code !saveDraft &&} 删掉,
 * {@link #draftNeverSyncsCreativeSquare} 立刻红。
 */
class ApiTopicSaveDraftContractTest {

    private static final long MEMBER = 901L;
    private static final long TOPIC_ID = 7001L;

    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private IQuotaService quotaService;
    private IUmsCreativeSquareService creativeSquareService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        // 手工 new 不走 @Value 默认值,旧写门禁显式开闸(与 Spring 运行时默认一致)。
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);
        topicService = mock(ICmsTopicService.class);
        quotaService = mock(IQuotaService.class);
        creativeSquareService = mock(IUmsCreativeSquareService.class);
        doReturn(MEMBER).when(controller).getAppUserId();
        doReturn("member901").when(controller).getAppUserName();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", creativeSquareService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        // RUN-52 发布者实名:本用例测的是草稿/发布两条路的创意广场同步契约,实名闸在这里放行
        // (mock 的 checkRegistered 返回 null = 已登记)。
        ReflectionTestUtils.setField(controller, "memberIdentityService",
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        when(quotaService.checkCreate(anyLong(), anyString())).thenReturn(null);
        when(topicService.createTopic(any(TopicCreateDTO.class), anyLong(), anyString())).thenReturn(TOPIC_ID);
    }

    private TopicCreateDTO draftDto() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setName("尚未发布的新主题");
        dto.setPublishMode("pro");
        dto.setSaveDraft(1);
        return dto;
    }

    @Test
    void saveDraftReturnsServerTopicId() {
        AjaxResult result = controller.createTopic(draftDto());

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        assertEquals(TOPIC_ID, result.get(AjaxResult.DATA_TAG), "存草稿接口必须回服务端草稿 id");
        assertEquals("草稿已保存", result.get(AjaxResult.MSG_TAG), "存草稿不能报成「主题发布成功」");
        ArgumentCaptor<TopicCreateDTO> cap = ArgumentCaptor.forClass(TopicCreateDTO.class);
        verify(topicService).createTopic(cap.capture(), eq(MEMBER), eq("member901"));
        assertEquals(Integer.valueOf(1), cap.getValue().getSaveDraft(), "saveDraft 必须原样落到建题路径");
    }

    /**
     * 草稿不得进创意广场:那是公开 feed。客户端表单里 publishToCreative 开关可能还是开着的,
     * 服务端必须按 saveDraft 兜住 —— 否则一条草稿帖会以作者名义出现在广场。
     */
    @Test
    void draftNeverSyncsCreativeSquare() {
        TopicCreateDTO dto = draftDto();
        dto.setPublishToCreative(1);

        AjaxResult result = controller.createTopic(dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        verify(creativeSquareService, never()).insertUmsCreativeSquare(any());
    }

    /** 负控(正面对照):不存草稿时创意广场同步照旧 —— 上面那条测的是「草稿跳过」,不是「整条链路坏了」。 */
    @Test
    void publishedCreateStillSyncsCreativeSquare() {
        TopicCreateDTO dto = draftDto();
        dto.setSaveDraft(null);
        dto.setPublishToCreative(1);

        AjaxResult result = controller.createTopic(dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        verify(creativeSquareService).insertUmsCreativeSquare(any());
    }

    /** 草稿 id 就是发布 id:发布走既有 publish-draft,不另起一条主题。 */
    @Test
    void publishDraftReusesTheSameServerId() {
        CmsTopic draft = new CmsTopic();
        draft.setId(TOPIC_ID);
        draft.setMemberId(MEMBER);
        draft.setDelFlag(0);
        draft.setLifecycle(0);
        draft.setPublishMode("pro");
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(draft);
        when(topicService.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);
        com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO receipt =
                new com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO(
                        TOPIC_ID, 901L, "PENDING", java.util.Collections.singletonList(71L));
        when(topicService.publishDraftWithReview(TOPIC_ID)).thenReturn(receipt);

        AjaxResult result = controller.publishDraft(String.valueOf(TOPIC_ID), null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        assertEquals(receipt, result.get(AjaxResult.DATA_TAG));
        verify(topicService).publishDraftWithReview(TOPIC_ID);
        verify(topicService, never()).createTopic(any(), anyLong(), anyString());
    }

    /** 他人读草稿编辑器详情:在归属闸就被拒,不碰章节/票/合作者等下游读。 */
    @Test
    void strangerCannotReadDraftInEditDetail() {
        CmsTopic draft = new CmsTopic();
        draft.setId(TOPIC_ID);
        draft.setMemberId(999L);
        draft.setDelFlag(0);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(draft);

        AjaxResult result = controller.topicEditDetail(String.valueOf(TOPIC_ID), null);

        assertTrue(result.get(AjaxResult.CODE_TAG) != null && !Integer.valueOf(200).equals(result.get(AjaxResult.CODE_TAG)),
                result.toString());
        assertEquals("无权编辑该主题", result.get(AjaxResult.MSG_TAG));
    }
}
