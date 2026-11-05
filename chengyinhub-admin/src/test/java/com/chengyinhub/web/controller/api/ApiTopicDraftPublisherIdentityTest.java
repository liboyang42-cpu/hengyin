package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiTopicDraftPublisherIdentityTest {
    private ApiTopicController controller;
    private ICmsTopicService topics;
    private IOmsTicketService tickets;
    private MemberIdentityService identity;
    private CmsTopic draft;
    private IQuotaService quota;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        doReturn(901L).when(controller).getAppUserId();
        topics = mock(ICmsTopicService.class);
        tickets = mock(IOmsTicketService.class);
        identity = mock(MemberIdentityService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        // 草稿发布口会读库内节点绑定的玩法去复检文字(j5 B1);这里没有绑定玩法
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ICmsTopicNodeService.class));
        ReflectionTestUtils.setField(controller, "omsTicketService", tickets);
        ReflectionTestUtils.setField(controller, "memberIdentityService", identity);
        quota = mock(IQuotaService.class);
        ReflectionTestUtils.setField(controller, "quotaService", quota);
        draft = new CmsTopic();
        draft.setId(7001L); draft.setMemberId(901L); draft.setPublishMode("pro");
        when(topics.selectCmsTopicById(7001L)).thenReturn(draft);
        when(topics.selectTopicIsDraft(7001L)).thenReturn(1);
        when(tickets.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        when(identity.checkRegistered(901L)).thenReturn("请先登记实名信息(姓名与身份证号),再发布");
        when(topics.publishDraftWithReview(7001L, 901L)).thenReturn(
                new TopicBundleSubmitResultVO(7001L, 901L, "PENDING", Collections.singletonList(71L)));
    }
    private void price(BigDecimal price) {
        OmsTicket ticket = new OmsTicket(); ticket.setPrice(price);
        when(tickets.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));
    }
    private AjaxResult publish() { return controller.publishDraft("7001", null); }
    private void assertIdentityDenied() {
        AjaxResult result = publish();
        assertEquals(500, result.get("code"));
        assertEquals("请先登记实名信息(姓名与身份证号),再发布", result.get("msg"));
        verify(topics, never()).publishDraftWithReview(anyLong(), anyLong());
        verify(topics, never()).publishDraft(anyLong(), anyLong());
        verify(identity).checkRegistered(901L);
        verifyNoInteractions(quota);
    }
    @Test void noTicketsRequiresIdentity() { assertIdentityDenied(); }
    @Test void paidTicketRequiresIdentity() { price(BigDecimal.ONE); assertIdentityDenied(); }
    @Test void missingTicketPriceRequiresIdentity() { price(null); assertIdentityDenied(); }
    @Test void paidSelfPlayRequiresIdentity() { price(BigDecimal.ZERO); draft.setSelfPlay(1); draft.setSelfPlayPrice(BigDecimal.ONE); assertIdentityDenied(); }
    @Test void missingSelfPlayPriceRequiresIdentity() {
        price(BigDecimal.ZERO); draft.setSelfPlay(1); assertIdentityDenied();
    }
    @Test void simplePublicationDenialCannotConsumeQuota() {
        draft.setPublishMode("simple"); assertIdentityDenied();
    }
    @Test void publicationReadsOnlyPersistedTopicTickets() {
        price(BigDecimal.ZERO); assertEquals(200, publish().get("code"));
        org.mockito.ArgumentCaptor<OmsTicket> query = org.mockito.ArgumentCaptor.forClass(OmsTicket.class);
        verify(tickets).selectOmsTicketList(query.capture());
        assertEquals(7001L, query.getValue().getOwnerId());
        assertEquals(1L, query.getValue().getOwnerType());
        assertEquals(0, query.getValue().getDelFlag());
    }
    @Test void allFreePublicationSkipsIdentity() {
        price(BigDecimal.ZERO); draft.setSelfPlay(1); draft.setSelfPlayPrice(BigDecimal.ZERO);
        assertEquals(200, publish().get("code")); verifyNoInteractions(identity); verify(topics).publishDraftWithReview(7001L, 901L);
    }
    @Test void registeredPublisherStillPublishes() {
        when(identity.checkRegistered(901L)).thenReturn(null);
        assertEquals(200, publish().get("code")); verify(identity).checkRegistered(901L); verify(topics).publishDraftWithReview(7001L, 901L);
    }
    @Test void dateRefusalReturns400ThroughActualSimpleAndProDraftMvcEntries() throws Exception {
        price(BigDecimal.ZERO);
        draft.setRecruitDeadline(new java.util.Date(2000L));
        draft.setSaleEndAt(new java.util.Date(2000L));
        when(topics.publishDraftWithReview(7001L, 901L)).thenAnswer(call -> {
            com.chengyinhub.business.service.support.TopicSaleTimeValidator.assertRecruitmentSaleWindow(draft);
            throw new AssertionError("无售卖窗口不得继续发布");
        });
        when(topics.publishDraft(7001L, 901L)).thenAnswer(call -> {
            com.chengyinhub.business.service.support.TopicSaleTimeValidator.assertRecruitmentSaleWindow(draft);
            throw new AssertionError("无售卖窗口不得继续发布");
        });
        when(quota.runWithThemeOnlineGuard(eq(901L), eq(7001L), any())).thenAnswer(call ->
                ((java.util.function.Supplier<?>) call.getArgument(2)).get());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.chengyinhub.framework.web.exception.GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .build();

        for (String mode : new String[] {"simple", "pro"}) {
            draft.setPublishMode(mode);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/topic/publish-draft").param("id", "7001"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg")
                            .value("招商截止日期必须早于售票截止时间"));
        }
        verify(topics).publishDraft(7001L, 901L);
        verify(topics).publishDraftWithReview(7001L, 901L);
        verify(topics, never()).updateCmsTopic(any());
    }

    @Test void draftTechnicalFailureKeeps500AndSafeMessage() throws Exception {
        price(BigDecimal.ZERO);
        when(topics.publishDraftWithReview(7001L, 901L)).thenThrow(
                new NullPointerException("java.lang.NullPointerException: private implementation"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.chengyinhub.framework.web.exception.GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/topic/publish-draft").param("id", "7001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg")
                        .value("系统繁忙，请稍后重试"));
        verify(topics, never()).updateCmsTopic(any());
    }

    @Test void ownershipAndDraftGuardsRemainFirst() {
        draft.setMemberId(902L); assertEquals(500, publish().get("code")); verifyNoInteractions(identity, tickets);
        draft.setMemberId(901L); when(topics.selectTopicIsDraft(7001L)).thenReturn(0);
        assertEquals(500, publish().get("code")); verifyNoInteractions(identity, tickets);
        verify(topics, never()).publishDraftWithReview(anyLong(), anyLong());
    }
}
