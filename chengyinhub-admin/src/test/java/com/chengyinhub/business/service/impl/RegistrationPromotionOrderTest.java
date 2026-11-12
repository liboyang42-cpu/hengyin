package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.MarketingAdjustment;
import com.chengyinhub.business.domain.dto.MarketingPromotionQuote;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.IMarketingPromotionService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.chengyinhub.business.domain.dto.MarketingPromotionRequest;

/**
 * 促销进入建单的接线:折扣快照、成交快照、指纹闸。
 *
 * <p>守四件事:
 * <ol>
 *   <li>折扣落 {@code discount_amount}(会员折扣+促销),{@code total_amount} 仍是票面;</li>
 *   <li>促销成交快照与订单同事务落一次({@code orderType=1} + 报名主单 id);</li>
 *   <li>客户端回传的 {@code marketingSign} 与服务端重算不一致 ⇒ 409,一个字节都不写;</li>
 *   <li>无促销时不写成交快照。</li>
 * </ol>
 */
class RegistrationPromotionOrderTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TOPIC_ID = 500L;
    private static final Long TICKET_ID = 301L;
    private static final Long REGISTRATION_ID = 1000L;

    @Test
    void createSnapshotsPromotionDiscountAndCommitsPromotionOnce() {
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-1"));
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        CmsRegistrationServiceImpl service = createService(promo, regMapper);

        RegistrationRequest request = request("SIGN-1");
        List<CmsRegistration> created = service.create(MEMBER_ID, request, "req-1", "fp-1");

        CmsRegistration order = created.get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo(order.getOriginalAmount()), "票面仍是 100");
        assertEquals(0, new BigDecimal("100.00").compareTo(order.getTotalAmount()),
                "total_amount 是票面额语义,不能被促销覆盖");
        assertEquals(0, new BigDecimal("20.00").compareTo(order.getDiscountAmount()),
                "折扣快照 = 会员折扣 0 + 促销 20");
        assertEquals(0, new BigDecimal("80.00").compareTo(order.getPayableAmount()));
        assertEquals(0, new BigDecimal("80.00").compareTo(order.getWechatPaymentAmount()));
        verify(promo).commitApplied(any(MarketingPromotionQuote.class),
                eq(MarketingRevenueAllocation.ORDER_REGISTRATION), eq(REGISTRATION_ID), any(Date.class));
    }

    @Test
    void createRejectsStalePromotionSignBeforeWritingAnything() {
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-FRESH"));
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        CmsRegistrationServiceImpl service = createService(promo, regMapper);

        assertThrows(RegistrationRequestConflictException.class,
                () -> service.create(MEMBER_ID, request("SIGN-STALE"), "req-1", "fp-1"));

        verify(regMapper, never()).insertCmsRegistration(any());
        verify(promo, never()).commitApplied(any(), any(), anyLong(), any());
    }

    @Test
    void createRejectsMissingSignWhenPromotionActuallyApplies() {
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-FRESH"));
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        CmsRegistrationServiceImpl service = createService(promo, regMapper);

        assertThrows(RegistrationRequestConflictException.class,
                () -> service.create(MEMBER_ID, request(null), "req-1", "fp-1"),
                "促销真的生效而客户端没带指纹 ⇒ 用户看到的价可能不是这个,必须重新报价");
        verify(regMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void createRejectsZeroPayablePromotionInsteadOfChargingListPrice() {
        // root 纠偏:不支持的优惠不能"静默按原价收" —— 那等于替用户抬价。
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("0.00", "100.00", "SIGN-FREE"));
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        CmsRegistrationServiceImpl service = createService(promo, regMapper);

        assertThrows(RegistrationRequestConflictException.class,
                () -> service.create(MEMBER_ID, request("SIGN-FREE"), "req-1", "fp-1"));
        verify(regMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void createWithoutPromotionWritesNoPromotionSnapshot() {
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        CmsRegistrationServiceImpl service = createService(null, regMapper);

        List<CmsRegistration> created = service.create(MEMBER_ID, request(null), "req-1", "fp-1");

        CmsRegistration order = created.get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo(order.getPayableAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(order.getDiscountAmount()));
    }

    /**
     * 「报价 = 实付」的根:促销请求的行必须**逐字相同**地喂给报价与建单(同票种跨行也一致)。
     * 只要有一处对不上(行顺序、张数、单价、offerCode),两个数就一定不同,而这在界面上是查不出的。
     */
    @Test
    void quoteAndCreateFeedTheSamePromotionLines() {
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-1"));
        CmsRegistrationServiceImpl service = createService(promo, mock(CmsRegistrationMapper.class));

        RegistrationRequest request = multiRequest("SIGN-1");
        service.quoteRegistration(MEMBER_ID, request);
        service.create(MEMBER_ID, request, "req-1", "fp-1");

        ArgumentCaptor<MarketingPromotionRequest> captor =
                ArgumentCaptor.forClass(MarketingPromotionRequest.class);
        verify(promo, org.mockito.Mockito.times(2)).quote(captor.capture(), any(Date.class));
        List<MarketingPromotionRequest> captured = captor.getAllValues();
        assertEquals(describe(captured.get(0)), describe(captured.get(1)),
                "报价与建单喂给促销引擎的行/优惠码不一致");
        assertEquals("COMBO-1", captured.get(1).getOfferCode());
    }

    private String describe(MarketingPromotionRequest request) {
        StringBuilder out = new StringBuilder()
                .append(request.getMemberId()).append('/')
                .append(request.getOwnerType()).append('/')
                .append(request.getOwnerId()).append('/')
                .append(request.getOfferCode()).append('[');
        for (MarketingPromotionRequest.Line line : request.getLines()) {
            out.append(line.getTicketId()).append(':').append(line.getQuantity())
                    .append('@').append(line.getUnitListPrice().stripTrailingZeros().toPlainString())
                    .append(';');
        }
        return out.append(']').toString();
    }

    /** 同一票种拆两行(1+2 张):整单 3 张,行级预检与促销聚合都必须按票种合并。 */
    private RegistrationRequest multiRequest(String marketingSign) {
        RegistrationRequest request = request(marketingSign);
        request.setTicketId(null);
        List<com.chengyinhub.business.domain.dto.RegistrationTicketItem> tickets = new java.util.ArrayList<>();
        tickets.add(item(TICKET_ID, 1));
        tickets.add(item(TICKET_ID, 2));
        request.setTickets(tickets);
        request.setOfferCode("COMBO-1");
        return request;
    }

    private com.chengyinhub.business.domain.dto.RegistrationTicketItem item(Long ticketId, int count) {
        com.chengyinhub.business.domain.dto.RegistrationTicketItem item =
                new com.chengyinhub.business.domain.dto.RegistrationTicketItem();
        item.setTicketId(ticketId);
        item.setCount(count);
        return item;
    }

    // ------------------------------------------------------------------ fixture

    private RegistrationRequest request(String marketingSign) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(ACTIVITY_ID);
        request.setTicketId(TICKET_ID);
        request.setIsUsePoint(0);
        request.setRealName("张三");
        request.setPhone("18800000000");
        request.setRequestId("req-1");
        request.setMarketingSign(marketingSign);
        return request;
    }

    private MarketingPromotionQuote promotionQuote(String payable, String discount, String sign) {
        MarketingPromotionQuote quote = new MarketingPromotionQuote();
        quote.setMemberId(MEMBER_ID);
        quote.setOwnerType(2);
        quote.setOwnerId(ACTIVITY_ID);
        quote.setListAmount(new BigDecimal("100.00"));
        quote.setPromotionDiscountAmount(new BigDecimal(discount));
        quote.setPayableAmount(new BigDecimal(payable));
        quote.setRuleVersion("offer:0|tier:|promo:5@1");
        quote.setMarketingSign(sign);
        quote.getAdjustments().add(new MarketingAdjustment(
                MarketingPromotionQuote.KIND_PROMOTION, "FIRST20", "首关优惠",
                new BigDecimal(discount), Integer.valueOf(1)));
        return quote;
    }

    private CmsRegistrationServiceImpl createService(IMarketingPromotionService promo,
                                                     CmsRegistrationMapper regMapper) {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        CommercialTopicPolicy commercialPolicy = mock(CommercialTopicPolicy.class);
        IProductRoleService productRoleService = mock(IProductRoleService.class);

        CmsActivity activity = activity();
        CmsTopic topic = sellingTopic();
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY_ID)).thenReturn(activity);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket());
        when(ticketMapper.deductInventory(eq(TICKET_ID), anyLong())).thenReturn(1);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member());
        when(commercialPolicy.isFreeTopic(any())).thenReturn(false);
        when(productRoleService.isPlatformOwned(TOPIC_ID)).thenReturn(true);
        when(regMapper.insertCmsRegistration(any())).thenAnswer(invocation -> {
            CmsRegistration inserted = invocation.getArgument(0);
            inserted.setId(REGISTRATION_ID);
            return 1;
        });
        when(regMapper.bindRequestFingerprint(any(), any(), any())).thenReturn(1);

        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.1000"));
        when(ruleService.getActiveRule(0L, "activity")).thenReturn(rule);

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "commercialTopicPolicy", commercialPolicy);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "umsMemberPointsDetailMapper", mock(UmsMemberPointsDetailMapper.class));
        ReflectionTestUtils.setField(service, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(service, "platformMemberId", MEMBER_ID);
        if (promo != null) {
            ReflectionTestUtils.setField(service, "marketingPromotionService", promo);
        }
        return service;
    }

    private CmsActivity activity() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMemberId(MEMBER_ID);
        activity.setName("测试活动");
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        activity.setTopicId(TOPIC_ID);
        activity.setEndDate(new Date(System.currentTimeMillis() + 86_400_000L));
        return activity;
    }

    private CmsTopic sellingTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        topic.setLifecycle(3);
        topic.setSellingStartedAt(new Date(System.currentTimeMillis() - 3_600_000L));
        topic.setSaleEndAt(new Date(System.currentTimeMillis() + 3_600_000L));
        topic.setFeePlatformRate(new BigDecimal("0.1000"));
        return topic;
    }

    private OmsTicket ticket() {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setOwnerId(ACTIVITY_ID);
        ticket.setOwnerType(2L);
        ticket.setDelFlag(0);
        ticket.setName("标准票");
        ticket.setRemainingInventory(50L);
        ticket.setPrice(new BigDecimal("100.00"));
        return ticket;
    }

    private UmsMember member() {
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        return member;
    }
}
