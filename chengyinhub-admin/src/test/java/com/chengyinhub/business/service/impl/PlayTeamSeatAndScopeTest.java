package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B6 多人角色席位与协作:席位、邀请、成员关系三件事分开表达,
 * 且票款身份与参与身份、队长职务与剧情角色、本队与他队互相不能冒充。
 */
class PlayTeamSeatAndScopeTest {

    private static final Long TEAM_ID = 41L;
    private static final Long LEADER_ID = 700L;
    private static final Long MEMBER_ID = 800L;
    private static final Long ACTIVITY_ID = 9L;

    private PlayTeamMapper teamMapper;
    private ImBlockMapper blockMapper;
    private IImService imService;
    private INotifyService notifyService;
    private PlayTeamServiceImpl service;

    @BeforeEach
    void setUp() {
        teamMapper = mock(PlayTeamMapper.class);
        blockMapper = mock(ImBlockMapper.class);
        imService = mock(IImService.class);
        notifyService = mock(INotifyService.class);
        service = new PlayTeamServiceImpl();
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "blockMapper", blockMapper);
        ReflectionTestUtils.setField(service, "imService", imService);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(service, "wxContentSecurityService", mock(IWxContentSecurityService.class));
    }

    // ---------- 席位:已购 / 待审申请 / 已领取 / 仍可领取 ----------

    @Test
    @SuppressWarnings("unchecked")
    void infoSeparatesClaimedPendingAndAvailableSeats() {
        PlayTeam team = team(4, 2, PlayTeam.STATUS_RECRUITING);
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(team);
        when(teamMapper.selectActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(LEADER_ID, 1));
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1), member(MEMBER_ID, 0)));
        when(teamMapper.countPaidActiveMembers(TEAM_ID)).thenReturn(2);
        when(teamMapper.countOpenApplications(eq(TEAM_ID), any(Date.class))).thenReturn(3);

        Map<String, Object> result = service.info(TEAM_ID, null, LEADER_ID);
        Map<String, Object> seats = (Map<String, Object>) result.get("seats");

        assertEquals(4, seats.get("capacity"));
        assertEquals(2, seats.get("occupied"));
        assertEquals(2, seats.get("available"), "可领取名额只由权威 joined_count 扣减");
        assertEquals(2, seats.get("paidOccupied"));
        assertEquals(0, seats.get("paidTicketMissing"));
        // 待审申请只是排队关系:3 个人在等也不占席、不吃掉可领取名额。
        assertEquals(3, seats.get("pendingApplications"));
        assertFalse(seats.containsKey("claimable"), "claimable 不能只按容量推断");
        assertFalse(seats.containsKey("reservedApplications"), "申请不是预留席位");
        assertFalse(seats.containsKey("unpaidOccupied"), "不能把无票成员说成可占未付款席位");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefundedMemberIsNeverReportedAsAPaidSeat() {
        PlayTeam team = team(4, 3, PlayTeam.STATUS_RECRUITING);
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(team);
        when(teamMapper.selectActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(LEADER_ID, 1));
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));
        when(teamMapper.countPaidActiveMembers(TEAM_ID)).thenReturn(1);

        Map<String, Object> seats = (Map<String, Object>) service.info(TEAM_ID, null, LEADER_ID).get("seats");

        assertEquals(3, seats.get("occupied"), "已领取席位数仍以 joined_count 为准");
        assertEquals(1, seats.get("paidOccupied"), "只有仍持有效票的人算已购席位");
        assertEquals(2, seats.get("paidTicketMissing"), "退票成员只计为票已失效,不冒充已付席位");
    }

    // ---------- 队长保留席位 / 末席只有一个赢家 ----------

    @Test
    void leaderTransferReleasesExactlyOneSeatAndPromotesTheSuccessorOnce() {
        PlayTeam team = team(4, 2, PlayTeam.STATUS_RECRUITING);
        PlayTeamMember successor = member(MEMBER_ID, 0);
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team);
        when(teamMapper.selectActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(LEADER_ID, 1));
        when(teamMapper.countActiveMembers(TEAM_ID)).thenReturn(2);
        when(teamMapper.selectEarliestOtherActiveMember(TEAM_ID, LEADER_ID)).thenReturn(successor);
        when(teamMapper.transferLeadership(TEAM_ID, LEADER_ID, MEMBER_ID)).thenReturn(1);
        when(teamMapper.updateMemberRole(TEAM_ID, LEADER_ID, 1, 0)).thenReturn(1);
        when(teamMapper.updateMemberRole(TEAM_ID, MEMBER_ID, 0, 1)).thenReturn(1);
        when(teamMapper.markMemberStatus(TEAM_ID, LEADER_ID,
                PlayTeamMember.STATUS_JOINED, PlayTeamMember.STATUS_QUIT)).thenReturn(1);
        when(teamMapper.releaseSlot(TEAM_ID)).thenReturn(1);

        service.quit(TEAM_ID, LEADER_ID);

        // 继任者的席位原样保留:只给离开的队长扣一次位。
        verify(teamMapper, times(1)).releaseSlot(TEAM_ID);
        verify(teamMapper, times(1)).updateMemberRole(TEAM_ID, MEMBER_ID, 0, 1);
        verify(teamMapper, never()).disbandTeam(anyLong(), anyLong());
    }

    @Test
    void approvingIntoTheLastSeatLosesAtomicallyAndNotifiesNobody() {
        PlayTeam team = team(4, 2, PlayTeam.STATUS_RECRUITING);
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);
        when(teamMapper.reserveSlot(TEAM_ID)).thenReturn(0);

        IPlayTeamService.TeamRejection error = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.handle(TEAM_ID, LEADER_ID, MEMBER_ID, true));

        assertEquals("TEAM_FULL", error.getErrorCode());
        verify(teamMapper, never()).approvePending(anyLong(), anyLong(), any(Date.class));
        verify(notifyService, never()).notifyTeamStatus(anyLong(), any(), any());
        verify(imService, never()).getOrCreateTeamGroup(anyLong(), anyLong());
    }

    @Test
    void joiningTheLastSeatLosesOnTheConditionalReservationOnly() {
        PlayTeam team = team(4, 2, PlayTeam.STATUS_RECRUITING);
        team.setInviteCode("seat-race");
        when(teamMapper.selectPlayTeamByInviteCode("seat-race")).thenReturn(team);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.countOpenReports(TEAM_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);
        when(teamMapper.reserveSlot(TEAM_ID)).thenReturn(0);

        IPlayTeamService.TeamRejection error = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.join("seat-race", MEMBER_ID));

        assertEquals("TEAM_FULL", error.getErrorCode());
        assertEquals("队伍已满或已停止招募", error.getMessage());
        verify(teamMapper, never()).insertPlayTeamMember(any());
        verify(teamMapper, never()).reactivateMember(anyLong(), anyLong());
        verify(notifyService, never()).notifyTeamStatus(anyLong(), any(), any());
    }

    // ---------- 跨队 / 跨角色 / 跨组织拒绝 ----------

    @Test
    void nonLeaderCannotHandleKickOrListApplications() {
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team(4, 2, PlayTeam.STATUS_RECRUITING));
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(team(4, 2, PlayTeam.STATUS_RECRUITING));

        for (Runnable attempt : Arrays.<Runnable>asList(
                () -> service.handle(TEAM_ID, MEMBER_ID, 900L, true),
                () -> service.kick(TEAM_ID, MEMBER_ID, 900L),
                () -> service.disband(TEAM_ID, MEMBER_ID),
                () -> service.applications(TEAM_ID, MEMBER_ID),
                () -> service.updateJoinMode(TEAM_ID, MEMBER_ID, 1))) {
            IllegalStateException denied = assertThrows(IllegalStateException.class, attempt::run);
            assertEquals("只有队长可以操作", denied.getMessage());
        }

        verify(teamMapper, never()).approvePending(anyLong(), anyLong(), any(Date.class));
        verify(teamMapper, never()).rejectPending(anyLong(), anyLong(), any(Date.class));
        verify(teamMapper, never()).markMemberStatus(anyLong(), anyLong(), anyInt(), anyInt());
        verify(teamMapper, never()).updateJoinMode(anyLong(), anyLong(), anyInt());
        verify(teamMapper, never()).disbandTeam(anyLong(), anyLong());
    }

    @Test
    void kickingSomebodyFromAnotherTeamDoesNotReleaseThisTeamsSeat() {
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team(4, 2, PlayTeam.STATUS_RECRUITING));
        // 目标不在本队:条件 UPDATE 命中 0 行。
        when(teamMapper.markMemberStatus(TEAM_ID, 900L,
                PlayTeamMember.STATUS_JOINED, PlayTeamMember.STATUS_KICKED)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.kick(TEAM_ID, LEADER_ID, 900L));

        assertEquals("成员不在队伍中", error.getMessage());
        verify(teamMapper, never()).releaseSlot(anyLong());
        verify(notifyService, never()).notifyTeamStatus(anyLong(), any(), any());
    }

    @Test
    void aLeaderWhoCannotHandOverDuringARunGetsAMachineReadableReason() {
        PlayTeam running = team(4, 2, PlayTeam.STATUS_STARTED);
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(running);
        when(teamMapper.selectActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(LEADER_ID, 1));
        when(teamMapper.countActiveMembers(TEAM_ID)).thenReturn(2);
        when(teamMapper.selectEarliestOtherActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(MEMBER_ID, 0));
        // 原 transferLeadership 只开 status in (0,1);进行中的队伍命中 0 行。
        when(teamMapper.transferLeadership(TEAM_ID, LEADER_ID, MEMBER_ID)).thenReturn(0);

        IPlayTeamService.TeamRejection error = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.quit(TEAM_ID, LEADER_ID));

        assertEquals("LEADER_HANDOVER_FAILED", error.getErrorCode());
        verify(teamMapper, never()).markMemberStatus(anyLong(), anyLong(), anyInt(), anyInt());
        verify(teamMapper, never()).releaseSlot(anyLong());
    }

    @Test
    void aLostSeatReleaseRaceIsReportedAsAStateChangeNotASuccess() {
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team(4, 3, PlayTeam.STATUS_RECRUITING));
        when(teamMapper.markMemberStatus(TEAM_ID, MEMBER_ID,
                PlayTeamMember.STATUS_JOINED, PlayTeamMember.STATUS_KICKED)).thenReturn(1);
        // 队伍行被并发改走(例如已结束),释放名额命中 0 行。
        when(teamMapper.releaseSlot(TEAM_ID)).thenReturn(0);

        IPlayTeamService.TeamRejection error = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.kick(TEAM_ID, LEADER_ID, MEMBER_ID));

        assertEquals("TEAM_STATE_CHANGED", error.getErrorCode());
        verify(notifyService, never()).notifyTeamStatus(anyLong(), any(), any());
    }

    // ---------- 票款身份与参与身份不互相冒充 ----------

    @Test
    void joinOnlyAcceptsThisSessionsPaidTicketNotASelfPlayPass() {
        PlayTeam team = team(4, 2, PlayTeam.STATUS_RECRUITING);
        team.setInviteCode("ticket-scope");
        when(teamMapper.selectPlayTeamByInviteCode("ticket-scope")).thenReturn(team);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        // 自玩通行证(owner_type=1)不属于本场次,查询按 owner_type=2 才有效。
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(0);

        IPlayTeamService.TeamRejection error = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.join("ticket-scope", MEMBER_ID));

        assertEquals("TICKET_REQUIRED", error.getErrorCode());
        verify(teamMapper).hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID);
        verify(teamMapper, never()).reserveSlot(anyLong());
        verify(teamMapper, never()).insertPlayTeamMember(any());
    }

    @Test
    void auditFrozenTeamAcceptsNoNewSeatEvenThroughAnInviteCode() {
        PlayTeam team = team(4, 2, PlayTeam.STATUS_RECRUITING);
        team.setInviteCode("under-review");
        when(teamMapper.selectPlayTeamByInviteCode("under-review")).thenReturn(team);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.countOpenReports(TEAM_ID)).thenReturn(1);

        IPlayTeamService.TeamRejection error = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.join("under-review", MEMBER_ID));

        assertEquals("TEAM_UNDER_REVIEW", error.getErrorCode());
        verify(teamMapper, never()).hasPaidRegistration(anyLong(), anyInt(), anyLong());
        verify(teamMapper, never()).reserveSlot(anyLong());
    }

    // ---------- 邀请期限 / 掉线重入 ----------

    @Test
    void inviteCodeFailuresDistinguishRevokedFromExpired() {
        when(teamMapper.selectPlayTeamByInviteCode("gone")).thenReturn(null);
        IPlayTeamService.TeamRejection revoked = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.join("gone", MEMBER_ID));
        assertEquals("INVITE_REVOKED", revoked.getErrorCode());

        PlayTeam ended = team(4, 2, PlayTeam.STATUS_ENDED);
        when(teamMapper.selectPlayTeamByInviteCode("ended")).thenReturn(ended);
        IPlayTeamService.TeamRejection expired = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.join("ended", MEMBER_ID));
        assertEquals("INVITE_EXPIRED", expired.getErrorCode());
        // 两种失败都在任何名额或成员写之前拒绝。
        verify(teamMapper, never()).reserveSlot(anyLong());
        verify(teamMapper, never()).insertPlayTeamMember(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inviteProjectionReadsTheAuthoritativeTeamInsteadOfACachedLink() {
        Date start = new Date(System.currentTimeMillis() + 3_600_000);
        PlayTeam recruiting = team(4, 2, PlayTeam.STATUS_RECRUITING);
        recruiting.setExpireTime(start);
        recruiting.setJoinMode(1);
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(recruiting);
        when(teamMapper.selectActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(LEADER_ID, 1));
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));
        Map<String, Object> invite = (Map<String, Object>) service.info(TEAM_ID, null, LEADER_ID).get("invite");
        assertEquals("INVITE_ONLY", invite.get("mode"));
        assertEquals(Boolean.TRUE, invite.get("available"));
        assertEquals(start, invite.get("expiresAt"));
        assertEquals(Boolean.FALSE, invite.get("expired"));

        PlayTeam ended = team(4, 2, PlayTeam.STATUS_ENDED);
        ended.setJoinMode(2);
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(ended);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));
        Map<String, Object> deadInvite = (Map<String, Object>) service.info(TEAM_ID, null, LEADER_ID).get("invite");
        assertEquals("PUBLIC", deadInvite.get("mode"));
        assertEquals(Boolean.FALSE, deadInvite.get("available"));
        assertEquals(Boolean.TRUE, deadInvite.get("expired"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reentryAfterBeingKickedReadsTheMemberRowInsteadOfAnOldSession() {
        PlayTeam recruiting = team(4, 2, PlayTeam.STATUS_RECRUITING);
        recruiting.setInviteCode("reentry-code");
        when(teamMapper.selectPlayTeamByInviteCode("reentry-code")).thenReturn(recruiting);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(null);
        PlayTeamMember kicked = member(MEMBER_ID, 0);
        kicked.setStatus(PlayTeamMember.STATUS_KICKED);
        when(teamMapper.selectMemberRow(TEAM_ID, MEMBER_ID)).thenReturn(kicked);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));
        when(teamMapper.countOpenReports(TEAM_ID)).thenReturn(0);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);

        Map<String, Object> result = service.info(null, "reentry-code", MEMBER_ID);
        Map<String, Object> viewer = (Map<String, Object>) result.get("viewer");

        assertEquals(Boolean.FALSE, result.get("joined"), "被移出后重入不能沿用旧会话的 joined");
        assertEquals(Boolean.FALSE, viewer.get("joined"));
        assertEquals(Boolean.FALSE, viewer.get("leader"));
        assertEquals("KICKED", viewer.get("applicationStatus"));
        assertEquals(Boolean.FALSE, viewer.get("canApply"), "被移出的人不能再申请同一支队伍");
        // play_team_member 唯一行 + reactivateMember(status in 0,2,5,6) 不放开 3/4:
        // 被移出的人也不能靠邀请码回来,与 PlayTeamMybatisIntegrationTest 的真实库口径一致。
        assertEquals(Boolean.FALSE, viewer.get("canJoinWithCode"));
        assertEquals(Arrays.asList("APPLY_REJECTED"), viewer.get("applyBlockers"));
        assertEquals(Arrays.asList("APPLY_REJECTED"), viewer.get("joinBlockers"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMemberWhoQuitCanComeBackThroughEitherEntry() {
        PlayTeam recruiting = team(4, 2, PlayTeam.STATUS_RECRUITING);
        recruiting.setInviteCode("quit-code");
        when(teamMapper.selectPlayTeamByInviteCode("quit-code")).thenReturn(recruiting);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(null);
        PlayTeamMember quit = member(MEMBER_ID, 0);
        quit.setStatus(PlayTeamMember.STATUS_QUIT);
        when(teamMapper.selectMemberRow(TEAM_ID, MEMBER_ID)).thenReturn(quit);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));
        when(teamMapper.countOpenReports(TEAM_ID)).thenReturn(0);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);

        Map<String, Object> viewer = (Map<String, Object>)
                service.info(null, "quit-code", MEMBER_ID).get("viewer");

        assertEquals("QUIT", viewer.get("applicationStatus"));
        // 主动退队不是拒绝:apply 的 reapplyMember 与 join 的 reactivateMember 都放开 status=2。
        assertEquals(Boolean.TRUE, viewer.get("canJoinWithCode"));
        assertEquals(Boolean.TRUE, viewer.get("canApply"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExpiredPendingApplicationShowsAsExpiredAndReopenable() {
        PlayTeam recruiting = team(4, 2, PlayTeam.STATUS_RECRUITING);
        recruiting.setInviteCode("pending-code");
        when(teamMapper.selectPlayTeamByInviteCode("pending-code")).thenReturn(recruiting);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(null);
        PlayTeamMember pending = member(MEMBER_ID, 0);
        pending.setStatus(PlayTeamMember.STATUS_PENDING);
        pending.setApplyExpireTime(new Date(System.currentTimeMillis() - 1_000));
        when(teamMapper.selectMemberRow(TEAM_ID, MEMBER_ID)).thenReturn(pending);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));
        when(teamMapper.countOpenReports(TEAM_ID)).thenReturn(0);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);

        Map<String, Object> viewer = (Map<String, Object>)
                service.info(null, "pending-code", MEMBER_ID).get("viewer");

        assertEquals("EXPIRED", viewer.get("applicationStatus"));
        assertEquals(Boolean.TRUE, viewer.get("canApply"), "过期申请必须有下一步:可重新申请");
    }

    @Test
    @SuppressWarnings("unchecked")
    void viewerActionabilityMirrorsTheRealAdmissionChecks() {
        PlayTeam recruiting = team(4, 2, PlayTeam.STATUS_RECRUITING);
        recruiting.setInviteCode("blocked-code");
        when(teamMapper.selectPlayTeamByInviteCode("blocked-code")).thenReturn(recruiting);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(null);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));
        when(teamMapper.selectMemberRow(TEAM_ID, MEMBER_ID)).thenReturn(null);
        when(teamMapper.countOpenReports(TEAM_ID)).thenReturn(1);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(1);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(0);

        Map<String, Object> viewer = (Map<String, Object>)
                service.info(null, "blocked-code", MEMBER_ID).get("viewer");

        // 容量够也不能说能点:审核中、被拉黑、没有本场票都必须出现在阻塞里。
        assertEquals(Boolean.FALSE, viewer.get("canApply"));
        assertEquals(Boolean.FALSE, viewer.get("canJoinWithCode"));
        assertEquals(Arrays.asList("TEAM_UNDER_REVIEW", "APPLY_BLOCKED", "TICKET_REQUIRED"),
                viewer.get("applyBlockers"));
        assertEquals(Arrays.asList("TEAM_UNDER_REVIEW", "APPLY_BLOCKED", "TICKET_REQUIRED"),
                viewer.get("joinBlockers"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPublicApplicantWithAPaidTicketAndFreeSeatCanApply() {
        PlayTeam recruiting = team(4, 2, PlayTeam.STATUS_RECRUITING);
        recruiting.setInviteCode("open-code");
        when(teamMapper.selectPlayTeamByInviteCode("open-code")).thenReturn(recruiting);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(null);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));
        when(teamMapper.selectMemberRow(TEAM_ID, MEMBER_ID)).thenReturn(null);
        when(teamMapper.countOpenReports(TEAM_ID)).thenReturn(0);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);

        Map<String, Object> viewer = (Map<String, Object>)
                service.info(null, "open-code", MEMBER_ID).get("viewer");

        assertEquals(Boolean.TRUE, viewer.get("canApply"));
        assertEquals(Boolean.TRUE, viewer.get("canJoinWithCode"));
        assertTrue(((java.util.List<?>) viewer.get("applyBlockers")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anonymousInvitePreviewHasNoViewerIdentity() {
        PlayTeam team = team(4, 2, PlayTeam.STATUS_RECRUITING);
        team.setInviteCode("anon-preview");
        when(teamMapper.selectPlayTeamByInviteCode("anon-preview")).thenReturn(team);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member(LEADER_ID, 1)));

        Map<String, Object> viewer = (Map<String, Object>)
                service.info(null, "anon-preview", 0L).get("viewer");

        assertEquals(Boolean.FALSE, viewer.get("joined"));
        assertEquals(Boolean.FALSE, viewer.get("canApply"), "未登录不能申请");
        assertEquals(Boolean.FALSE, viewer.get("canJoinWithCode"), "未登录不能占席");
    }

    // ---------- helpers ----------

    private PlayTeam team(int max, int joined, int status) {
        PlayTeam team = new PlayTeam();
        team.setId(TEAM_ID);
        team.setOwnerType(2);
        team.setOwnerId(ACTIVITY_ID);
        team.setLeaderMemberId(LEADER_ID);
        team.setMaxMembers(max);
        team.setJoinedCount(joined);
        team.setStatus(status);
        team.setJoinMode(2);
        return team;
    }

    private PlayTeamMember member(Long memberId, int role) {
        PlayTeamMember member = new PlayTeamMember();
        member.setTeamId(TEAM_ID);
        member.setMemberId(memberId);
        member.setRole(role);
        member.setStatus(PlayTeamMember.STATUS_JOINED);
        return member;
    }
}
