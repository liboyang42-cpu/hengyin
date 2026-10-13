package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.domain.vo.ClubRegistrationRosterVO;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubRegistrationRosterPrivacyTest {

    private static final Long CLUB = 42L;
    private static final Long TOPIC = 77L;

    private ApiClubController controller;
    private IClubService clubService;
    private IClubMemberService memberService;
    private ICmsTopicService topicService;
    private IOmsTicketService ticketService;
    private IViewRegistrationService registrationService;
    private ICmsActivityService activityService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        topicService = mock(ICmsTopicService.class);
        ticketService = mock(IOmsTicketService.class);
        registrationService = mock(IViewRegistrationService.class);
        activityService = mock(ICmsActivityService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(controller, "viewRegistrationService", registrationService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
    }

    /**
     * 2026-08-26 管理员治理权:名册对管理员**放开为只读** —— 带队要按名单点人。
     * 原用例断言的是「管理员一律看不到」,那条产品决定已被推翻;这里改为断言
     * 「治理判据说不行就一定看不到」,守的仍是同一件事:名册不对普通成员开放。
     * ⚠️ 清退退款是另一个接口,仍然 owner-only,不在本用例范围。
     */
    @Test
    void rosterRejectsWhoeverFailsGovernanceCheck() {
        doReturn(7L).when(controller).getAppUserId();
        Club club = clubOwnedBy(9L);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(memberService.canGovernClub(club, 7L)).thenReturn(false);   // 普通成员

        AjaxResult result = controller.topicRegistrations(request());

        assertEquals("仅主理人或管理员可查看报名名册", result.get("msg"));
        verify(registrationService, never()).selectRegistrationSummaries(any());
    }

    /**
     * 管理员通过治理闸之后,继续走团归属校验 —— 用「团不属于本俱乐部」这条下游错误
     * 证明闸确实放行了。不断言 200:那需要把票/报名整条读链都桩起来,
     * 那是另一个用例的事(ownerRosterResponseUsesWhitelist... 已经覆盖)。
     */
    @Test
    void clubAdminPassesGovernanceGateAndReachesTopicOwnershipCheck() {
        doReturn(7L).when(controller).getAppUserId();
        Club club = clubOwnedBy(9L);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(memberService.canGovernClub(club, 7L)).thenReturn(true);    // role=1 管理员
        CmsTopic foreign = new CmsTopic();
        foreign.setId(TOPIC);
        foreign.setClubId(CLUB + 1);                                     // 别的俱乐部的团
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(foreign);

        AjaxResult result = controller.topicRegistrations(request());

        assertEquals("该团不属于本俱乐部", result.get("msg"),
                "管理员应当已经过了权限闸,卡在下游的团归属校验上");
    }

    @Test
    void executingClubWithEffectiveOrganizerOrderCanReadRoster() {
        doReturn(9L).when(controller).getAppUserId();
        Club executingClub = clubOwnedBy(9L);
        when(clubService.selectClubById(CLUB)).thenReturn(executingClub);
        when(memberService.canGovernClub(executingClub, 9L)).thenReturn(true);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(null);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(memberService.isTopicOrganizer(TOPIC, CLUB, 9L)).thenReturn(true);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        when(activityService.selectCmsActivityList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.topicRegistrations(request());

        assertEquals(200, result.get("code"));
        verify(memberService).isTopicOrganizer(TOPIC, CLUB, 9L);
    }

    /**
     * ★2026-09-11:名册原来只查主题票(owner_type=1),而俱乐部开一场会把主题票**复制**成
     * owner_type=2 的场次票,玩家买的是复制件 —— 于是这张名册在结构上恒为空,
     * 连带唯一入口在 enroll 页的核销详情页整页不可达。
     */
    @Test
    void rosterIncludesSessionTicketsNotJustTopicTickets() {
        doReturn(9L).when(controller).getAppUserId();
        Club ownerClub = clubOwnedBy(9L);
        when(clubService.selectClubById(CLUB)).thenReturn(ownerClub);
        when(memberService.canGovernClub(ownerClub, 9L)).thenReturn(true);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(CLUB);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsActivity session = new CmsActivity();
        session.setId(501L);
        session.setTopicId(TOPIC);
        session.setClubId(CLUB);
        when(activityService.selectCmsActivityList(any())).thenReturn(Collections.singletonList(session));

        OmsTicket sessionTicket = new OmsTicket();
        sessionTicket.setId(601L);
        sessionTicket.setName("玩家票");
        when(ticketService.selectOmsTicketList(any())).thenAnswer(invocation -> {
            OmsTicket probe = invocation.getArgument(0);
            // 主题票一张都没有(俱乐部开团后票都挂在场次上),只有场次票有
            if (Long.valueOf(2L).equals(probe.getOwnerType()) && Long.valueOf(501L).equals(probe.getOwnerId())) {
                return Collections.singletonList(sessionTicket);
            }
            return Collections.emptyList();
        });
        when(registrationService.selectRegistrationSummaries(any())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.topicRegistrations(request());

        assertEquals(200, result.get("code"));
        ClubRegistrationRosterVO roster = (ClubRegistrationRosterVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, roster.getOmsTicketList().size(),
                "★只查主题票的话这里是 0 —— 名册恒空,正是这条用例要挡住的回归");
        assertEquals(Long.valueOf(601L), roster.getOmsTicketList().get(0).getId());
    }

    @Test
    void ownerRosterResponseUsesWhitelistAndContainsNoFullPiiOrCodes() throws Exception {
        doReturn(9L).when(controller).getAppUserId();
        Club ownerClub = clubOwnedBy(9L);
        when(clubService.selectClubById(CLUB)).thenReturn(ownerClub);
        when(memberService.canGovernClub(ownerClub, 9L)).thenReturn(true);   // 主理人
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(CLUB);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        OmsTicket ticket = new OmsTicket();
        ticket.setId(88L);
        ticket.setName("玩家票");
        ticket.setMode(1);
        ticket.setTotalInventory(20L);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));
        ViewRegistration registration = new ViewRegistration();
        registration.setId(99L);
        registration.setMemberId(11L);
        registration.setAvatar("https://cdn/avatar.png");
        registration.setNickname("玩家甲");
        registration.setPaymentStatus(2);
        registration.setVerificationStatus(0);
        registration.setRealName("张敏感");
        registration.setPhone("13800138000");
        registration.setEmail("pii@example.com");
        registration.setAddress("敏感住址");
        registration.setTransactionId("WX-SECRET-TRANSACTION");
        registration.setVerificationCode("VERIFY-SECRET");
        registration.setQrcodeUrl("https://cdn/secret-qr.png");
        when(registrationService.selectRegistrationSummaries(any()))
                .thenReturn(Collections.singletonList(registration));

        AjaxResult result = controller.topicRegistrations(request());
        String json = new ObjectMapper().writeValueAsString(result);

        assertEquals(200, result.get("code"));
        assertTrue(((ClubRegistrationRosterVO) result.get(AjaxResult.DATA_TAG)).isCanRefund(),
                "主理人有清退退款权,canRefund 应为 true");
        assertFalse(json.contains("13800138000"), "响应不得包含手机号");
        assertFalse(json.contains("pii@example.com"), "响应不得包含邮箱");
        assertFalse(json.contains("敏感住址"), "响应不得包含地址");
        assertFalse(json.contains("WX-SECRET-TRANSACTION"), "响应不得包含交易号");
        assertFalse(json.contains("VERIFY-SECRET"), "响应不得包含核销码");
        assertFalse(json.contains("secret-qr"), "响应不得包含核销二维码");
    }

    /**
     * canRefund 是名册响应的权限声明,前端拿它决定退款按钮。清退退款是 owner-only,
     * 管理员的名册回包若写 canRefund=true,就是给管理员摆一个点了必被拒的假按钮。
     */
    @Test
    void adminRosterSaysRefundIsOwnerOnly() {
        doReturn(7L).when(controller).getAppUserId();
        Club club = clubOwnedBy(9L);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(memberService.canGovernClub(club, 7L)).thenReturn(true);   // role=1 管理员,非 owner
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(CLUB);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.<OmsTicket>emptyList());

        AjaxResult result = controller.topicRegistrations(request());

        assertEquals(200, result.get("code"));
        assertFalse(((ClubRegistrationRosterVO) result.get(AjaxResult.DATA_TAG)).isCanRefund(),
                "管理员没有清退退款权,canRefund 不得撒谎");
    }

    private Map<String, Object> request() {
        Map<String, Object> body = new HashMap<>();
        body.put("clubId", CLUB);
        body.put("topicId", TOPIC);
        return body;
    }

    private Club clubOwnedBy(Long ownerId) {
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(ownerId);
        return club;
    }
}
