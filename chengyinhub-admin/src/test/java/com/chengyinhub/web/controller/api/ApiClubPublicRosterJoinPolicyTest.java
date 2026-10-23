package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拍板 2026-10-22(L06 俱乐部):
 * ① 公开加入(join_policy=0)的团,登录访客不入团也能看成员名单与贡献榜;需审核的团仍锁「通过审核后可看」;
 *   被封禁者、不对外可见的团不放。
 * ② 商家可以加入俱乐部(不再「商家不能加入俱乐部」)。
 * ③ 需审核的团申请留言必填、最多 60 字。
 */
class ApiClubPublicRosterJoinPolicyTest {

    private static final Long CLUB_ID = 73L;
    private static final Long OWNER_ID = 9L;
    private static final Long VIEWER_ID = 42L;

    private IClubService clubService;
    private IClubMemberService memberService;
    private IClubParticipationGuard participationGuard;
    private ICapabilityService capabilityService;
    private ApiClubController controller;

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        participationGuard = mock(IClubParticipationGuard.class);
        capabilityService = mock(ICapabilityService.class);
        controller = spy(new ApiClubController());
        doReturn(VIEWER_ID).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "clubParticipationGuard", participationGuard);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club());
        when(clubService.isJoinPolicySupported()).thenReturn(true);
        when(memberService.selectByClubAndMember(anyLong(), anyLong())).thenReturn(null);
        when(memberService.selectClubMemberList(any(ClubMember.class))).thenReturn(new java.util.ArrayList<>());
        when(memberService.selectClubLeaderboard(eq(CLUB_ID), any())).thenReturn(Collections.emptyList());
    }

    private Club club() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(OWNER_ID);
        club.setStatus(1L);
        return club;
    }

    private AjaxResult members() {
        ClubMember body = new ClubMember();
        body.setClubId(CLUB_ID);
        return controller.members(body);
    }

    private AjaxResult leaderboard() {
        Map<String, Object> body = new HashMap<>();
        body.put("id", CLUB_ID);
        return controller.leaderboard(body);
    }

    @Test
    void publicClubShowsRosterAndLeaderboardToNonMembers() {
        when(clubService.selectJoinPolicy(CLUB_ID)).thenReturn(0);
        assertEquals(200, members().get("code"));
        assertEquals(200, leaderboard().get("code"));
    }

    @Test
    void approvalClubKeepsRosterAndLeaderboardLockedUntilApproved() {
        when(clubService.selectJoinPolicy(CLUB_ID)).thenReturn(1);
        AjaxResult m = members();
        AjaxResult l = leaderboard();
        assertEquals(500, m.get("code"));
        assertEquals("通过审核后可看成员名单", m.get("msg"));
        assertEquals(500, l.get("code"));
        assertEquals("通过审核后可看贡献榜", l.get("msg"));
        verify(memberService, never()).selectClubLeaderboard(anyLong(), any());
    }

    @Test
    void approvedMemberOfApprovalClubStillSeesRoster() {
        when(clubService.selectJoinPolicy(CLUB_ID)).thenReturn(1);
        when(memberService.selectByClubAndMember(CLUB_ID, VIEWER_ID)).thenReturn(new ClubMember());
        assertEquals(200, members().get("code"));
        assertEquals(200, leaderboard().get("code"));
    }

    @Test
    void bannedViewerCannotBrowsePublicRoster() {
        when(clubService.selectJoinPolicy(CLUB_ID)).thenReturn(0);
        doThrow(new ServiceException("你已被限制参与该俱乐部")).when(participationGuard).requireAllowed(CLUB_ID, VIEWER_ID);
        assertEquals(500, members().get("code"));
        assertEquals(500, leaderboard().get("code"));
    }

    @Test
    void hiddenPublicClubStaysClosedToOutsiders() {
        Club hidden = club();
        hidden.setPublicVisible(0);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(hidden);
        when(memberService.selectMembershipRecord(CLUB_ID, VIEWER_ID)).thenReturn(null);
        when(clubService.selectJoinPolicy(CLUB_ID)).thenReturn(0);
        assertEquals(500, members().get("code"));
        assertEquals(500, leaderboard().get("code"));
    }

    @Test
    void merchantCanJoinClub() {
        when(capabilityService.hasCapability(VIEWER_ID, MemberCapability.CAP_MERCHANT)).thenReturn(true);
        when(clubService.selectJoinPolicy(CLUB_ID)).thenReturn(0);
        when(memberService.requestJoin(eq(CLUB_ID), eq(VIEWER_ID), any())).thenReturn(IClubMemberService.JOIN_STATE_JOINED);
        Club body = new Club();
        body.setId(CLUB_ID);
        AjaxResult result = controller.join(body);
        assertEquals(200, result.get("code"));
        assertEquals("加入成功", result.get("msg"));
    }

    @Test
    void approvalClubRequiresJoinMessageUpTo60Chars() {
        when(clubService.selectJoinPolicy(CLUB_ID)).thenReturn(1);
        Club blank = new Club();
        blank.setId(CLUB_ID);
        blank.setJoinMessage("   ");
        assertEquals("请先给主理人写一句申请留言", controller.join(blank).get("msg"));

        Club tooLong = new Club();
        tooLong.setId(CLUB_ID);
        tooLong.setJoinMessage("长".repeat(61));
        assertEquals("申请留言最多 60 字", controller.join(tooLong).get("msg"));
        verify(memberService, never()).requestJoin(anyLong(), anyLong(), anyString());
        verify(memberService, never()).requestJoin(anyLong(), anyLong(), isNull());

        when(memberService.requestJoin(CLUB_ID, VIEWER_ID, "朋友推荐来的")).thenReturn(IClubMemberService.JOIN_STATE_PENDING);
        Club ok = new Club();
        ok.setId(CLUB_ID);
        ok.setJoinMessage("朋友推荐来的");
        assertEquals("申请已提交，等待主理人审核", controller.join(ok).get("msg"));
    }

    @Test
    void publicClubJoinNeedsNoMessage() {
        when(clubService.selectJoinPolicy(CLUB_ID)).thenReturn(0);
        when(memberService.requestJoin(eq(CLUB_ID), eq(VIEWER_ID), isNull())).thenReturn(IClubMemberService.JOIN_STATE_JOINED);
        Club body = new Club();
        body.setId(CLUB_ID);
        assertEquals(200, controller.join(body).get("code"));
    }
}
