package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.MarketingAdjustment;
import com.chengyinhub.business.domain.dto.MarketingPromotionQuote;
import com.chengyinhub.business.domain.dto.MarketingPromotionRequest;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMarketingPromotionService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 促销进入报价的接线(M2-F ↔ M4)。
 *
 * <p>守四件事:
 * <ol>
 *   <li>促销只把"票面"降到"促销后应付",积分抵扣仍按既有算法在**促销后金额**上算;</li>
 *   <li>客户端给的 {@code offerCode} 真的传给了 M4(不是只收不用);</li>
 *   <li>促销把应付压到 0 时**显式拒绝**(该优惠不可用,409),绝不丢弃促销后按原价成交;</li>
 *   <li>促销规则版本进 quoteSign:同额不同因的报价必须是两个签名。</li>
 * </ol>
 * 全部 Mockito 打桩,不连库;M4 计算器本身的行为由 M4 自己的测试覆盖。
 */
class RegistrationPromotionQuoteTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TOPIC_ID = 500L;
    private static final Long TICKET_ID = 301L;

    @Test
    void withoutPromotionProviderQuoteStaysAtListPrice() {
        CmsRegistrationServiceImpl service = quoteService(null);
        RegistrationQuoteResult quote = service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0);

        assertEquals(0, new BigDecimal("100.00").compareTo(quote.getPayAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(quote.getListAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(quote.getMarketingDiscountAmount()));
        assertNull(quote.getMarketingSign(), "没有促销就不能要求客户端回传促销指纹");
    }

    @Test
    void promotionReducesPayableAndIsProjectedAsDiscountNotAsCoupon() {
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-1"));
        CmsRegistrationServiceImpl service = quoteService(promo);

        RegistrationQuoteResult quote = service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0);

        assertEquals(0, new BigDecimal("80.00").compareTo(quote.getPayAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(quote.getListAmount()));
        assertEquals(0, new BigDecimal("20.00").compareTo(quote.getMarketingDiscountAmount()));
        assertEquals("SIGN-1", quote.getMarketingSign());
        assertNotNull(quote.getMarketingAdjustments());
        assertEquals(1, quote.getMarketingAdjustments().size());
        // 券抵扣恒为 0:促销不是券,券也不抵支付。
        assertEquals(0, BigDecimal.ZERO.compareTo(quote.getCouponDeductYuan()));
    }

    @Test
    void clientOfferCodeIsForwardedToThePromotionEngine() {
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-1"));
        CmsRegistrationServiceImpl service = quoteService(promo);

        com.chengyinhub.business.domain.dto.RegistrationRequest request =
                new com.chengyinhub.business.domain.dto.RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(ACTIVITY_ID);
        request.setTicketId(TICKET_ID);
        request.setIsUsePoint(0);
        request.setOfferCode("COMBO-1");
        service.quoteRegistration(MEMBER_ID, request);

        ArgumentCaptor<MarketingPromotionRequest> captor =
                ArgumentCaptor.forClass(MarketingPromotionRequest.class);
        org.mockito.Mockito.verify(promo).quote(captor.capture(), any(Date.class));
        assertEquals("COMBO-1", captor.getValue().getOfferCode(),
                "优惠码只由客户端给码,服务端读配置;码必须真的传到计算引擎");
        assertEquals(MEMBER_ID, captor.getValue().getMemberId());
        assertEquals(1, captor.getValue().getLines().size());
        assertEquals(0, new BigDecimal("100.00")
                .compareTo(captor.getValue().getLines().get(0).getUnitListPrice()),
                "行价必须取服务端票真源,不能取客户端");
    }

    @Test
    void promotionThatWouldZeroThePayableIsReportedUnavailableNotSilentlyRepriced() {
        // root 纠偏:不支持的优惠必须报不可用,**绝不**替用户把价抬回票面。
        // 零元单在本平台无法执行(completeZeroAmountPayment 会直接置为已付、无付款事实),
        // 所以这条优惠对本次报价无效 —— 显式拒绝,让用户改选或找运营。
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("0.00", "100.00", "SIGN-FREE"));
        CmsRegistrationServiceImpl service = quoteService(promo);

        com.chengyinhub.business.service.RegistrationRequestConflictException rejected =
                assertThrows(com.chengyinhub.business.service.RegistrationRequestConflictException.class,
                        () -> service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0));
        assertTrue(rejected.getMessage().contains("降为 0"),
                "原因必须可解释(实际:" + rejected.getMessage() + ")");
    }

    @Test
    void pointsAreDeductedFromThePromotedAmount() {
        IMarketingPromotionService promo = mock(IMarketingPromotionService.class);
        when(promo.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-1"));
        CmsRegistrationServiceImpl service = quoteService(promo);

        // 5000 积分 × 0.02 = 可抵 ¥100,但封顶 ¥50;促销后应付 80 ⇒ 只能抵 50(不是 100)。
        RegistrationQuoteResult quote = service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 1);

        assertEquals(0, new BigDecimal("50.00").compareTo(quote.getPointsDeductYuan()));
        assertEquals(0, new BigDecimal("30.00").compareTo(quote.getPayAmount()));
    }

    @Test
    void quoteSignBindsPromotionRuleVersionNotJustTheAmount() {
        IMarketingPromotionService first = mock(IMarketingPromotionService.class);
        when(first.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-A"));
        IMarketingPromotionService second = mock(IMarketingPromotionService.class);
        when(second.quote(any(), any())).thenReturn(promotionQuote("80.00", "20.00", "SIGN-B"));

        String signA = quoteService(first).quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0).getQuoteSign();
        String signB = quoteService(second).quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0).getQuoteSign();

        assertNotNull(signA);
        assertNotEquals(signA, signB,
                "同额不同因(不同规则版本)必须是两个签名,否则换个等额优惠重放也能过");
    }

    @Test
    void quoteWithoutPromotionKeepsLegacySignatureShape() {
        // 无促销时 quoteSign 必须与**历史公式**逐字相同:存量在途报价在升级后仍要能校验通过。
        // 这里直接按历史公式(M0-1)手算期望值,而不是拿"新代码跑两遍相等"来自证。
        String plain = quoteService(null).quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0).getQuoteSign();
        String expected = com.chengyinhub.common.utils.sign.Md5Utils.hash(
                MEMBER_ID + "|2|" + ACTIVITY_ID + "|" + TICKET_ID + ":1|0|100.00|0|0.00|"
                        + com.chengyinhub.common.constant.CmsConstants.QUOTE_SIGN_SALT);
        assertEquals(expected, plain, "无促销的签名必须与历史算法一致(否则升级即让在途报价全 409)");

        // 有促销配置但本次没有实际减免(应付==票面)时,同样不改变签名。
        IMarketingPromotionService noop = mock(IMarketingPromotionService.class);
        when(noop.quote(any(), any())).thenReturn(promotionQuote("100.00", "0.00", null));
        assertEquals(plain, quoteService(noop).quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0).getQuoteSign());
        assertFalse(plain == null || plain.isEmpty());
    }

    // ------------------------------------------------------------------ fixture

    private MarketingPromotionQuote promotionQuote(String payable, String discount, String sign) {
        MarketingPromotionQuote quote = new MarketingPromotionQuote();
        quote.setMemberId(MEMBER_ID);
        quote.setOwnerType(2);
        quote.setOwnerId(ACTIVITY_ID);
        quote.setListAmount(new BigDecimal("100.00"));
        quote.setTierDiscountAmount(BigDecimal.ZERO);
        quote.setOfferDiscountAmount(BigDecimal.ZERO);
        quote.setPromotionDiscountAmount(new BigDecimal(discount));
        quote.setPayableAmount(new BigDecimal(payable));
        quote.setRuleVersion("offer:0|tier:|promo:5@1");
        quote.setMarketingSign(sign);
        if (new BigDecimal(discount).signum() > 0) {
            quote.getAdjustments().add(new MarketingAdjustment(
                    MarketingPromotionQuote.KIND_PROMOTION, "FIRST20", "首关优惠",
                    new BigDecimal(discount), Integer.valueOf(1)));
        }
        return quote;
    }

    private CmsRegistrationServiceImpl quoteService(IMarketingPromotionService promo) {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        CommercialTopicPolicy commercialPolicy = mock(CommercialTopicPolicy.class);
        IProductRoleService productRoleService = mock(IProductRoleService.class);

        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity());
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(sellingTopic());
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket());
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member(5000L));
        when(commercialPolicy.isFreeTopic(any())).thenReturn(false);
        when(productRoleService.isPlatformOwned(TOPIC_ID)).thenReturn(true);

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "commercialTopicPolicy", commercialPolicy);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
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
        return topic;
    }

    private OmsTicket ticket() {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setOwnerId(ACTIVITY_ID);
        ticket.setOwnerType(2L);
        ticket.setDelFlag(0);
        ticket.setRemainingInventory(50L);
        ticket.setPrice(new BigDecimal("100.00"));
        return ticket;
    }

    private UmsMember member(long points) {
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(points);
        return member;
    }
}
