package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.appgaps.ClubProxyEnrollService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.dto.ClubAttendanceCorrectionRequest;
import com.chengyinhub.business.domain.dto.ClubEventCampaignRequest;
import com.chengyinhub.business.domain.dto.ClubEventScopeRequest;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.ClubWaitlistRequest;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IGroupCodeService;
import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * App {@code Core/ClubGapClient} short names: one test per route. Aliases hand the exact app body to the long-path
 * controller (same login, permission and error rules); proxy-enroll, workbench and settlement export check
 * permissions here first; quality evidence is retired (410).
 */
class ApiClubGapControllerTest {
    private static final long CLUB = 7001L, ACTIVITY = 992031L, OWNER = 9003L, MEMBER = 9004L;

    private ApiClubEventOpsController eventOps;
    private ApiClubEventNotificationController notices;
    private ApiClubController clubs;
    private ApiClubSettlementController settlements;
    private IGroupCodeService groupCodes;
    private ChannelPlatformService channels;
    private IClubAccessService access;
    private IClubService clubService;
    private IClubMemberService clubMembers;
    private ClubProxyEnrollService proxyEnroll;
    private ApiClubGapController controller;
    private Long me;

    @BeforeEach
    void setUp() {
        eventOps = mock(ApiClubEventOpsController.class);
        notices = mock(ApiClubEventNotificationController.class);
        clubs = mock(ApiClubController.class);
        settlements = mock(ApiClubSettlementController.class);
        groupCodes = mock(IGroupCodeService.class);
        channels = mock(ChannelPlatformService.class);
        access = mock(IClubAccessService.class);
        clubService = mock(IClubService.class);
        clubMembers = mock(IClubMemberService.class);
        proxyEnroll = mock(ClubProxyEnrollService.class);
        me = OWNER;
        controller = new ApiClubGapController(eventOps, notices, clubs, settlements, groupCodes, channels, access,
                clubService, clubMembers, proxyEnroll) {
            @Override
            public Long getAppUserId() {
                if (me == null) throw new IllegalStateException("未登录");
                return me;
            }
        };
    }

    // ---- aliases: the exact app body reaches the long-path controller ----

    @Test
    void activityJoinIsTheWaitlistJoin() {
        ClubWaitlistRequest body = new ClubWaitlistRequest();
        body.setActivityId(ACTIVITY);
        AjaxResult ok = AjaxResult.success();
        when(eventOps.joinWaitlist(body)).thenReturn(ok);
        assertSame(ok, controller.activityJoin(body));
    }

    @Test
    void activityCancelIsTheWaitlistCancel() {
        ClubWaitlistRequest body = new ClubWaitlistRequest();
        body.setActivityId(ACTIVITY);
        AjaxResult ok = AjaxResult.success();
        when(eventOps.cancelWaitlist(body)).thenReturn(ok);
        assertSame(ok, controller.activityCancel(body));
    }

    @Test
    void activityDetailIsTheOccurrenceStatus() {
        ClubEventScopeRequest body = new ClubEventScopeRequest();
        body.setClubId(CLUB);
        body.setActivityId(ACTIVITY);
        AjaxResult ok = AjaxResult.success();
        when(eventOps.occurrenceStatus(body)).thenReturn(ok);
        assertSame(ok, controller.activityDetail(body));
    }

    @Test
    void eventsIsTheSeriesList() {
        Map<String, Object> body = Map.of("clubId", CLUB);
        AjaxResult ok = AjaxResult.success();
        when(eventOps.listSeries(body)).thenReturn(ok);
        assertSame(ok, controller.events(body));
    }

    @Test
    void eventsSaveIsTheSeriesCreate() {
        ClubEventSeriesRequest body = new ClubEventSeriesRequest();
        body.setClubId(CLUB);
        body.setRequestId("series-1");
        AjaxResult ok = AjaxResult.success();
        when(eventOps.createSeries(body)).thenReturn(ok);
        assertSame(ok, controller.eventsSave(body));
    }

    @Test
    void eventsCheckinIsTheAttendanceCorrection() {
        ClubAttendanceCorrectionRequest body = new ClubAttendanceCorrectionRequest();
        body.setClubId(CLUB);
        body.setRequestId("req-1");
        AjaxResult conflict = new AjaxResult(409, "版本冲突");
        when(eventOps.correctAttendance(body)).thenReturn(conflict);
        assertSame(conflict, controller.eventsCheckin(body), "the long path's refusal is returned unchanged");
    }

