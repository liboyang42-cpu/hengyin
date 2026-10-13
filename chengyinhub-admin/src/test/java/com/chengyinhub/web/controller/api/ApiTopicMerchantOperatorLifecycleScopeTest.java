package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 商家经理看到的主题必须按商家主体完成全生命周期，不能停在只读列表。 */
class ApiTopicMerchantOperatorLifecycleScopeTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;
    private static final long TOPIC_ID = 7001L;

    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private IQuotaService quotaService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        quotaService = mock(IQuotaService.class);
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        MerchantAccessContext access = new MerchantAccessContext(
                PRINCIPAL, 77L, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
        doReturn(PRINCIPAL).when(controller).getAppUserId();
        doReturn("operator901").when(controller).getAppUserName();
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(access);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "omsTicketService", mock(com.chengyinhub.business.service.IOmsTicketService.class));
        ReflectionTestUtils.setField(controller, "memberIdentityService", mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
    }

    private CmsTopic ownerTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(OWNER);
        topic.setDelFlag(0);
        topic.setUserStatus(0);
        topic.setPublishMode("pro");
        topic.setLifecycle(0);
        return topic;
    }

    @Test
    void managerCanUpdateOwnerTopic() {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC_ID);
        dto.setName("商家项目");
        dto.setScope("MERCHANT");
        when(topicService.updateTopicByOwner(eq(dto), eq(OWNER), eq("operator901"))).thenReturn(TOPIC_ID);

        AjaxResult result = controller.updateTopic(dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(topicService).updateTopicByOwner(dto, OWNER, "operator901");
    }

    @Test
    void managerCanLoadOwnerEditDetail() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(ownerTopic());
        when(topicService.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", mock(ICmsTopicNodeService.class));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        IViewCollaboratorsService collaboratorService = mock(IViewCollaboratorsService.class);
        when(collaboratorService.selectViewCollaboratorsList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", collaboratorService);

        AjaxResult result = controller.topicEditDetail(String.valueOf(TOPIC_ID), "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void managerCanDeleteOwnerTopic() {
        when(topicService.deleteTopicByOwner(TOPIC_ID, OWNER)).thenReturn(true);

        AjaxResult result = controller.topicDelete(String.valueOf(TOPIC_ID), "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(topicService).deleteTopicByOwner(TOPIC_ID, OWNER);
    }

    @Test
    void managerCanToggleOwnerTopicUsingOwnerQuota() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(ownerTopic());
        when(topicService.casUserStatus(TOPIC_ID, 0, 1)).thenReturn(1);
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(2)).get())
                .when(quotaService).runWithThemeOnlineGuard(eq(OWNER), eq(TOPIC_ID), any());

        AjaxResult result = controller.topicUpdateUserStatus(String.valueOf(TOPIC_ID), "0", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(quotaService).runWithThemeOnlineGuard(eq(OWNER), eq(TOPIC_ID), any());
    }

    @Test
    void managerCanPublishOwnerDraft() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(ownerTopic());
        when(topicService.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);
        when(topicService.publishDraftWithReview(TOPIC_ID, OWNER)).thenReturn(
                new com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO(
                        TOPIC_ID, 901L, "PENDING", java.util.Collections.singletonList(71L)));

        AjaxResult result = controller.publishDraft(String.valueOf(TOPIC_ID), "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(topicService).publishDraftWithReview(TOPIC_ID, OWNER);
        verify((com.chengyinhub.business.service.impl.MemberIdentityService) ReflectionTestUtils.getField(controller, "memberIdentityService")).checkRegistered(OWNER);
    }

    @Test
    void managerCanGraduateOwnerBeta() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(ownerTopic());
        when(topicService.graduateBeta(TOPIC_ID)).thenReturn(1);

        AjaxResult result = controller.betaGraduate(String.valueOf(TOPIC_ID), "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(topicService).graduateBeta(TOPIC_ID);
    }

    @Test
    void managerCanTransferOwnerTopicThroughOwnerCooperation() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(ownerTopic());
        IClubService clubService = mock(IClubService.class);
        Club club = new Club();
        club.setId(88L);
        when(clubService.selectClubById(88L)).thenReturn(club);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubLeaderService", mock(IClubLeaderService.class));
        ICoopInviteService coopService = mock(ICoopInviteService.class);
        when(coopService.selectCoopInviteList(any())).thenReturn(Collections.singletonList(
                new com.chengyinhub.business.domain.CoopInvite()));
        ReflectionTestUtils.setField(controller, "coopInviteService", coopService);
        when(topicService.transferToClub(TOPIC_ID, OWNER, 88L)).thenReturn(7002L);

        AjaxResult result = controller.transferToClub(String.valueOf(TOPIC_ID), "88", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(topicService).transferToClub(TOPIC_ID, OWNER, 88L);
    }
}
