package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** 商家员工发起主题时，操作者与发布主体必须分离。 */
class ApiTopicMerchantOperatorScopeTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;

    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private IQuotaService quotaService;
    private IMerchantAccessService merchantAccessService;
    private IWxContentSecurityService contentSecurityService;
    private com.chengyinhub.business.service.impl.MemberIdentityService memberIdentityService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        quotaService = mock(IQuotaService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        contentSecurityService = mock(IWxContentSecurityService.class);
        // RUN-52 发布者实名:本用例测的是「操作者与发布主体分离」,实名闸默认放行
        // (mock 的 checkRegistered 返回 null = 已登记),只留一个引用给末尾那条 verify。
        memberIdentityService = mock(com.chengyinhub.business.service.impl.MemberIdentityService.class);
        doReturn(PRINCIPAL).when(controller).getAppUserId();
        doReturn("operator901").when(controller).getAppUserName();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", contentSecurityService);
        ReflectionTestUtils.setField(controller, "memberIdentityService", memberIdentityService);
        when(quotaService.checkCreate(anyLong(), anyString())).thenReturn(null);
        when(contentSecurityService.checkText(anyString(), any())).thenReturn(null);
        when(topicService.createTopic(any(), anyLong(), anyString())).thenReturn(7001L);
    }

    @Test
    void invitationCardsAreOwnerOnlyAndDoNotReadStoryOrEditWindow() {
        com.chengyinhub.business.service.ICmsTopicChapterService chapters = mock(
                com.chengyinhub.business.service.ICmsTopicChapterService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapters);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(99L); topic.setMemberId(PRINCIPAL); topic.setDelFlag(0);
        when(topicService.selectCmsTopicById(99L)).thenReturn(topic);
        com.chengyinhub.business.domain.CmsTopicChapter chapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        chapter.setId(11L); chapter.setName("学习"); chapter.setBlocksJson("private story");
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(java.util.Collections.singletonList(chapter));
        AjaxResult result = controller.inviteChapters("99", null);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("private story"));
        topic.setMemberId(OWNER);
        assertEquals(HttpStatus.ERROR, controller.inviteChapters("99", null).get(AjaxResult.CODE_TAG));
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(access(MerchantRoleCode.MERCHANT_MANAGER));
        assertEquals(200, controller.inviteChapters("99", "MERCHANT").get(AjaxResult.CODE_TAG));
        topic.setDelFlag(2);
        assertEquals(HttpStatus.ERROR, controller.inviteChapters("99", "MERCHANT").get(AjaxResult.CODE_TAG));
    }

    @Test
    void coopPermissionKeepsClubTopicPickerButCannotReadInvitationCards() {
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.COOP_MANAGE))
                .thenReturn(access(MerchantRoleCode.MERCHANT_MANAGER));
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));

        assertEquals(HttpStatus.FORBIDDEN,
                controller.inviteChapters("99", "MERCHANT").get(AjaxResult.CODE_TAG));
        verify(merchantAccessService, never()).require(PRINCIPAL, MerchantPermission.COOP_MANAGE);
        verify(topicService, never()).selectCmsTopicById(anyLong());

        // master 的俱乐部合作选题权限继续有效，只能查询所管理商家发布的主题。
        when(topicService.selectCmsTopicList(any())).thenReturn(java.util.Collections.emptyList());
        ReflectionTestUtils.setField(controller, "topicContentPublisherProjectionService", mock(
                com.chengyinhub.business.service.ITopicContentPublisherProjectionService.class));
        org.springframework.web.context.request.RequestAttributes previous =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        try {
            assertEquals(200,
                    controller.topicList("1", null, null, null, null, "MERCHANT", null, null, "club")
                            .get(AjaxResult.CODE_TAG));
            org.mockito.ArgumentCaptor<com.chengyinhub.business.domain.CmsTopic> query =
                    org.mockito.ArgumentCaptor.forClass(com.chengyinhub.business.domain.CmsTopic.class);
            verify(topicService).selectCmsTopicList(query.capture());
            assertEquals(Long.valueOf(OWNER), query.getValue().getMemberId());
            verify(topicService, never()).selectPublicCmsTopicList(any());
            verify(merchantAccessService).require(PRINCIPAL, MerchantPermission.COOP_MANAGE);
        } finally {
            com.chengyinhub.common.utils.PageUtils.clearPage();
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(previous);
        }
    }

    @Test
    void projectManagerCannotReadAnotherPublishersInvitationCards() {
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(access(MerchantRoleCode.MERCHANT_MANAGER));
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(99L); topic.setMemberId(1001L); topic.setDelFlag(0);
        when(topicService.selectCmsTopicById(99L)).thenReturn(topic);
        com.chengyinhub.business.service.ICmsTopicChapterService chapters = mock(
                com.chengyinhub.business.service.ICmsTopicChapterService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapters);

        assertEquals(HttpStatus.ERROR,
                controller.inviteChapters("99", "MERCHANT").get(AjaxResult.CODE_TAG));
        verify(chapters, never()).selectCmsTopicChapterList(any());
    }

    private TopicCreateDTO freeExploreDto() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setName("员工代商家发起");
        return dto;
    }

    private TopicCreateDTO merchantDto() {
        TopicCreateDTO dto = freeExploreDto();
        dto.setScope("MERCHANT");
        return dto;
    }

    private MerchantAccessContext access(String roleCode) {
        return new MerchantAccessContext(PRINCIPAL, 77L, OWNER, roleCode);
    }

    @Test
    void managerCreatesUnderMerchantOwner_notUnderOperatorAccount() {
        MerchantAccessContext context = access(MerchantRoleCode.MERCHANT_MANAGER);
        when(merchantAccessService.resolve(PRINCIPAL)).thenReturn(context);
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(context);

        AjaxResult result = controller.createTopic(merchantDto());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(quotaService).checkCreate(OWNER, IQuotaService.TYPE_THEME);
        verify(topicService).createTopic(any(TopicCreateDTO.class), eq(OWNER), eq("operator901"));
        verify(topicService, never()).createTopic(any(TopicCreateDTO.class), eq(PRINCIPAL), anyString());
        // RUN-52 发布者实名:该交证件的是拿钱的主体,不是点按钮的员工。
        // 员工代商家发布时如果去查操作者,等于要求每个运营都把自己的身份证交给平台。
        verify(memberIdentityService).checkRegistered(OWNER);
        verify(memberIdentityService, never()).checkRegistered(PRINCIPAL);
    }

    /**
     * 实名闸只拦「真的要把东西放出去」:存草稿还没有对外收钱,拦在那里等于
     * 把没实名的人锁在填写过程外面,他连把主题写完都做不到。
     */
    @Test
    void unregisteredOwnerCannotPublish_butDraftStillPasses() {
        when(memberIdentityService.checkRegistered(anyLong()))
                .thenReturn(com.chengyinhub.business.service.impl.MemberIdentityService.MSG_NOT_REGISTERED);

        AjaxResult blocked = controller.createTopic(freeExploreDto());
        assertEquals(HttpStatus.ERROR, blocked.get(AjaxResult.CODE_TAG));
        assertEquals(com.chengyinhub.business.service.impl.MemberIdentityService.MSG_NOT_REGISTERED,
                blocked.get(AjaxResult.MSG_TAG));
        verify(topicService, never()).createTopic(any(), anyLong(), anyString());

        TopicCreateDTO draft = freeExploreDto();
        draft.setSaveDraft(1);
        AjaxResult allowed = controller.createTopic(draft);
        assertEquals(200, allowed.get(AjaxResult.CODE_TAG));
        verify(topicService).createTopic(any(TopicCreateDTO.class), eq(PRINCIPAL), eq("operator901"));
    }

    @Test
    void explicitlyFreeTicketsPublishWithoutIdentity_butPaidAndUnknownPricesDoNot() {
        TopicCreateDTO dto = freeExploreDto();
        com.chengyinhub.business.domain.dto.ActivityTicketRequest ticket =
                new com.chengyinhub.business.domain.dto.ActivityTicketRequest();
        ticket.setPrice(java.math.BigDecimal.ZERO);
        dto.setTickets(java.util.Collections.singletonList(ticket));
        assertEquals(200, controller.createTopic(dto).get(AjaxResult.CODE_TAG));
        verify(memberIdentityService, never()).checkRegistered(anyLong());

        when(memberIdentityService.checkRegistered(anyLong()))
                .thenReturn(com.chengyinhub.business.service.impl.MemberIdentityService.MSG_NOT_REGISTERED);
        for (java.math.BigDecimal price : new java.math.BigDecimal[] {
                new java.math.BigDecimal("0.01"), new java.math.BigDecimal("-1"), null }) {
            ticket.setPrice(price);
            assertEquals(HttpStatus.ERROR, controller.createTopic(dto).get(AjaxResult.CODE_TAG));
        }
        ticket.setPrice(java.math.BigDecimal.ZERO);
        dto.setSelfPlay(1);
        dto.setSelfPlayPrice(java.math.BigDecimal.ONE);
        assertEquals(HttpStatus.ERROR, controller.createTopic(dto).get(AjaxResult.CODE_TAG));
    }

    @Test
    void financeOperatorCannotCreateProject() {
        MerchantAccessContext context = access(MerchantRoleCode.MERCHANT_FINANCE);
        when(merchantAccessService.resolve(PRINCIPAL)).thenReturn(context);
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));

        AjaxResult result = controller.createTopic(merchantDto());

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        verify(topicService, never()).createTopic(any(), anyLong(), anyString());
        verify(quotaService, never()).checkCreate(anyLong(), anyString());
    }

    @Test
    void ordinaryPublisherStillOwnsTheirTopic() {
        MerchantAccessContext context = access(MerchantRoleCode.MERCHANT_MANAGER);
        when(merchantAccessService.resolve(PRINCIPAL)).thenReturn(context);

        AjaxResult result = controller.createTopic(freeExploreDto());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(topicService).createTopic(any(TopicCreateDTO.class), eq(PRINCIPAL), eq("operator901"));
        verify(merchantAccessService, never()).require(anyLong(), anyString());
    }

    @Test
    void unknownNonBlankScopeFailsClosedInsteadOfPublishingUnderOperator() {
        TopicCreateDTO dto = freeExploreDto();
        dto.setScope("MERHCANT");

        AjaxResult result = controller.createTopic(dto);

        assertEquals(HttpStatus.BAD_REQUEST, result.get(AjaxResult.CODE_TAG));
        verify(topicService, never()).createTopic(any(), anyLong(), anyString());
    }
}
