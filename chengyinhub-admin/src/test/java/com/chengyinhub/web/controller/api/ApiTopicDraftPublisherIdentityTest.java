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
    @Test void ownershipAndDraftGuardsRemainFirst() {
        draft.setMemberId(902L); assertEquals(500, publish().get("code")); verifyNoInteractions(identity, tickets);
        draft.setMemberId(901L); when(topics.selectTopicIsDraft(7001L)).thenReturn(0);
        assertEquals(500, publish().get("code")); verifyNoInteractions(identity, tickets);
        verify(topics, never()).publishDraftWithReview(anyLong(), anyLong());
    }
}
