package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ISettlementRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §6.9 俱乐部经营配置：价格、成员资格和保留名额必须都由服务端裁决。
 */
class ClubOperationBenefitTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long CLUB_ID = 300L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TICKET_ID = 400L;

    /**
     * [2026-08-12 拍板] 俱乐部会员价**全局停用** —— 会员与非会员拿到同一个价。
     *
     * <p>本用例原本断言会员报价 ¥60、优惠 ¥40(库里 memberDiscountPrice=60、票价 100);
     * 停用后必须是原价 ¥100、优惠 ¥0。**断言反过来正是这次改动的验收**,
     * 而不是"测试挂了顺手改数" —— 改回 `club.getMemberDiscountPrice()` 这条会立刻红。
     *
     * <p>⚠️ 只停用**价格**:会员身份识别与优先报名/保留名额是独立能力,必须仍然生效,
     * 俱乐部活动另按报名权限规则拒绝非成员；自玩票仍保留独立的名额检查。
     */
    @Test
    void clubMemberPaysSamePriceAsNonMemberAfterDiscountDisabled() {
        Fixture fixture = fixture(2L, true);

        RegistrationQuoteResult quote = fixture.service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0);

        assertEquals(0, new BigDecimal("100.00").compareTo(quote.getPayAmount()),
                "会员价已全局停用,会员必须按原价 ¥100 报价");
        assertEquals(0, BigDecimal.ZERO.compareTo(quote.getMemberDiscountYuan()),
                "优惠金额必须为 0");
        assertTrue(quote.getClubMember(), "会员身份识别不受影响");
        assertTrue(quote.getPrioritySignupEnabled(), "优先报名/保留名额不受影响");
    }

    @Test
    void nonMemberCannotQuoteClubActivityEvenWhenReservedSeatsRemain() {
        Fixture fixture = fixture(2L, false);

        RuntimeException error = assertThrows(RuntimeException.class, () -> fixture.service.quoteRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, 0));

        assertTrue(error.getMessage().contains("请先加入俱乐部再报名"));
    }

    @Test
    void activityCreatorWithoutMembershipUsesAtomicReservedInventoryDeduction() {
        Fixture fixture = fixture(3L, false);
        fixture.activityMapper.selectCmsActivityById(ACTIVITY_ID).setMemberId(MEMBER_ID);
        ReflectionTestUtils.setField(fixture.service, "platformMemberId", MEMBER_ID);
        when(fixture.ticketMapper.deductInventoryKeepingReserved(TICKET_ID, 1L, 2)).thenReturn(1);
        when(fixture.registrationMapper.insertCmsRegistration(org.mockito.ArgumentMatchers.any(CmsRegistration.class)))
                .thenReturn(1);

        CmsRegistration registration = fixture.service.createRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, 0,
                "张三", "18800000000", null, null);

        assertEquals(0, new BigDecimal("100.00").compareTo(registration.getPayableAmount()));
        verify(fixture.ticketMapper).deductInventoryKeepingReserved(eq(TICKET_ID), eq(1L), eq(2));
        verify(fixture.ticketMapper, never()).deductInventory(TICKET_ID, 1L);
    }

    @Test
    void nonMemberCannotCreateClubActivityOrderEvenWithUnreservedInventory() {
        Fixture fixture = fixture(3L, false);
        RuntimeException error = assertThrows(RuntimeException.class, () -> fixture.service.createRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, 0,
                "张三", "18800000000", null, null));
        assertTrue(error.getMessage().contains("请先加入俱乐部再报名"));
        verify(fixture.ticketMapper, never()).deductInventoryKeepingReserved(TICKET_ID, 1L, 2);
        verify(fixture.ticketMapper, never()).deductInventory(TICKET_ID, 1L);
        verify(fixture.registrationMapper, never()).insertCmsRegistration(
                org.mockito.ArgumentMatchers.any(CmsRegistration.class));
    }

    @Test
    void registrationCannotBeCreatedAfterOwningClubDissolves() {
        Fixture fixture = fixture(3L, true);
        when(fixture.clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(null);

        RuntimeException error = assertThrows(RuntimeException.class, () -> fixture.service.createRegistration(
                MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, 0,
                "张三", "18800000000", null, null));

        assertTrue(error.getMessage().contains("关联俱乐部已解散"),
                "该红必须指认到报名写入的解散互斥闸，实际:" + error.getMessage());
        verify(fixture.registrationMapper, never()).insertCmsRegistration(
                org.mockito.ArgumentMatchers.any(CmsRegistration.class));
    }

    /** 自玩票是 effectivePrice 的另一个调用点,同样必须退化成原价 —— 否则"一处收口"就是假的。 */
    @Test
    void clubMemberSelfPlayPassAlsoPaysOriginalPrice() {
        Fixture fixture = fixture(2L, true);
        CmsTopic topic = new CmsTopic();
        topic.setId(500L);
        topic.setClubId(CLUB_ID);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        markSelling(topic);
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("100.00"));
        topic.setFeePlatformRate(new BigDecimal("0.1000"));
        topic.setSelfPlayQuota(2);
        when(fixture.topicMapper.selectCmsTopicByIdForUpdate(500L)).thenReturn(topic);
        when(fixture.productRoleService.isPlatformOwned(500L)).thenReturn(true);
        when(fixture.registrationMapper.countActiveSelfPlayPasses(500L)).thenReturn(0);
        when(fixture.registrationMapper.insertCmsRegistration(org.mockito.ArgumentMatchers.any(CmsRegistration.class)))
                .thenReturn(1);

        CmsRegistration pass = fixture.service.createSelfPlayPass(MEMBER_ID, 500L, "张三", "18800000000");

        assertEquals(0, new BigDecimal("100.00").compareTo(pass.getOriginalAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(pass.getDiscountAmount()),
                "会员价已停用,自玩票优惠必须为 0");
        assertEquals(0, new BigDecimal("100.00").compareTo(pass.getPayableAmount()),
                "会员按原价付自玩票");
    }

    @Test
    void nonMemberCannotUseSelfPlayQuotaReservedForClubMembers() {
        Fixture fixture = fixture(2L, false);
        CmsTopic topic = new CmsTopic();
        topic.setId(501L);
        topic.setClubId(CLUB_ID);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        markSelling(topic);
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("100.00"));
        topic.setFeePlatformRate(new BigDecimal("0.1000"));
        topic.setSelfPlayQuota(2);
        when(fixture.topicMapper.selectCmsTopicByIdForUpdate(501L)).thenReturn(topic);
        when(fixture.registrationMapper.countActiveSelfPlayPasses(501L)).thenReturn(1);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> fixture.service.createSelfPlayPass(MEMBER_ID, 501L, "张三", "18800000000"));

        assertTrue(error.getMessage().contains("会员保留"));
    }

    @Test
    void selfPlayQuotaChecksAfterTopicLockAndBeforeInsert() {
        Fixture fixture = fixture(2L, true);
        CmsTopic topic = selfPlayTopic(502L);
        when(fixture.topicMapper.selectCmsTopicByIdForUpdate(502L)).thenReturn(topic);
        when(fixture.registrationMapper.countActiveSelfPlayPasses(502L)).thenReturn(0);
        when(fixture.productRoleService.isPlatformOwned(502L)).thenReturn(true);
        when(fixture.registrationMapper.insertCmsRegistration(org.mockito.ArgumentMatchers.any(CmsRegistration.class)))
                .thenReturn(1);

        fixture.service.createSelfPlayPass(MEMBER_ID, 502L, "张三", "18800000000");

        org.mockito.InOrder order = inOrder(fixture.topicMapper, fixture.registrationMapper);
        order.verify(fixture.topicMapper).selectCmsTopicByIdForUpdate(502L);
        order.verify(fixture.registrationMapper).countActiveSelfPlayPasses(502L);
        order.verify(fixture.registrationMapper).insertCmsRegistration(org.mockito.ArgumentMatchers.any(CmsRegistration.class));
    }

    private CmsTopic selfPlayTopic(Long topicId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(topicId);
        topic.setClubId(CLUB_ID);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        markSelling(topic);
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("100.00"));
        topic.setFeePlatformRate(new BigDecimal("0.1000"));
        topic.setSelfPlayQuota(2);
        return topic;
    }

    private static void markSelling(CmsTopic topic) {
        topic.setLifecycle(3);
        topic.setSellingStartedAt(new java.util.Date(System.currentTimeMillis() - 60_000L));
        topic.setSaleEndAt(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
    }

    private Fixture fixture(long remainingInventory, boolean isClubMember) {
        Fixture fixture = new Fixture();
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMemberId(999L);
        activity.setClubId(CLUB_ID);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(fixture.activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        when(fixture.memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);

        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setOwnerId(ACTIVITY_ID);
        ticket.setOwnerType(2L);
        ticket.setDelFlag(0);
        ticket.setRemainingInventory(remainingInventory);
        ticket.setPrice(new BigDecimal("100.00"));
        when(fixture.ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket);

        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(999L);
        club.setStatus(1L);
        club.setPrioritySignupEnabled(1);
        club.setMemberDiscountPrice(new BigDecimal("60.00"));
        club.setMemberReservedQuota(2);
        when(fixture.clubMapper.selectClubById(CLUB_ID)).thenReturn(club);
        when(fixture.clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        SettlementRule settlementRule = new SettlementRule();
        settlementRule.setPlatformCommissionRate(new BigDecimal("0.1000"));
        when(fixture.settlementRuleService.getActiveRule(0L, "activity")).thenReturn(settlementRule);

        if (isClubMember) {
            ClubMember membership = new ClubMember();
            membership.setStatus(1);
            membership.setDelFlag(0);
            when(fixture.clubMemberMapper.selectByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(membership);
        }

        fixture.service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(fixture.service, "cmsActivityMapper", fixture.activityMapper);
        ReflectionTestUtils.setField(fixture.service, "platformMemberId", 999L);
        ReflectionTestUtils.setField(fixture.service, "umsMemberMapper", fixture.memberMapper);
        ReflectionTestUtils.setField(fixture.service, "omsTicketMapper", fixture.ticketMapper);
        ReflectionTestUtils.setField(fixture.service, "clubMapper", fixture.clubMapper);
        ReflectionTestUtils.setField(fixture.service, "clubMemberMapper", fixture.clubMemberMapper);
        ReflectionTestUtils.setField(fixture.service, "cmsRegistrationMapper", fixture.registrationMapper);
        ReflectionTestUtils.setField(fixture.service, "cmsTopicMapper", fixture.topicMapper);
        ReflectionTestUtils.setField(fixture.service, "productRoleService", fixture.productRoleService);
        ReflectionTestUtils.setField(fixture.service, "settlementRuleService", fixture.settlementRuleService);
        return fixture;
    }

    private static class Fixture {
        final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        final UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        final OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        final ClubMapper clubMapper = mock(ClubMapper.class);
        final ClubMemberMapper clubMemberMapper = mock(ClubMemberMapper.class);
        final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        final com.chengyinhub.business.service.IProductRoleService productRoleService =
                mock(com.chengyinhub.business.service.IProductRoleService.class);
        final ISettlementRuleService settlementRuleService = mock(ISettlementRuleService.class);
        CmsRegistrationServiceImpl service;
    }
}