    @Test
    void applyIsTheClubJoinWithTheMessage() {
        Club body = new Club();
        body.setId(CLUB);
        body.setJoinMessage("想一起跑");
        AjaxResult pending = AjaxResult.success("申请已提交，等待主理人审核");
        when(clubs.join(body)).thenReturn(pending);
        assertSame(pending, controller.apply(body));
        controller.apply(null);
        verify(clubs).join(argThat((Club c) -> c != body && c.getId() == null));
    }

    @Test
    void noticesIsTheAudienceCount() {
        ClubEventCampaignRequest body = new ClubEventCampaignRequest();
        body.setClubId(CLUB);
        AjaxResult ok = AjaxResult.success();
        when(notices.audienceCounts(body)).thenReturn(ok);
        assertSame(ok, controller.noticeAudience(body));
    }

    @Test
    void noticesSendIsTheCampaignSend() {
        ClubEventCampaignRequest body = new ClubEventCampaignRequest();
        body.setClubId(CLUB);
        body.setAudienceType("ALL_MEMBERS");
        body.setRequestId("notice-1");
        AjaxResult ok = AjaxResult.success();
        when(notices.send(body)).thenReturn(ok);
        assertSame(ok, controller.noticeSend(body));
    }

    @Test
    void groupCodeReadsEligibilityAndSaveIssuesFromTheJsonBody() {
        AjaxResult eligible = AjaxResult.success();
        AjaxResult issued = AjaxResult.success();
        when(groupCodes.status(ACTIVITY, OWNER)).thenReturn(eligible);
        when(groupCodes.issue(ACTIVITY, OWNER)).thenReturn(issued);
        assertSame(eligible, controller.groupCode(Map.of("activityId", ACTIVITY)));
        assertSame(issued, controller.groupCodeSave(Map.of("activityId", ACTIVITY)));
        me = null;
        controller.groupCodeSave(Map.of("activityId", ACTIVITY));
        verify(groupCodes).issue(ACTIVITY, 0L);
        controller.groupCode(Map.of("activityId", true));
        verify(groupCodes).status(null, 0L);
    }

    @Test
    void clubChannelsIsTheClubGuildDirectory() {
        Map<String, Object> directory = Map.of("categories", List.of());
        when(channels.directory(OWNER, 1, CLUB, false)).thenReturn(directory);
        ResponseEntity<AjaxResult> r = controller.clubChannels(Map.of("clubId", CLUB));
        assertEquals(200, r.getStatusCode().value());
        assertSame(directory, r.getBody().get("data"));
        assertEquals(400, assertThrows(AppGapException.class, () -> controller.clubChannels(Map.of())).getStatus());
    }

    // ---- new behaviour ----

    @Test
    void qualityEvidenceIsRetiredBeforeLogin() {
        AjaxResult r = new ApiClubCompensationController(null, null, null).qualityEvidence();
        assertEquals(410, r.get(AjaxResult.CODE_TAG));
        assertEquals("QUALITY_EVIDENCE_RETIRED", r.get("errorCode"));
    }

    @Test
    void proxyEnrollNeedsActivityManageBeforeAnyWrite() {
        Map<String, Object> body = Map.of("clubId", CLUB, "activityId", ACTIVITY, "memberId", MEMBER);
        Map<String, Object> invited = Map.of("state", "INVITED");
        when(proxyEnroll.propose(OWNER, CLUB, ACTIVITY, MEMBER)).thenReturn(invited);
        assertSame(invited, controller.proxyEnroll(body).get("data"));
        verify(access).require(OWNER, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE);

        me = MEMBER;
        when(access.require(MEMBER, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE))
                .thenThrow(new ServiceException("俱乐部身份无效或权限不足", 403));
        AppGapException denied = assertThrows(AppGapException.class, () -> controller.proxyEnroll(body));
        assertEquals(403, denied.getStatus());
        assertEquals("CLUB_FORBIDDEN", denied.getErrorCode());
        verify(proxyEnroll, never()).propose(eq(MEMBER), any(), any(), any());

        me = null;
        assertEquals(401, assertThrows(AppGapException.class, () -> controller.proxyEnroll(body)).getStatus());
        me = OWNER;
        assertEquals(400, assertThrows(AppGapException.class, () -> controller.proxyEnroll(Map.of("clubId", CLUB))).getStatus());
    }

