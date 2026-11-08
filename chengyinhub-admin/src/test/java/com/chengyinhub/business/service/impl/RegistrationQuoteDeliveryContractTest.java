package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C06 交付摘要合同：报价必须说清「买的是什么、多少人、何时可用、退款规则」，
 * 同时金额与 quoteSign 一字不改（摘要不参与签名，不得让已发出的报价失效）。
 */
class RegistrationQuoteDeliveryContractTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TICKET_ID = 300L;
    private static final Long OTHER_TICKET_ID = 301L;

    // 相对当前时间构造:硬编码 epoch 会随真实日期过期(1_790_100_000_000 ≈ 2026-09-22,
    // 到了 2026-11 就变成「活动已结束」,夹具自行失效)。窗口不变:开赛前 10 天、结束在未来 10 天。
    private static final long NOW_MILLIS = System.currentTimeMillis();
    private static final Date START = new Date(NOW_MILLIS - 10L * 24 * 60 * 60 * 1000);
    private static final Date END = new Date(NOW_MILLIS + 10L * 24 * 60 * 60 * 1000);

    private OmsTicketMapper ticketMapper;
    private CmsActivityMapper activityMapper;

    private CmsRegistrationServiceImpl service() {
        activityMapper = mock(CmsActivityMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMemberId(MEMBER_ID);
        activity.setName("夜巡场");
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        activity.setStartDate(START);
        activity.setEndDate(END);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);

        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "双人夜巡票"));
        when(ticketMapper.selectOmsTicketById(OTHER_TICKET_ID)).thenReturn(ticket(OTHER_TICKET_ID, "单人夜巡票"));

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "platformMemberId", MEMBER_ID);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        return service;
    }

    private OmsTicket ticket(Long id, String name) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(id);
        ticket.setName(name);
        ticket.setOwnerId(ACTIVITY_ID);
        ticket.setOwnerType(2L);
        ticket.setDelFlag(0);
        ticket.setRemainingInventory(9999L);
        ticket.setPrice(new BigDecimal("100.00"));
        ticket.setTeamSize(2);
        ticket.setMinPeople(2);
        ticket.setMaxPeople(4);
        ticket.setNeedAudit(1);
        ticket.setRefundRule("开场前24小时可退，之后不可退");
        return ticket;
    }

    private static RegistrationTicketItem item(Long ticketId, int count) {
        RegistrationTicketItem item = new RegistrationTicketItem();
        item.setTicketId(ticketId);
        item.setCount(count);
        return item;
    }

    @Test
    void quoteStatesTheActualDeliveryWithoutTouchingTheAmount() {
        RegistrationQuoteResult quote = service()
                .quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0);

        assertEquals(0, new BigDecimal("100.00").compareTo(quote.getPayAmount()), "报价金额不变");
        RegistrationQuoteResult.Delivery delivery = quote.getDelivery();
        assertNotNull(delivery, "报价必须下发交付摘要");
        assertEquals(PurchaseKind.GUIDED_TICKET, delivery.getPurchaseKind().intValue());
        assertEquals("双人夜巡票", delivery.getTicketName());
        assertEquals(2, delivery.getTeamSize().intValue());
        assertEquals(2, delivery.getMinPeople().intValue());
        assertEquals(4, delivery.getMaxPeople().intValue());
        assertEquals(1, delivery.getNeedAudit().intValue());
        assertEquals("开场前24小时可退，之后不可退", delivery.getRefundRule());
        assertEquals(START, delivery.getServiceStartAt());
        assertEquals(END, delivery.getServiceEndAt());
        assertEquals("PAYER_SELF", delivery.getBeneficiaryKind());
        assertEquals(MEMBER_ID, delivery.getSellerMemberId(), "内容/场次归属主体单独给出");
        assertEquals(Boolean.TRUE, delivery.getAmountFromServer());
    }

    /** 摘要不参与签名：换票名/退款规则原文不得让已发出的报价失效。 */
    @Test
    void deliveryFieldsDoNotChangeQuoteSign() {
        CmsRegistrationServiceImpl service = service();
        String first = service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0).getQuoteSign();

        OmsTicket renamed = ticket(TICKET_ID, "改名后的票");
        renamed.setRefundRule("另一套退款文案");
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(renamed);

        String second = service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0).getQuoteSign();
        assertEquals(first, second);
    }

    /** 多票混选不得挑一个票种条款冒充整单交付；只有第一张票可判断购买类型。 */
    @Test
    void mixedTicketQuoteDoesNotBorrowOneTicketsTerms() {
        RegistrationQuoteResult quote = service().quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, null,
                List.of(item(TICKET_ID, 1), item(OTHER_TICKET_ID, 2)), 0);
        RegistrationQuoteResult.Delivery delivery = quote.getDelivery();
        assertNotNull(delivery);
        assertEquals(PurchaseKind.GUIDED_TICKET, delivery.getPurchaseKind().intValue());
        assertNull(delivery.getTicketName(), "混选没有单一票种名");
        assertNull(delivery.getTeamSize());
        assertNull(delivery.getRefundRule(), "缺条款就留空，不编默认值");
        assertEquals(0, new BigDecimal("300.00").compareTo(quote.getPayAmount()), "金额仍是整单票面");
    }

    /** 票种未配置人数/退款规则时留空，不用默认值补成「单人」「可退」。 */
    @Test
    void unconfiguredTicketTermsStayNullInsteadOfDefaults() {
        CmsRegistrationServiceImpl service = service();
        OmsTicket bare = ticket(TICKET_ID, "未配条款的票");
        bare.setTeamSize(null);
        bare.setMinPeople(null);
        bare.setMaxPeople(null);
        bare.setNeedAudit(null);
        bare.setRefundRule(null);
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(bare);

        RegistrationQuoteResult quote = service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0);
        RegistrationQuoteResult.Delivery delivery = quote.getDelivery();
        assertNotNull(delivery);
        assertNull(delivery.getTeamSize());
        assertNull(delivery.getRefundRule(), "未配置退款规则不得被展示成可退");
        assertNull(delivery.getNeedAudit());
        assertEquals(Boolean.TRUE, delivery.getAmountFromServer());
    }
}
