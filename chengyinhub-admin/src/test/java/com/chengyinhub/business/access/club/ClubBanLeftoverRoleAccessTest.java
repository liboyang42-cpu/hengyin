package com.chengyinhub.business.access.club;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubRoleAssignment;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SC-CL-BAN：封禁会删除成员行；若撤销角色那一步漏了一条仍为 ACTIVE 的 club_role_assignment，
 * 或旧成员行残留 role=1 但已非在籍状态，重新打开治理入口也不能凭旧角色拿到任何权限。
 */
class ClubBanLeftoverRoleAccessTest {
    private static final long CLUB = 11L;
    private static final long OWNER = 900L;
    private static final long BANNED = 901L;

    private ClubMapper clubs;
    private ClubMemberMapper members;
    private ClubRoleAssignmentMapper assignments;
    private ClubAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        clubs = mock(ClubMapper.class);
        members = mock(ClubMemberMapper.class);
        assignments = mock(ClubRoleAssignmentMapper.class);
        UmsMemberMapper accounts = mock(UmsMemberMapper.class);
        service = new ClubAccessServiceImpl();
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "clubMemberMapper", members);
        ReflectionTestUtils.setField(service, "clubRoleAssignmentMapper", assignments);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberMapper", accounts);
        when(accounts.lockActiveMemberById(any())).thenAnswer(call -> call.getArgument(0));
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubs.selectClubById(CLUB)).thenReturn(club);
        ClubRoleAssignment leftover = new ClubRoleAssignment();
        leftover.setId(80L);
        leftover.setClubId(CLUB);
        leftover.setTargetMemberId(BANNED);
        leftover.setRoleCode(ClubRoleCode.CLUB_CO_OWNER);
        leftover.setScopeType(ClubRoleAssignment.SCOPE_CLUB);
        leftover.setScopeId(CLUB);
        leftover.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        when(assignments.selectActiveForAccess(any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(leftover));
    }

    @Test
    void bannedMemberWithoutMembershipRowGetsNoAccessEvenIfACoOwnerAssignmentSurvived() {
        when(members.selectByClubAndMember(CLUB, BANNED)).thenReturn(null);

        assertNull(service.resolve(BANNED, CLUB, null));
        for (String permission : new String[] {ClubPermission.MEMBER_MANAGE, ClubPermission.ROLE_MANAGE,
                ClubPermission.MEMBER_LIST_READ}) {
            ServiceException denied = assertThrows(ServiceException.class,
                    () -> service.require(BANNED, CLUB, null, permission));
            assertEquals(403, denied.getCode());
        }
    }

    @Test
    void staleLegacyAdminRowThatIsNoLongerActiveDoesNotRestoreGovernance() {
        ClubMember stale = new ClubMember();
        stale.setId(70L);
        stale.setClubId(CLUB);
        stale.setMemberId(BANNED);
        stale.setRole(1);
        stale.setStatus(0);
        stale.setDelFlag(0);
        when(members.selectByClubAndMember(CLUB, BANNED)).thenReturn(stale);

        assertNull(service.resolve(BANNED, CLUB, null));
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.require(BANNED, CLUB, null, ClubPermission.MEMBER_MANAGE)).getCode());
    }
}