    @Test
    void proxyEnrollMineIsTheCallersOwnInvitations() {
        me = MEMBER;
        Map<String, Object> mine = Map.of("items", List.of());
        when(proxyEnroll.mine(MEMBER)).thenReturn(mine);
        assertSame(mine, controller.proxyEnrollMine().get("data"));
    }

    @Test
    void workbenchListsOnlyTheToolsTheCallerHolds() {
        ClubAccessContext owner = context(OWNER,
                Set.of(ClubPermission.CLUB_READ, ClubPermission.MEMBER_APPROVE, ClubPermission.ACTIVITY_MANAGE, ClubPermission.FINANCE_READ));
        when(access.resolveWithEventAccesses(OWNER, CLUB)).thenReturn(owner);
        Club club = new Club();
        club.setId(CLUB);
        club.setName("老城跑团");
        club.setMemberId(OWNER);
        club.setStatus(1L);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(clubMembers.countByClub(CLUB)).thenReturn(3);
        when(clubMembers.countPendingJoinRequests(CLUB)).thenReturn(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) controller.workbench(Map.of("clubId", CLUB)).get("data");
        assertEquals(List.of("join_requests", "event_ops", "settlement"), data.get("tools"));
        assertEquals(true, data.get("isOwner"));
        assertEquals(3, data.get("memberCount"));
        assertEquals(2, data.get("pendingJoinRequestCount"));
        assertEquals("老城跑团", ((Map<?, ?>) data.get("club")).get("name"));
    }

    @Test
    void workbenchRefusesPlainMembersAndOutsiders() {
        me = MEMBER;
        ClubAccessContext plain = context(MEMBER, Set.of(ClubPermission.CLUB_READ, ClubPermission.MEMBER_LIST_READ));
        when(access.resolveWithEventAccesses(MEMBER, CLUB)).thenReturn(plain);
        assertEquals("CLUB_WORKBENCH_FORBIDDEN", assertThrows(AppGapException.class,
                () -> controller.workbench(Map.of("clubId", CLUB))).getErrorCode());
        me = 1L;
        when(access.resolveWithEventAccesses(1L, CLUB)).thenThrow(new ServiceException("俱乐部身份无效或权限不足", 403));
        assertEquals(403, assertThrows(AppGapException.class, () -> controller.workbench(Map.of("clubId", CLUB))).getStatus());
        when(access.resolveWithEventAccesses(1L, 7002L)).thenReturn(null);
        assertEquals(403, assertThrows(AppGapException.class, () -> controller.workbench(Map.of("clubId", 7002L))).getStatus());
        verify(clubMembers, never()).countPendingJoinRequests(anyLong());
    }

    @Test
    void settlementExportIsTheClubSummaryForFinanceReadersOnly() {
        Map<String, Object> summaryData = new LinkedHashMap<>();
        summaryData.put("topics", List.of());
        when(settlements.summary(any())).thenReturn(AjaxResult.success(summaryData));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) controller.settlementsExport(Map.of("clubId", CLUB)).get("data");
        assertEquals(true, data.get("readOnly"));
        assertEquals(CLUB, data.get("clubId"));
        verify(access).require(OWNER, CLUB, null, ClubPermission.FINANCE_READ);
        verify(settlements).summary(argThat(m -> m.size() == 1 && Long.valueOf(CLUB).equals(m.get("clubId"))));

        me = MEMBER;
        when(access.require(MEMBER, CLUB, null, ClubPermission.FINANCE_READ)).thenThrow(new ServiceException("权限不足", 403));
        assertEquals("CLUB_SETTLEMENT_FORBIDDEN", assertThrows(AppGapException.class,
                () -> controller.settlementsExport(Map.of("clubId", CLUB, "personal", true))).getErrorCode());
        verify(settlements, times(1)).summary(any());
    }

    private static ClubAccessContext context(long principal, Set<String> permissions) {
        ClubAccessContext context = mock(ClubAccessContext.class);
        when(context.getRoleCodes()).thenReturn(Set.of("OWNER"));
        when(context.getPermissions()).thenReturn(permissions);
        when(context.hasPermission(anyString())).thenAnswer(call -> permissions.contains(call.<String>getArgument(0)));
        when(context.getPrincipalMemberId()).thenReturn(principal);
        return context;
    }
}
