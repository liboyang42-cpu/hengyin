package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报价侧的两条营销合同:
 * <ol>
 *   <li><b>数量</b>:每行张数不得超过剩余库存 —— 报价页就要说"买不了",不能等下单才失败。
 *       并发下的唯一权威仍是建单侧的 deductInventory 条件 UPDATE;这里只测预检。</li>
 *   <li><b>完局奖励券投影</b>:报价结果可以告诉玩家"走完能拿到什么券",但券不是折扣 ——
 *       实付与 couponDeductYuan(=0)不得因它变化,发放仍在服务端真实完局时按日志判重执行。</li>
 * </ol>
 */
class RegistrationQuoteMarketingContractTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TOPIC_ID = 500L;
    private static final Long TICKET_ID = 301L;
    private static final Long COUPON_ID = 700L;

    // ---------- 报价侧 ----------

    @Test
    void quoteRejectsQuantityAboveRemainingInventory() {
        CmsRegistrationServiceImpl service = quoteService(2L, sellingTopic(null), null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, null,
                new ArrayList<>(Arrays.asList(item(TICKET_ID, 3))), 0));
        assertEquals("库存不足,仅剩 2 张", ex.getMessage());
    }

    @Test
    void quoteAcceptsQuantityWithinRemainingInventory() {
        CmsRegistrationServiceImpl service = quoteService(2L, sellingTopic(null), null);

        RegistrationQuoteResult q = service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, null,
                new ArrayList<>(Arrays.asList(item(TICKET_ID, 2))), 0);

        assertEquals(0, new BigDecimal("200.00").compareTo(q.getPayAmount()),
                "2 张 × ¥100 应报 ¥200,实际 " + q.getPayAmount());
    }

    @Test
    void quoteAggregatesSameTicketAcrossLinesBeforeJudgingInventory() {
        // 同一票种拆成两行(按参与日期分行):2+2=4 张 > 余票 3 —— 每行单独看都"够",整单却买不满
        CmsRegistrationServiceImpl service = quoteService(3L, sellingTopic(null), null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, null,
                new ArrayList<>(Arrays.asList(item(TICKET_ID, 2), item(TICKET_ID, 2))), 0));
        assertEquals("库存不足,仅剩 3 张", ex.getMessage());
    }

    @Test
    void quoteProjectsConfiguredCompletionRewardCoupon() {
        SmsCoupon reward = rewardCoupon(1, "20.00", "100.00");
        CmsRegistrationServiceImpl service = quoteService(9999L, sellingTopic(COUPON_ID), reward);

        RegistrationQuoteResult q = service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0);

        assertNotNull(q.getCompleteRewardCoupon(), "主题配了完局奖励券,报价应投影出来");
        assertEquals(COUPON_ID, q.getCompleteRewardCoupon().getId());
        assertEquals(0, new BigDecimal("20.00").compareTo(q.getCompleteRewardCoupon().getAmount()));
        // 「满X可用」门槛按既有拍板不从公开奖励投影下发(见 PublicDetailVoBoundaryTest),
        // 券的公开投影只回答"是什么/值多少/什么时候能用"。
        // 它不是折扣:实付仍是票面,券抵扣恒为 0
        assertEquals(0, new BigDecimal("100.00").compareTo(q.getPayAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(q.getCouponDeductYuan()));
    }

    @Test
    void quoteStopsPromisingCompletionCouponOnceThePlayerAlreadyGotIt() {
        // 通关券发放日志(主题+会员)非空 = 这张券真发过 ⇒ 复购时再说"走完可得"就是假承诺
        CmsRegistrationServiceImpl service = quoteService(9999L, sellingTopic(COUPON_ID),
                rewardCoupon(1, "20.00", null));
        com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper rewardLog =
                mock(com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper.class);
        when(rewardLog.selectCouponId(TOPIC_ID, MEMBER_ID)).thenReturn(COUPON_ID);
        ReflectionTestUtils.setField(service, "completionCouponRewardMapper", rewardLog);

        assertNull(service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0)
                .getCompleteRewardCoupon());
    }

    @Test
    void quoteHidesCompletionRewardCouponThatCanNoLongerBeGranted() {
        // status=4 商家停发:玩家已无法再获得,继续挂"走完可得"就是空头支票
        CmsRegistrationServiceImpl service = quoteService(9999L, sellingTopic(COUPON_ID), rewardCoupon(4, "20.00", null));
        assertNull(service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0)
                .getCompleteRewardCoupon());

        // status=3 平台手动失效
        CmsRegistrationServiceImpl invalidated =
                quoteService(9999L, sellingTopic(COUPON_ID), rewardCoupon(3, "20.00", null));
        assertNull(invalidated.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0)
                .getCompleteRewardCoupon());
    }

    @Test
    void quoteWithoutConfiguredRewardProjectsNull() {
        CmsRegistrationServiceImpl service = quoteService(9999L, sellingTopic(null), null);
        assertNull(service.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0)
                .getCompleteRewardCoupon());
    }

    // ---------- 建单侧:同一句判定,先于库存 CAS ----------

    @Test
    void createRejectsQuantityAboveRemainingInventoryWithSameWording() {
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(memberMapper.deductPoints(eq(MEMBER_ID), anyInt())).thenReturn(1);
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket(1L, "100.00"));
        when(regMapper.insertCmsRegistration(any())).thenReturn(1);

        CmsRegistrationServiceImpl service = createService(regMapper, memberMapper, ticketMapper);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.createMultiTicketRegistrations(
                MEMBER_ID, 2, ACTIVITY_ID, 0, "张三", "18800000000", null, null,
                new ArrayList<>(Arrays.asList(item(TICKET_ID, 2)))));
        assertEquals("库存不足,仅剩 1 张", ex.getMessage(),
                "建单与报价必须同一文案;而且必须早于 deductInventory 的并发扣减");
    }

    // ---------- fixture ----------

    private CmsRegistrationServiceImpl quoteService(long remaining, CmsTopic topic, SmsCoupon rewardCoupon) {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        CommercialTopicPolicy commercialPolicy = mock(CommercialTopicPolicy.class);
        IProductRoleService productRoleService = mock(IProductRoleService.class);
        ISmsCouponService couponService = mock(ISmsCouponService.class);

        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity());
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket(remaining, "100.00"));
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member());
        when(commercialPolicy.isFreeTopic(topic)).thenReturn(false);
        when(productRoleService.isPlatformOwned(TOPIC_ID)).thenReturn(true);
        if (rewardCoupon != null) {
            when(couponService.selectSmsCouponById(COUPON_ID)).thenReturn(rewardCoupon);
        }

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "commercialTopicPolicy", commercialPolicy);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "smsCouponService", couponService);
        ReflectionTestUtils.setField(service, "platformMemberId", MEMBER_ID);
        return service;
    }

    /** 建单需要比报价更多的 mapper(插入/扣库存/积分流水/费率)。 */
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

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "platformMemberId", MEMBER_ID);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "umsMemberPointsDetailMapper",
                mock(com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper.class));
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.1000"));
        when(ruleService.getActiveRule(0L, "activity")).thenReturn(rule);
        ReflectionTestUtils.setField(service, "settlementRuleService", ruleService);
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

    private CmsTopic sellingTopic(Long completeRewardCouponId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        topic.setLifecycle(3);
        topic.setSellingStartedAt(new Date(System.currentTimeMillis() - 3_600_000L));
        topic.setSaleEndAt(new Date(System.currentTimeMillis() + 3_600_000L));
        topic.setCompleteRewardCouponId(completeRewardCouponId);
        return topic;
    }

    private OmsTicket ticket(long remaining, String price) {
        OmsTicket t = new OmsTicket();
        t.setId(TICKET_ID);
        t.setOwnerId(ACTIVITY_ID);
        t.setOwnerType(2L);
        t.setDelFlag(0);
        t.setRemainingInventory(remaining);
        t.setPrice(new BigDecimal(price));
        return t;
    }

    private UmsMember member() {
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        return member;
    }

    private SmsCoupon rewardCoupon(int status, String amount, String minPoint) {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(COUPON_ID);
        coupon.setName("完局到店券");
        coupon.setCouponType(1);
        coupon.setAmount(new BigDecimal(amount));
        coupon.setMinPoint(minPoint == null ? null : new BigDecimal(minPoint));
        coupon.setStatus(status);
        coupon.setDelFlag(0);
        return coupon;
    }

    private RegistrationTicketItem item(Long ticketId, int count) {
        RegistrationTicketItem it = new RegistrationTicketItem();
        it.setTicketId(ticketId);
        it.setCount(count);
        return it;
    }
}
