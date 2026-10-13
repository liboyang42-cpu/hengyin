package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.vo.ClubLifecycleCommandResultVO;
import com.chengyinhub.business.service.impl.ClubLifecycleCommandServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubLifecycleCommandAuthorizationTest {
    private static final Long CLUB_ID = 73L;
    private static final Long TARGET_MEMBER_ID = 42L;
    private static final Long REVOKED_CO_OWNER_ID = 10L;

    private IClubService clubService;
    private IClubAccessService accessService;
    private IClubMemberService memberService;
    private ClubLifecycleCommandServiceImpl lifecycleService;

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        accessService = mock(IClubAccessService.class);
        memberService = mock(IClubMemberService.class);
        lifecycleService = new ClubLifecycleCommandServiceImpl();
        ReflectionTestUtils.setField(lifecycleService, "clubService", clubService);
        ReflectionTestUtils.setField(lifecycleService, "clubAccessService", accessService);
        ReflectionTestUtils.setField(lifecycleService, "clubMemberService", memberService);
        ReflectionTestUtils.setField(lifecycleService, "clubMembershipService", mock(IClubMembershipService.class));
    }

    @Test
    void revokedCoOwnerCannotRemoveMemberUsingRoleObservedBeforeRevocation() {
        Club locked = activeClub();
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(locked);
        when(accessService.resolve(REVOKED_CO_OWNER_ID, CLUB_ID, null)).thenReturn(
                new ClubAccessContext(REVOKED_CO_OWNER_ID, CLUB_ID, 9L, null,
                        Collections.singleton(ClubRoleCode.CLUB_MEMBER)));

        ServiceException error = assertThrows(ServiceException.class,
                () -> lifecycleService.removeMember(
                        CLUB_ID, TARGET_MEMBER_ID, REVOKED_CO_OWNER_ID, "remove-after-role-revoked"));

        assertEquals(Integer.valueOf(403), error.getCode());
        verify(memberService, never()).quitMemberAndLeaveGroup(any(), any(), any(), any(), any());
    }

    @Test
    void currentCoOwnerIsResolvedAfterClubLockAndAuditRoleComesFromCurrentAccess() {
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(activeClub());
        when(accessService.resolve(REVOKED_CO_OWNER_ID, CLUB_ID, null)).thenReturn(
                new ClubAccessContext(REVOKED_CO_OWNER_ID, CLUB_ID, 9L, null,
                        new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                                ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_CO_OWNER))));
        when(memberService.quitMemberAndLeaveGroup(
                CLUB_ID, TARGET_MEMBER_ID, REVOKED_CO_OWNER_ID,
                ClubRoleCode.CLUB_CO_OWNER, "MEMBER_REMOVED")).thenReturn(1);

        ClubLifecycleCommandResultVO result = lifecycleService.removeMember(
                CLUB_ID, TARGET_MEMBER_ID, REVOKED_CO_OWNER_ID, "remove-current-co-owner");

        assertEquals(1, result.getAffectedRows());
        org.mockito.InOrder order = inOrder(clubService, accessService, memberService);
        order.verify(clubService).selectClubByIdForUpdate(CLUB_ID);
        order.verify(accessService).resolve(REVOKED_CO_OWNER_ID, CLUB_ID, null);
        order.verify(memberService).quitMemberAndLeaveGroup(
                CLUB_ID, TARGET_MEMBER_ID, REVOKED_CO_OWNER_ID,
            ClubRoleCode.CLUB_CO_OWNER, "MEMBER_REMOVED");
    }

    @Test
    void legacyAdminCanRemoveOrdinaryMemberAfterClubLock() {
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(activeClub());
        when(accessService.resolve(REVOKED_CO_OWNER_ID, CLUB_ID, null)).thenReturn(
                new ClubAccessContext(REVOKED_CO_OWNER_ID, CLUB_ID, 9L, null,
                        new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                                ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_LEGACY_ADMIN))));
        when(memberService.selectByClubAndMember(CLUB_ID, TARGET_MEMBER_ID))
                .thenReturn(activeMember(0));
        when(memberService.quitMemberAndLeaveGroup(
                CLUB_ID, TARGET_MEMBER_ID, REVOKED_CO_OWNER_ID,
                ClubRoleCode.CLUB_LEGACY_ADMIN, "MEMBER_REMOVED")).thenReturn(1);

        ClubLifecycleCommandResultVO result = lifecycleService.removeMember(
                CLUB_ID, TARGET_MEMBER_ID, REVOKED_CO_OWNER_ID, "legacy-admin-remove-member");

        assertEquals(1, result.getAffectedRows());
        verify(memberService).quitMemberAndLeaveGroup(
                CLUB_ID, TARGET_MEMBER_ID, REVOKED_CO_OWNER_ID,
                ClubRoleCode.CLUB_LEGACY_ADMIN, "MEMBER_REMOVED");
    }

    @Test
    void nonOwnerManagerCannotRemoveActiveLegacyAdmin() {
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(activeClub());
        when(accessService.resolve(REVOKED_CO_OWNER_ID, CLUB_ID, null)).thenReturn(
                new ClubAccessContext(REVOKED_CO_OWNER_ID, CLUB_ID, 9L, null,
                        new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                                ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_LEGACY_ADMIN))));
        when(memberService.selectByClubAndMember(CLUB_ID, TARGET_MEMBER_ID))
                .thenReturn(activeMember(1));

        ServiceException error = assertThrows(ServiceException.class,
                () -> lifecycleService.removeMember(
                        CLUB_ID, TARGET_MEMBER_ID, REVOKED_CO_OWNER_ID,
                        "legacy-admin-remove-admin"));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(memberService, never()).quitMemberAndLeaveGroup(any(), any(), any(), any(), any());
    }

    private Club activeClub() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(9L);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private ClubMember activeMember(int role) {
        ClubMember member = new ClubMember();
        member.setId(81L);
        member.setClubId(CLUB_ID);
        member.setMemberId(TARGET_MEMBER_ID);
        member.setRole(role);
        member.setStatus(1);
        member.setDelFlag(0);
        return member;
    }
}
