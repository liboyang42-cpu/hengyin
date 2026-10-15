package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ISettlementRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多票积分抵扣:封顶必须按【整单】票面算,不能按 tickets[0] 那一行算。
 *
 * 缺陷:quoteRegistration/createMultiTicketRegistrations 都对每一票各调一次 computePrice,
 * 且只给 i==0 传 usePoint;而 computePrice 内部把抵扣封顶降到「本行 totalAmount」
 * ⇒ 实际封顶 = min(50元, tickets[0].price × tickets[0].count)。
 * 后果:同一个购物车,票种排序不同,优惠不同 —— 用户拖一下顺序优惠翻几倍。
 *
 * 这个缺陷当前不可达(前端从不发多票),但 PR C 建起购物车 UI 就会当场变成活的资损,
 * 与 P0-A 同构。多票报价此前零测试覆盖(MoneyPathContractTest 的 tickets 恒传 null)。
 */
class MultiTicketPointsDeductTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long CHEAP_TICKET = 301L;   // ¥10 早鸟
    private static final Long PRICEY_TICKET = 302L;  // ¥200 全价
    private static final Long FREE_TICKET = 303L;
    /** 5000 积分 × 0.02 = 可抵 ¥100,但平台封顶 ¥50(CmsConstants.MAX_POINT_DEDUCT_AMOUNT) */
    private static final long RICH_POINTS = 5000L;

    private CmsRegistrationServiceImpl service(long memberPoint) {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMemberId(MEMBER_ID);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(memberPoint);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);

        when(ticketMapper.selectOmsTicketById(CHEAP_TICKET)).thenReturn(ticket(CHEAP_TICKET, "10.00"));
        when(ticketMapper.selectOmsTicketById(PRICEY_TICKET)).thenReturn(ticket(PRICEY_TICKET, "200.00"));

        CmsRegistrationServiceImpl s = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(s, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(s, "platformMemberId", MEMBER_ID);
        ReflectionTestUtils.setField(s, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(s, "omsTicketMapper", ticketMapper);
        return s;
    }

    private OmsTicket ticket(Long id, String price) {
        OmsTicket t = new OmsTicket();
        t.setId(id);
        t.setOwnerId(ACTIVITY_ID);
        t.setOwnerType(2L);
        t.setDelFlag(0);
        t.setRemainingInventory(9999L);
        t.setPrice(new BigDecimal(price));
        return t;
    }

    private RegistrationTicketItem item(Long ticketId, int count) {
        RegistrationTicketItem it = new RegistrationTicketItem();
        it.setTicketId(ticketId);
        it.setCount(count);
        return it;
    }

    private RegistrationQuoteResult quoteMulti(List<RegistrationTicketItem> tickets, int usePoint) {
        return service(RICH_POINTS).quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, null, tickets, usePoint);
    }

    @Test
    void pointsCapIsBasedOnWholeOrderNotFirstTicketRow() {
        // 购物车:早鸟 ¥10 + 全价 ¥200 = 整单 ¥210。用户积分够抵 ¥100,平台封顶 ¥50。
        // 正确:抵 ¥50 → 实付 ¥160。
        // 缺陷:封顶被降到首行 ¥10 → 只抵 ¥10 → 实付 ¥200。
        RegistrationQuoteResult q = quoteMulti(
                new ArrayList<>(Arrays.asList(item(CHEAP_TICKET, 1), item(PRICEY_TICKET, 1))), 1);

        assertEquals(0, new BigDecimal("50.00").compareTo(q.getPointsDeductYuan()),
                "整单 ¥210 应抵满封顶 ¥50,实际抵 " + q.getPointsDeductYuan());
        assertEquals(0, new BigDecimal("160.00").compareTo(q.getPayAmount()),
                "实付应为 210-50=160,实际 " + q.getPayAmount());
    }

    @Test
    void discountMustNotDependOnCartOrder() {
        // 同一个购物车,两种排序,优惠必须一致 —— 否则用户拖一下顺序就变价。
        RegistrationQuoteResult cheapFirst = quoteMulti(
                new ArrayList<>(Arrays.asList(item(CHEAP_TICKET, 1), item(PRICEY_TICKET, 1))), 1);
        RegistrationQuoteResult priceyFirst = quoteMulti(
                new ArrayList<>(Arrays.asList(item(PRICEY_TICKET, 1), item(CHEAP_TICKET, 1))), 1);

        assertEquals(0, cheapFirst.getPointsDeductYuan().compareTo(priceyFirst.getPointsDeductYuan()),
                "抵扣额随排序变了:便宜票在前=" + cheapFirst.getPointsDeductYuan()
                        + " 贵票在前=" + priceyFirst.getPointsDeductYuan());
        assertEquals(0, cheapFirst.getPayAmount().compareTo(priceyFirst.getPayAmount()),
                "实付随排序变了:便宜票在前=" + cheapFirst.getPayAmount()
                        + " 贵票在前=" + priceyFirst.getPayAmount());
    }

    @Test
    void pointsCapStillHonouredWhenOrderIsCheaperThanCap() {
        // 负控:整单 ¥20 < 封顶 ¥50 时,只能抵 ¥20(不能倒贴)。修复不该把这条改坏。
        RegistrationQuoteResult q = quoteMulti(
                new ArrayList<>(Arrays.asList(item(CHEAP_TICKET, 1), item(CHEAP_TICKET, 1))), 1);

        assertEquals(0, new BigDecimal("20.00").compareTo(q.getPointsDeductYuan()),
                "整单 ¥20 时抵扣应被整单金额卡住,实际 " + q.getPointsDeductYuan());
        assertEquals(0, BigDecimal.ZERO.compareTo(q.getPayAmount()), "应实付 0");
    }

    // ---------- create 侧:必须与报价分毫不差,否则「报价=实付」的契约破了 ----------

    /** 建单需要比报价更多的 mapper(插入/扣库存/扣积分/积分流水)。 */
    private CmsRegistrationServiceImpl createService(CmsRegistrationMapper regMapper,
                                                     UmsMemberMapper memberMapper,
                                                     OmsTicketMapper ticketMapper) {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMemberId(MEMBER_ID);
        activity.setName("测试活动");
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        CmsRegistrationServiceImpl s = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(s, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(s, "platformMemberId", MEMBER_ID);
        ReflectionTestUtils.setField(s, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(s, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(s, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(s, "umsMemberPointsDetailMapper",
                mock(com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper.class));
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.1000"));
        when(ruleService.getActiveRule(0L, "activity")).thenReturn(rule);
        ReflectionTestUtils.setField(s, "settlementRuleService", ruleService);
        return s;
    }

    @Test
    void createMustChargeTheSameAsQuoteAndNotMisflagAsPointsOnlyOrder() {
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(RICH_POINTS);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(memberMapper.deductPoints(eq(MEMBER_ID), anyInt())).thenReturn(1);

        when(ticketMapper.selectOmsTicketById(CHEAP_TICKET)).thenReturn(ticket(CHEAP_TICKET, "10.00"));
        when(ticketMapper.selectOmsTicketById(PRICEY_TICKET)).thenReturn(ticket(PRICEY_TICKET, "200.00"));
        when(ticketMapper.deductInventory(anyLong(), anyLong())).thenReturn(1);
        when(regMapper.insertCmsRegistration(any())).thenReturn(1);

        CmsRegistrationServiceImpl s = createService(regMapper, memberMapper, ticketMapper);
        List<CmsRegistration> created = s.createMultiTicketRegistrations(
                MEMBER_ID, 2, ACTIVITY_ID, 1, "张三", "18800000000", null, null,
                new ArrayList<>(Arrays.asList(item(CHEAP_TICKET, 1), item(PRICEY_TICKET, 1))));

        CmsRegistration first = created.get(0);
        assertEquals(0, new BigDecimal("160.00").compareTo(first.getPayableAmount()),
                "首单应付应=整单210-抵扣50=160(与报价一致),实际 " + first.getPayableAmount());
        assertEquals(0, new BigDecimal("160.00").compareTo(first.getWechatPaymentAmount()),
                "首单微信应收应=160");
        // ★白嫖闸:抵扣按整单算后,首行 payable 会变负(10-50)而撞进 computePrice 的
        //  「payable<=0 → paymentType=0 纯积分单」分支。paymentType=0 会让控制器判定为零元单、
        //  直接伪造回调标记支付成功 ⇒ 用户 ¥160 一分没付拿到票。必须是 2(混合支付)。
        assertEquals(2, first.getPaymentType().intValue(),
                "首单支付方式应=2(混合:积分50+微信160),实际 " + first.getPaymentType()
                        + "(0=纯积分 ⇒ 会被判成零元单直接放行,白嫖)");
        assertEquals(2500L, first.getPointUsed().longValue(), "抵 ¥50 需 2500 积分(0.02/分)");
    }

    @Test
    void createDeductsPointsExactlyOnceForTheWholeOrder() {
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(RICH_POINTS);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(memberMapper.deductPoints(eq(MEMBER_ID), anyInt())).thenReturn(1);
        when(ticketMapper.selectOmsTicketById(CHEAP_TICKET)).thenReturn(ticket(CHEAP_TICKET, "10.00"));
        when(ticketMapper.selectOmsTicketById(PRICEY_TICKET)).thenReturn(ticket(PRICEY_TICKET, "200.00"));
        when(ticketMapper.deductInventory(anyLong(), anyLong())).thenReturn(1);
        when(regMapper.insertCmsRegistration(any())).thenReturn(1);

        CmsRegistrationServiceImpl s = createService(regMapper, memberMapper, ticketMapper);
        s.createMultiTicketRegistrations(MEMBER_ID, 2, ACTIVITY_ID, 1, "张三", "18800000000", null, null,
                new ArrayList<>(Arrays.asList(item(CHEAP_TICKET, 1), item(PRICEY_TICKET, 1))));

        // 整单只该扣一次积分,且扣的是整单抵扣对应的 2500 分(不是首行封顶的 500 分)
        verify(memberMapper, times(1)).deductPoints(MEMBER_ID, 2500);
    }

    @Test
    void freeFirstPaidSecondStillFreezesFeeForTheWholePayableOrder() {
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(ticketMapper.selectOmsTicketById(FREE_TICKET)).thenReturn(ticket(FREE_TICKET, "0.00"));
        when(ticketMapper.selectOmsTicketById(PRICEY_TICKET)).thenReturn(ticket(PRICEY_TICKET, "200.00"));
        when(ticketMapper.deductInventory(anyLong(), anyLong())).thenReturn(1);
        when(regMapper.insertCmsRegistration(any())).thenReturn(1);

        CmsRegistrationServiceImpl s = createService(regMapper, memberMapper, ticketMapper);
        List<CmsRegistration> created = s.createMultiTicketRegistrations(
                MEMBER_ID, 2, ACTIVITY_ID, 0, "张三", "18800000000", null, null,
                new ArrayList<>(Arrays.asList(item(FREE_TICKET, 1), item(PRICEY_TICKET, 1))));

        assertEquals(0, new BigDecimal("200.00").compareTo(created.get(0).getPayableAmount()));
        assertEquals(0, new BigDecimal("0.1000").compareTo(created.get(0).getFeePlatformRate()));
    }

    @Test
    void notUsingPointsDeductsNothing() {
        // 负控:不使用积分时抵扣恒 0,实付=整单票面。
        RegistrationQuoteResult q = quoteMulti(
                new ArrayList<>(Arrays.asList(item(CHEAP_TICKET, 1), item(PRICEY_TICKET, 1))), 0);

        assertEquals(0, BigDecimal.ZERO.compareTo(q.getPointsDeductYuan()), "没用积分不该有抵扣");
        assertEquals(0, new BigDecimal("210.00").compareTo(q.getPayAmount()), "实付应=整单票面 210");
    }
}
