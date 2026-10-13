package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.UmsMember;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayTeamServiceImplTest {

    private static final Long TEAM_ID = 31L;
    private static final Long LEADER_ID = 7L;
    private static final Long MEMBER_ID = 8L;

    private PlayTeamMapper teamMapper;
    private ImBlockMapper blockMapper;
    private CmsActivityMapper cmsActivityMapper;
    private CmsTopicMapper cmsTopicMapper;
    private IImService imService;
    private INotifyService notifyService;
    private IUmsMemberService umsMemberService;
    private IWxContentSecurityService wxContentSecurityService;
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
        umsMemberService = mock(IUmsMemberService.class);
        wxContentSecurityService = mock(IWxContentSecurityService.class);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", wxContentSecurityService);
        cmsActivityMapper = mock(CmsActivityMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
    }

    @Test
    void create_rejectsOverlongTitleBeforeWritingTeam() {
        String title = new String(new char[61]).replace('\0', '队');

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(LEADER_ID, 1, 9L, title, 4));

        assertEquals("队伍名称最多 60 个字", error.getMessage());
        verify(teamMapper, never()).insertPlayTeam(any());
    }

    @Test
    void create_rejectsUnsafeRecruitCopyBeforeWritingTeam() {
        UmsMember member = new UmsMember();
        member.setWxOpenId("openid-7");
        when(umsMemberService.selectUmsMemberById(LEADER_ID)).thenReturn(member);
        when(wxContentSecurityService.checkText("周末夜行，等你加入", "openid-7"))
                .thenReturn("内容安全校验未通过");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(LEADER_ID, 2, 9L, "周末夜行，等你加入", 4));

        assertEquals("内容安全校验未通过", error.getMessage());
        verify(wxContentSecurityService).checkText("周末夜行，等你加入", "openid-7");
        verify(teamMapper, never()).insertPlayTeam(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void infoExposesOnlyMemberIdRoleNicknameAndAvatar() {
        PlayTeam team = recruitingTeam();
        PlayTeamMember member = member(MEMBER_ID, 0);
        member.setMemberName("夜行者");
        member.setMemberAvatar("https://img/avatar.png");
        member.setApplyMessage("不应下发的内部字段");
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(team);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(Arrays.asList(member));

        when(teamMapper.selectActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(LEADER_ID, 1));
        Map<String, Object> result = service.info(TEAM_ID, null, LEADER_ID);
        List<Map<String, Object>> visible = (List<Map<String, Object>>) result.get("members");

        assertEquals(1, visible.size());
        assertEquals(new HashSet<>(Arrays.asList("memberId", "role", "memberName", "memberAvatar")),
                visible.get(0).keySet());
        assertEquals("夜行者", visible.get(0).get("memberName"));
        assertEquals("https://img/avatar.png", visible.get(0).get("memberAvatar"));
    }

    @Test
    void infoByEnumerableIdRejectsViewerOutsideTheTeam() {
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(recruitingTeam());
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.info(TEAM_ID, null, MEMBER_ID));

        assertEquals("无权查看该队伍", error.getMessage());
        verify(teamMapper, never()).selectActiveMembers(TEAM_ID);
    }

    @Test
    void infoByInviteCodeAllowsAnonymousPreview() {
        PlayTeam team = recruitingTeam();
        team.setInviteCode("secure-invite");
        when(teamMapper.selectPlayTeamByInviteCode("secure-invite")).thenReturn(team);

        Map<String, Object> result = service.info(null, "secure-invite", 0L);

        assertEquals(team, result.get("team"));
        assertEquals(false, result.get("joined"));
        verify(teamMapper).selectActiveMembers(TEAM_ID);
    }

    @Test
    void endedTeamInviteCodeCannotExposeFormerMemberRoster() {
        PlayTeam team = recruitingTeam();
        team.setInviteCode("ended-invite");
        team.setStatus(PlayTeam.STATUS_ENDED);
        when(teamMapper.selectPlayTeamByInviteCode("ended-invite")).thenReturn(team);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.info(null, "ended-invite", 0L));

        assertEquals("邀请已失效", error.getMessage());
        verify(teamMapper, never()).selectActiveMembers(TEAM_ID);
    }

    @Test
    void infoByInviteCodeKeepsWorkingAfterActivityStarted() {
        PlayTeam team = recruitingTeam();
        team.setInviteCode("started-invite");
        team.setExpireTime(new Date(System.currentTimeMillis() - 1_000));
        when(teamMapper.selectPlayTeamByInviteCode("started-invite")).thenReturn(team);
        when(teamMapper.selectActiveMembers(TEAM_ID)).thenReturn(java.util.Collections.emptyList());

        Map<String, Object> result = service.info(null, "started-invite", 0L);

        assertEquals(team, result.get("team"));
        verify(teamMapper).selectActiveMembers(TEAM_ID);
    }

    @Test
    void create_afterActivityStartedStillLetsPlayersRebuildTheTeam() {
        CmsActivity activity = activity(19L);
        activity.setStartDate(new Date(System.currentTimeMillis() - 60_000));
        activity.setEndDate(new Date(System.currentTimeMillis() + 3_600_000));
        when(cmsActivityMapper.selectCmsActivityById(9L)).thenReturn(activity);
        when(cmsTopicMapper.selectCmsTopicById(19L)).thenReturn(topic(1, 4));
        when(teamMapper.hasPaidRegistration(LEADER_ID, 2, 9L)).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            PlayTeam team = invocation.getArgument(0);
            team.setId(TEAM_ID);
            return 1;
        }).when(teamMapper).insertPlayTeam(any());

        PlayTeam created = service.create(LEADER_ID, 2, 9L, null, 4);

        assertNotNull(created.getInviteCode());
        verify(teamMapper).insertPlayTeam(created);
        verify(teamMapper).insertPlayTeamMember(any());
    }

    @Test
    void join_afterActivityStartedStillAcceptsTheInviteCode() {
        PlayTeam team = recruitingTeam();
        team.setInviteCode("secure-invite");
        team.setOwnerType(2);
        team.setOwnerId(9L);
        team.setExpireTime(new Date(System.currentTimeMillis() - 60_000));
        when(teamMapper.selectPlayTeamByInviteCode("secure-invite")).thenReturn(team);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, 9L)).thenReturn(1);
        when(teamMapper.reserveSlot(TEAM_ID)).thenReturn(1);
        when(teamMapper.selectPlayTeamById(TEAM_ID)).thenReturn(team);

        service.join("secure-invite", MEMBER_ID);

        verify(teamMapper).insertPlayTeamMember(any());
    }

    @Test
    void apply_afterActivityStartedKeepsAPendingDeadlineTheLeaderCanStillApprove() {
        PlayTeam team = recruitingTeam();
        team.setJoinMode(2);
        team.setOwnerType(2);
        team.setOwnerId(9L);
        Date endDate = new Date(System.currentTimeMillis() + 3_600_000);
        CmsActivity activity = activity(19L);
        activity.setStartDate(new Date(System.currentTimeMillis() - 60_000));
        activity.setEndDate(endDate);
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(null);
        when(cmsActivityMapper.selectCmsActivityById(9L)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, 9L)).thenReturn(1);

        Date expireAt = service.apply(TEAM_ID, MEMBER_ID, null);

        assertTrue(expireAt.after(new Date()), "开场后的申请不能被立即作废: " + expireAt);
        assertEquals(endDate, expireAt);
        verify(teamMapper).insertPlayTeamMember(any());
    }

    @Test
    void create_rejectsNonActivityOwner() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(LEADER_ID, 1, 9L, null, 4));

        assertEquals("组队仅支持活动场次", error.getMessage());
        verify(teamMapper, never()).insertPlayTeam(any());
    }

    @Test
    void create_rejectsTopicWithoutTeamMode() {
        CmsActivity activity = activity(19L);
        CmsTopic topic = topic(0, 4);
        when(cmsActivityMapper.selectCmsActivityById(9L)).thenReturn(activity);
        when(cmsTopicMapper.selectCmsTopicById(19L)).thenReturn(topic);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.create(LEADER_ID, 2, 9L, null, 4));

        assertEquals("该主题未开放组队", error.getMessage());
        verify(teamMapper, never()).hasPaidRegistration(any(), any(), any());
        verify(teamMapper, never()).insertPlayTeam(any());
    }

    @Test
    void create_rejectsRequestedMembersAboveTopicLimit() {
        when(cmsActivityMapper.selectCmsActivityById(9L)).thenReturn(activity(19L));
        when(cmsTopicMapper.selectCmsTopicById(19L)).thenReturn(topic(1, 3));
        when(teamMapper.hasPaidRegistration(LEADER_ID, 2, 9L)).thenReturn(1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(LEADER_ID, 2, 9L, null, 4));

        assertEquals("队伍人数须为 2–3 人", error.getMessage());
        verify(teamMapper, never()).insertPlayTeam(any());
    }

    @Test
    void create_rejectsMemberWithoutPaidActivityRegistration() {
        when(cmsActivityMapper.selectCmsActivityById(9L)).thenReturn(activity(19L));
        when(cmsTopicMapper.selectCmsTopicById(19L)).thenReturn(topic(2, 4));
        when(teamMapper.hasPaidRegistration(LEADER_ID, 2, 9L)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.create(LEADER_ID, 2, 9L, null, 3));

        assertEquals("报名成功后才能建队", error.getMessage());
        verify(teamMapper, never()).insertPlayTeam(any());
    }

    @Test
    void createGeneratesUnguessableInviteCodeBeforePersistence() {
        when(cmsActivityMapper.selectCmsActivityById(9L)).thenReturn(activity(19L));
        when(cmsTopicMapper.selectCmsTopicById(19L)).thenReturn(topic(1, 4));
        when(teamMapper.hasPaidRegistration(LEADER_ID, 2, 9L)).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            PlayTeam team = invocation.getArgument(0);
            team.setId(TEAM_ID);
            return 1;
        }).when(teamMapper).insertPlayTeam(any());

        PlayTeam created = service.create(LEADER_ID, 2, 9L, null, 4);

        assertNotNull(created.getInviteCode());
        assertTrue(created.getInviteCode().matches("[A-Za-z0-9_-]{22}"), created.getInviteCode());
        verify(teamMapper).insertPlayTeam(created);
    }

    @Test
    void create_defaultsToPublicTeamSoNewTeamsCanAppearOnTheMap() {
        when(cmsActivityMapper.selectCmsActivityById(9L)).thenReturn(activity(19L));
        when(cmsTopicMapper.selectCmsTopicById(19L)).thenReturn(topic(1, 4));
        when(teamMapper.hasPaidRegistration(LEADER_ID, 2, 9L)).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            PlayTeam team = invocation.getArgument(0);
            team.setId(TEAM_ID);
            return 1;
        }).when(teamMapper).insertPlayTeam(any());

        PlayTeam created = service.create(LEADER_ID, 2, 9L, null, 4);

        assertEquals(2, created.getJoinMode(), "P 方案:不传 joinMode 的新队伍默认公开(地图可见)");
    }

    @Test
    void updateJoinMode_onlyTheLeaderCanSwitchAndOnlyToAKnownMode() {
        PlayTeam team = recruitingTeam();
        team.setJoinMode(2);
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team);
        when(teamMapper.updateJoinMode(TEAM_ID, LEADER_ID, 1)).thenReturn(1);

        assertEquals(1, service.updateJoinMode(TEAM_ID, LEADER_ID, 1));
        verify(teamMapper).updateJoinMode(TEAM_ID, LEADER_ID, 1);

        // 幂等:已是该模式时不重复写。
        org.mockito.Mockito.clearInvocations(teamMapper);
        team.setJoinMode(1);
        assertEquals(1, service.updateJoinMode(TEAM_ID, LEADER_ID, 1));
        verify(teamMapper, never()).updateJoinMode(TEAM_ID, LEADER_ID, 1);

        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(recruitingTeam());
        IllegalStateException notLeader = assertThrows(IllegalStateException.class,
                () -> service.updateJoinMode(TEAM_ID, MEMBER_ID, 1));
        assertEquals("只有队长可以操作", notLeader.getMessage());

        IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
                () -> service.updateJoinMode(TEAM_ID, LEADER_ID, 3));
        assertEquals("组队方式有误", badMode.getMessage());
        verify(teamMapper, never()).updateJoinMode(TEAM_ID, LEADER_ID, 3);
    }

    private CmsActivity activity(Long topicId) {
        CmsActivity activity = new CmsActivity();
        activity.setTopicId(topicId);
        activity.setStartDate(new Date(System.currentTimeMillis() + 60_000));
        return activity;
    }

    private CmsTopic topic(int teamMode, int teamMaxMembers) {
        CmsTopic topic = new CmsTopic();
        topic.setTeamMode(teamMode);
        topic.setTeamMaxMembers(teamMaxMembers);
        return topic;
    }

    private PlayTeam recruitingTeam() {
        PlayTeam team = new PlayTeam();
        team.setId(TEAM_ID);
        team.setLeaderMemberId(LEADER_ID);
        team.setStatus(PlayTeam.STATUS_RECRUITING);
        team.setJoinedCount(1);
        team.setMaxMembers(4);
        return team;
    }

    @Test
    void applyToAnInviteOnlyTeamIsIndistinguishableFromAMissingTeam() {
        PlayTeam inviteOnly = recruitingTeam();
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(inviteOnly);
        when(teamMapper.selectActiveMember(TEAM_ID, MEMBER_ID)).thenReturn(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.apply(TEAM_ID, MEMBER_ID, null));

        assertEquals("队伍不存在", error.getMessage(), "错误口径不得用来探测私密队伍是否存在");
        verify(teamMapper, never()).insertPlayTeamMember(any());
    }

    @Test
    void join_requiresAPaidTicketForThisSessionLikeApply() {
        PlayTeam team = recruitingTeam();
        team.setInviteCode("secure-invite");
        when(teamMapper.selectPlayTeamByInviteCode("secure-invite")).thenReturn(team);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, team.getOwnerId())).thenReturn(0);

        IPlayTeamService.TeamRejection error = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> service.join("secure-invite", MEMBER_ID));

        assertEquals("TICKET_REQUIRED", error.getErrorCode());
        verify(teamMapper, never()).reserveSlot(TEAM_ID);
        verify(teamMapper, never()).insertPlayTeamMember(any());
    }

    @Test
    void join_fullTeamFailsFromAtomicReservationAndDoesNotWriteMember() {
        PlayTeam team = recruitingTeam();
        team.setInviteCode("secure-invite");
        when(teamMapper.selectPlayTeamByInviteCode("secure-invite")).thenReturn(team);
        when(blockMapper.existsBetween(LEADER_ID, MEMBER_ID)).thenReturn(0);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, team.getOwnerId())).thenReturn(1);
        when(teamMapper.reserveSlot(TEAM_ID)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.join("secure-invite", MEMBER_ID));

        assertEquals("队伍已满或已停止招募", error.getMessage());
        verify(teamMapper, never()).insertPlayTeamMember(any());
        verify(teamMapper, never()).reactivateMember(any(), any());
        verify(imService, never()).getOrCreateTeamGroup(any(), any());
    }

    @Test
    void quit_lastLeaderDisbandsInsteadOfInventingAutoRefund() {
        PlayTeam team = recruitingTeam();
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team);
        when(teamMapper.selectActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(LEADER_ID, 1));
        when(teamMapper.countActiveMembers(TEAM_ID)).thenReturn(1);
        when(teamMapper.markMemberStatus(TEAM_ID, LEADER_ID, 1, 2)).thenReturn(1);
        when(teamMapper.disbandTeam(TEAM_ID, LEADER_ID)).thenReturn(1);

        service.quit(TEAM_ID, LEADER_ID);

        verify(teamMapper).disbandTeam(TEAM_ID, LEADER_ID);
        verify(imService).closeTeamGroup(TEAM_ID);
        verify(notifyService, never()).notifyRefund(any(), any(), any());
    }

    @Test
    void quit_leaderTransfersToEarliestJoinedMemberBeforeLeaving() {
        PlayTeam team = recruitingTeam();
        PlayTeamMember next = member(MEMBER_ID, 0);
        when(teamMapper.selectPlayTeamByIdForUpdate(TEAM_ID)).thenReturn(team);
        when(teamMapper.selectActiveMember(TEAM_ID, LEADER_ID)).thenReturn(member(LEADER_ID, 1));
        when(teamMapper.countActiveMembers(TEAM_ID)).thenReturn(2);
        when(teamMapper.selectEarliestOtherActiveMember(TEAM_ID, LEADER_ID)).thenReturn(next);
        when(teamMapper.transferLeadership(TEAM_ID, LEADER_ID, MEMBER_ID)).thenReturn(1);
        when(teamMapper.updateMemberRole(TEAM_ID, LEADER_ID, 1, 0)).thenReturn(1);
        when(teamMapper.updateMemberRole(TEAM_ID, MEMBER_ID, 0, 1)).thenReturn(1);
        when(teamMapper.markMemberStatus(TEAM_ID, LEADER_ID, 1, 2)).thenReturn(1);
        when(teamMapper.releaseSlot(TEAM_ID)).thenReturn(1);

        service.quit(TEAM_ID, LEADER_ID);

        org.mockito.InOrder order = inOrder(teamMapper);
        order.verify(teamMapper).transferLeadership(TEAM_ID, LEADER_ID, MEMBER_ID);
        order.verify(teamMapper).markMemberStatus(TEAM_ID, LEADER_ID, 1, 2);
        order.verify(teamMapper).releaseSlot(TEAM_ID);
        verify(imService).leaveTeamGroup(TEAM_ID, LEADER_ID);
        verify(notifyService).notifyTeamStatus(eq(MEMBER_ID), any(), eq("你已成为队长"));
    }

    @Test
    void expireOneTeamIsIdempotentAndOnlyClosesItsImGroup() {
        Date now = new Date();
        when(teamMapper.endExpiredTeam(TEAM_ID, now)).thenReturn(1);

        assertEquals(1, service.endExpiredTeam(TEAM_ID, now));
        verify(imService).closeTeamGroup(TEAM_ID);
        verify(notifyService, never()).notifyRefund(any(), any(), any());

        when(teamMapper.endExpiredTeam(TEAM_ID, now)).thenReturn(0);
        assertEquals(0, service.endExpiredTeam(TEAM_ID, now));
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
