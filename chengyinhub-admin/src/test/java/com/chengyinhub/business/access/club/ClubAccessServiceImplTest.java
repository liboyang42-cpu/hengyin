package com.chengyinhub.business.access.club;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class ClubAccessServiceImplTest {
    private static final long TOPIC = 501L;
    private ClubMapper clubs;
    private ClubMemberMapper members;
    private ClubRoleAssignmentMapper assignments;
    private CmsActivityMapper activities;
    private UmsMemberMapper accounts;
    private ClubAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        clubs = mock(ClubMapper.class);
        members = mock(ClubMemberMapper.class);
        assignments = mock(ClubRoleAssignmentMapper.class);
        activities = mock(CmsActivityMapper.class);
        accounts = mock(UmsMemberMapper.class);
        service = new ClubAccessServiceImpl();
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "clubMemberMapper", members);
        ReflectionTestUtils.setField(service, "clubRoleAssignmentMapper", assignments);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "umsMemberMapper", accounts);
        when(accounts.lockActiveMemberById(any())).thenAnswer(call -> call.getArgument(0));
        when(accounts.selectRolePermissionSubject(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.UmsMember owner = new com.chengyinhub.business.domain.UmsMember();
            owner.setId(call.getArgument(0)); owner.setRole("club"); owner.setStatus(1L); owner.setDelFlag(0); return owner;
        });
    }

    @Test
    void ownerIsDerivedFromApprovedClubAndCanManageRoles() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));

        ClubAccessContext access = service.require(900L, 11L, null, ClubPermission.ROLE_MANAGE);

        assertEquals(900L, access.getPrincipalMemberId());
        assertEquals(11L, access.getClubId());
        assertEquals(900L, access.getOwnerMemberId());
        assertTrue(access.getRoleCodes().contains(ClubRoleCode.CLUB_OWNER));
        assertTrue(access.hasPermission(ClubPermission.ROLE_MANAGE));
    }

    @Test
    void clubProfileDoesNotGrantOwnerPermissionToPlayerRole() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        com.chengyinhub.business.domain.UmsMember player = new com.chengyinhub.business.domain.UmsMember();
        player.setId(900L); player.setRole("player");
        when(accounts.selectRolePermissionSubject(900L)).thenReturn(player);
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.require(900L, 11L, null, ClubPermission.ROLE_MANAGE)).getCode());
    }

    @Test
    void disabledOwnerOldTokenCannotAuthorizeEventCancellation() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(activities.selectCmsActivityById(55L)).thenReturn(activity(55L, 11L));
        when(accounts.lockActiveMemberById(900L)).thenReturn(null);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.require(900L, 11L, 55L, ClubPermission.ACTIVITY_MANAGE));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(accounts).lockActiveMemberById(900L);
        verify(members, never()).selectByClubAndMember(any(), any());
        verify(assignments, never()).selectActiveForAccess(any(), any(), any(), any(Date.class));
    }

    @Test
    void deletedOwnerOldTokenCannotAuthorizeEventCancellation() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(activities.selectCmsActivityById(55L)).thenReturn(activity(55L, 11L));
        when(accounts.lockActiveMemberById(900L)).thenReturn(null);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.require(900L, 11L, 55L, ClubPermission.ACTIVITY_MANAGE));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(accounts).lockActiveMemberById(900L);
        verify(members, never()).selectByClubAndMember(any(), any());
        verify(assignments, never()).selectActiveForAccess(any(), any(), any(), any(Date.class));
    }

    @Test
    void disabledCollaboratorOldTokenCannotReachEventRoleAuthorization() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(activities.selectCmsActivityById(55L)).thenReturn(activity(55L, 11L));
        when(accounts.lockActiveMemberById(901L)).thenReturn(null);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.require(901L, 11L, 55L, ClubPermission.ACTIVITY_MANAGE));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(accounts).lockActiveMemberById(901L);
        verify(members, never()).selectByClubAndMember(any(), any());
        verify(assignments, never()).selectActiveForAccess(any(), any(), any(), any(Date.class));
    }

    @Test
    void deletedCollaboratorOldTokenCannotReachEventRoleAuthorization() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(activities.selectCmsActivityById(55L)).thenReturn(activity(55L, 11L));
        when(accounts.lockActiveMemberById(901L)).thenReturn(null);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.require(901L, 11L, 55L, ClubPermission.ACTIVITY_MANAGE));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(accounts).lockActiveMemberById(901L);
        verify(members, never()).selectByClubAndMember(any(), any());
        verify(assignments, never()).selectActiveForAccess(any(), any(), any(), any(Date.class));
    }

    @Test
    void inactiveClubFailsClosed() {
        Club inactive = activeClub(11L, 900L);
        inactive.setStatus(0L);
        when(clubs.selectClubById(11L)).thenReturn(inactive);

        assertThrows(ServiceException.class,
                () -> service.require(900L, 11L, null, ClubPermission.CLUB_READ));
    }

    @Test
    void explicitOperatorDoesNotGainLegacyAdminMemberOrFinancePowers() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(members.selectByClubAndMember(11L, 901L)).thenReturn(activeMember(11L, 901L, 0));
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(null), any(Date.class)))
                .thenReturn(Collections.singletonList(activeClubRole(
                        11L, 901L, ClubRoleCode.CLUB_OPERATOR)));

        ClubAccessContext access = service.resolve(901L, 11L, null);

        assertTrue(access.getRoleCodes().contains(ClubRoleCode.CLUB_MEMBER));
        assertTrue(access.getRoleCodes().contains(ClubRoleCode.CLUB_OPERATOR));
        assertTrue(access.hasPermission(ClubPermission.MEMBER_LIST_READ));
        assertTrue(access.hasPermission(ClubPermission.MEMBER_APPROVE));
        assertTrue(access.hasPermission(ClubPermission.CONTENT_MANAGE));
        assertTrue(access.hasPermission(ClubPermission.NOTIFY_SEND));
        assertTrue(!access.hasPermission(ClubPermission.MEMBER_MANAGE));
        assertTrue(!access.hasPermission(ClubPermission.ACTIVITY_MANAGE));
        assertTrue(!access.hasPermission(ClubPermission.EVENT_OPERATE));
        assertTrue(!access.hasPermission(ClubPermission.EVENT_CHECKIN));
        assertTrue(!access.hasPermission(ClubPermission.FINANCE_READ));
        assertTrue(!access.hasPermission(ClubPermission.ROLE_MANAGE));
    }

    @Test
    void legacyAdminWithoutAssignmentCanManagePeopleButCannotRestoreRevokedExecutionOrTouchMoney() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(members.selectByClubAndMember(11L, 901L)).thenReturn(activeMember(11L, 901L, 1));
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(null), any(Date.class)))
                .thenReturn(Collections.emptyList());

        ClubAccessContext access = service.resolve(901L, 11L, null);

        assertTrue(access.getRoleCodes().contains(ClubRoleCode.CLUB_LEGACY_ADMIN));
        assertTrue(access.hasPermission(ClubPermission.MEMBER_LIST_READ));
        assertTrue(access.hasPermission(ClubPermission.MEMBER_APPROVE));
        assertTrue(access.hasPermission(ClubPermission.MEMBER_MANAGE));
        assertTrue(access.hasPermission(ClubPermission.CONTENT_MANAGE));
        assertTrue(!access.hasPermission(ClubPermission.NOTIFY_SEND));
        assertTrue(!access.hasPermission(ClubPermission.EVENT_OPERATE));
        assertTrue(!access.hasPermission(ClubPermission.EVENT_CHECKIN));
        assertTrue(!access.hasPermission(ClubPermission.CLUB_WRITE));
        assertTrue(!access.hasPermission(ClubPermission.ACTIVITY_MANAGE));
        assertTrue(!access.hasPermission(ClubPermission.FINANCE_READ));
        assertTrue(!access.hasPermission(ClubPermission.ROLE_MANAGE));
    }

    @Test
    void everyEventRoleIsBoundToServerValidatedActivityAndCannotReadMembersOrFinance() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(members.selectByClubAndMember(11L, 901L)).thenReturn(activeMember(11L, 901L, 0));
        when(activities.selectCmsActivityById(55L)).thenReturn(activity(55L, 11L));
        for (String roleCode : java.util.Arrays.asList(
                ClubRoleCode.EVENT_LEAD, ClubRoleCode.EVENT_CHECKIN)) {
            when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(55L), any(Date.class)))
                    .thenReturn(Collections.singletonList(activeEventRole(11L, 901L, 55L, roleCode)));

            ClubAccessContext access = service.resolve(901L, 11L, 55L);

            assertTrue(access.hasPermission(ClubPermission.EVENT_CHECKIN));
            assertTrue(!access.hasPermission(ClubPermission.MEMBER_LIST_READ));
            assertTrue(!access.hasPermission(ClubPermission.FINANCE_READ));
        }

        when(activities.selectCmsActivityById(56L)).thenReturn(activity(56L, 11L));
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(56L), any(Date.class)))
                .thenReturn(Collections.emptyList());
        ClubAccessContext anotherActivity = service.resolve(901L, 11L, 56L);
        assertTrue(!anotherActivity.hasPermission(ClubPermission.EVENT_OPERATE),
                "EVENT_LEAD 必须只授权所绑定的 activityId");
    }

    @Test
    void expiredAndCrossClubAssignmentsFailClosed() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(members.selectByClubAndMember(11L, 901L)).thenReturn(activeMember(11L, 901L, 0));
        ClubRoleAssignment expired = activeClubRole(11L, 901L, ClubRoleCode.CLUB_CO_OWNER);
        expired.setExpiresAt(new Date(System.currentTimeMillis() - 1000L));
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(null), any(Date.class)))
                .thenReturn(Collections.singletonList(expired));

        ClubAccessContext baseMember = service.resolve(901L, 11L, null);
        assertTrue(!baseMember.hasPermission(ClubPermission.FINANCE_READ));

        ClubRoleAssignment crossClub = activeClubRole(12L, 901L, ClubRoleCode.CLUB_CO_OWNER);
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(null), any(Date.class)))
                .thenReturn(Collections.singletonList(crossClub));
        assertThrows(ServiceException.class, () -> service.resolve(901L, 11L, null));
    }

    @Test
    void rejectsEventWhoseClubDoesNotMatchRequestedClub() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(activities.selectCmsActivityById(55L)).thenReturn(activity(55L, 12L));

        assertThrows(ServiceException.class, () -> service.resolve(900L, 11L, 55L));
    }

    @Test
    void clubScopedResolutionDiscoversOnlyDelegatedEventPermissions() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(members.selectByClubAndMember(11L, 901L)).thenReturn(activeMember(11L, 901L, 0));
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(null), any(Date.class)))
                .thenReturn(Collections.emptyList());
        when(assignments.selectActiveEventAccesses(eq(11L), eq(901L), any(Date.class)))
                .thenReturn(java.util.Arrays.asList(
                        activeEventRole(11L, 901L, 55L, TOPIC, ClubRoleCode.EVENT_CHECKIN),
                        activeEventRole(11L, 901L, 56L, 502L, ClubRoleCode.EVENT_LEAD)));

        ClubAccessContext access = service.resolveWithEventAccesses(901L, 11L);

        List<ClubEventAccess> eventAccesses = access.getEventAccesses();
        assertEquals(2, eventAccesses.size());
        assertEquals(55L, eventAccesses.get(0).getActivityId());
        assertEquals(TOPIC, eventAccesses.get(0).getTopicId());
        assertTrue(eventAccesses.get(0).getRoleCodes().contains(ClubRoleCode.EVENT_CHECKIN));
        assertTrue(eventAccesses.get(0).getPermissions().contains(ClubPermission.EVENT_CHECKIN));
        assertTrue(!eventAccesses.get(0).getPermissions().contains(ClubPermission.EVENT_OPERATE));
        assertEquals(56L, eventAccesses.get(1).getActivityId());
        assertEquals(502L, eventAccesses.get(1).getTopicId());
        assertTrue(eventAccesses.get(1).getPermissions().contains(ClubPermission.EVENT_OPERATE));
        assertTrue(!eventAccesses.get(1).getPermissions().contains(ClubPermission.FINANCE_READ));
        assertTrue(!access.getPermissions().contains(ClubPermission.EVENT_CHECKIN),
                "活动委派不得升格为 club 全局权限");
    }

    @Test
    void eventDiscoveryFailsClosedForAssignmentOutsideRequestedClub() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(members.selectByClubAndMember(11L, 901L)).thenReturn(activeMember(11L, 901L, 0));
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(null), any(Date.class)))
                .thenReturn(Collections.emptyList());
        when(assignments.selectActiveEventAccesses(eq(11L), eq(901L), any(Date.class)))
                .thenReturn(Collections.singletonList(
                        activeEventRole(12L, 901L, 55L, TOPIC, ClubRoleCode.EVENT_LEAD)));

        assertThrows(ServiceException.class, () -> service.resolveWithEventAccesses(901L, 11L));
    }

    @Test
    void eventDiscoveryFailsClosedWhenJoinedActivityHasNoTopic() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));
        when(members.selectByClubAndMember(11L, 901L)).thenReturn(activeMember(11L, 901L, 0));
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(null), any(Date.class)))
                .thenReturn(Collections.emptyList());
        when(assignments.selectActiveEventAccesses(eq(11L), eq(901L), any(Date.class)))
                .thenReturn(Collections.singletonList(
                        activeEventRole(11L, 901L, 55L, null, ClubRoleCode.EVENT_LEAD)));

        assertThrows(ServiceException.class, () -> service.resolveWithEventAccesses(901L, 11L));
    }

    @Test
    void ownerAndEventSpecificResolutionDoNotEnumerateEventAssignments() {
        when(clubs.selectClubById(11L)).thenReturn(activeClub(11L, 900L));

        ClubAccessContext owner = service.resolveWithEventAccesses(900L, 11L);

        assertTrue(owner.getEventAccesses().isEmpty());
        verify(assignments, never()).selectActiveEventAccesses(
                eq(11L), eq(900L), any(Date.class));

        when(members.selectByClubAndMember(11L, 901L)).thenReturn(activeMember(11L, 901L, 0));
        when(activities.selectCmsActivityById(55L)).thenReturn(activity(55L, 11L));
        when(assignments.selectActiveForAccess(eq(11L), eq(901L), eq(55L), any(Date.class)))
                .thenReturn(Collections.singletonList(
                        activeEventRole(11L, 901L, 55L, ClubRoleCode.EVENT_LEAD)));

        ClubAccessContext event = service.resolve(901L, 11L, 55L);

        assertTrue(event.getEventAccesses().isEmpty());
        verify(assignments, never()).selectActiveEventAccesses(
                eq(11L), eq(901L), any(Date.class));
    }

    private Club activeClub(Long id, Long ownerMemberId) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(ownerMemberId);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private ClubMember activeMember(Long clubId, Long memberId, Integer legacyRole) {
        ClubMember member = new ClubMember();
        member.setId(70L);
        member.setClubId(clubId);
        member.setMemberId(memberId);
        member.setRole(legacyRole);
        member.setStatus(1);
        member.setDelFlag(0);
        return member;
    }

    private ClubRoleAssignment activeClubRole(Long clubId, Long memberId, String roleCode) {
        ClubRoleAssignment row = new ClubRoleAssignment();
        row.setId(80L);
        row.setClubId(clubId);
        row.setTargetMemberId(memberId);
        row.setRoleCode(roleCode);
        row.setScopeType(ClubRoleAssignment.SCOPE_CLUB);
        row.setScopeId(clubId);
        row.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        return row;
    }

    private ClubRoleAssignment activeEventRole(Long clubId, Long memberId, Long activityId, String roleCode) {
        return activeEventRole(clubId, memberId, activityId, TOPIC, roleCode);
    }

    private ClubRoleAssignment activeEventRole(Long clubId, Long memberId, Long activityId,
                                                Long topicId, String roleCode) {
        ClubRoleAssignment row = activeClubRole(clubId, memberId, roleCode);
        row.setScopeType(ClubRoleAssignment.SCOPE_EVENT);
        row.setScopeId(activityId);
        row.setScopeTopicId(topicId);
        return row;
    }

    private CmsActivity activity(Long id, Long clubId) {
        CmsActivity activity = new CmsActivity();
        activity.setId(id);
        activity.setClubId(clubId);
        activity.setDelFlag(0);
        return activity;
    }
}
