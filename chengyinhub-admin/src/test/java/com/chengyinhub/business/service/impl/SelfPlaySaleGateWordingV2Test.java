package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CL-37 / F-W1-05:付费主题审核前不能售卖,且玩家拿到的拒绝文案不能露出内部生命周期名(SELLING 等)。
 * validateTopicSellable 是自玩下单 / 主题直售报价共用的售卖闸,文案须与 assertCommercialSaleOpen 同口径:
 * 未到售卖 = 尚未开售;已过售卖期 = 已停止售卖;未知生命周期继续 fail-closed,按尚未开售拦截。
 */
class SelfPlaySaleGateWordingV2Test {

    private static final Long TOPIC_ID = 510L;
    private static final Long MEMBER_ID = 89L;
    private static final Long TICKET_ID = 310L;
    private static final String NOT_OPEN = "该主题尚未开售，开售审核通过后才可报名";
    private static final String STOPPED = "报名已结束，本主题已停止售卖";

    private CmsRegistrationServiceImpl serviceWithLifecycle(Integer lifecycle) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        topic.setLifecycle(lifecycle);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);

        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setOwnerId(TOPIC_ID);
        ticket.setOwnerType(1L);
        ticket.setDelFlag(0);
        ticket.setRemainingInventory(9999L);
        ticket.setPrice(new BigDecimal("100.00"));
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket);

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        com.chengyinhub.business.service.IProductRoleService productRoles =
                mock(com.chengyinhub.business.service.IProductRoleService.class);
        when(productRoles.isPlatformOwned(TOPIC_ID)).thenReturn(true);
        ReflectionTestUtils.setField(service, "productRoleService", productRoles);
        return service;
    }

    private String rejection(Integer lifecycle) {
        CmsRegistrationServiceImpl service = serviceWithLifecycle(lifecycle);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.quoteRegistration(MEMBER_ID, 1, TOPIC_ID, TICKET_ID, null, 0));
        return ex.getMessage();
    }

    private static void assertNoRawLifecycleName(String message) {
        for (TopicLifecycleState state : TopicLifecycleState.values()) {
            assertFalse(message.contains(state.name()), "玩家文案不能露出内部生命周期名 " + state.name() + ":" + message);
        }
    }

    @Test
    void beforeSaleUsesNotYetOnSaleWording() {
        for (TopicLifecycleState state : new TopicLifecycleState[]{TopicLifecycleState.DRAFT,
                TopicLifecycleState.RECRUITING, TopicLifecycleState.PRICING, TopicLifecycleState.PENDING_REVIEW}) {
            String message = rejection(state.getCode());
            assertNoRawLifecycleName(message);
            assertEquals(NOT_OPEN, message, state.name());
        }
    }

    @Test
    void afterSaleUsesStoppedWording() {
        for (TopicLifecycleState state : new TopicLifecycleState[]{TopicLifecycleState.RUNNING,
                TopicLifecycleState.SETTLING, TopicLifecycleState.CLOSED}) {
            String message = rejection(state.getCode());
            assertNoRawLifecycleName(message);
            assertEquals(STOPPED, message, state.name());
        }
    }

    @Test
    void unknownLifecycleStillFailsClosed() {
        for (Integer code : new Integer[]{null, 99}) {
            String message = rejection(code);
            assertNoRawLifecycleName(message);
            assertEquals(NOT_OPEN, message);
        }
    }
}
