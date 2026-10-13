package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubControllerSafetyTest {

    private IClubService clubService;
    private IClubLeaderService clubLeaderService;
    private IClubMemberService clubMemberService;
    private IClubMembershipService clubMembershipService;
    private IClubRoleAssignmentService clubRoleAssignmentService;
    private IClubPostService clubPostService;
    private IAuditTaskService auditTaskService;
    private ClubController controller;

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        clubLeaderService = mock(IClubLeaderService.class);
        clubMemberService = mock(IClubMemberService.class);
        clubMembershipService = mock(IClubMembershipService.class);
        clubRoleAssignmentService = mock(IClubRoleAssignmentService.class);
        clubPostService = mock(IClubPostService.class);
        auditTaskService = mock(IAuditTaskService.class);
        controller = new ClubController();
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubLeaderService", clubLeaderService);
        ReflectionTestUtils.setField(controller, "clubMemberService", clubMemberService);
        ReflectionTestUtils.setField(controller, "clubMembershipService", clubMembershipService);
        ReflectionTestUtils.setField(controller, "clubRoleAssignmentService", clubRoleAssignmentService);
        ReflectionTestUtils.setField(controller, "clubPostService", clubPostService);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void editWhitelistsProfileFieldsAndCannotWriteGovernanceState() {
        Club request = new Club();
        request.setId(7L);
        request.setMemberId(99L);
        request.setName("更新后的俱乐部");
        request.setDescription("新的简介");
        request.setStatus(1L);
        request.setRejectReason("绕过审核");
        request.setLevel(5);
        request.setTags("后台越权标签");
        request.setAuditBy("attacker");
        request.setDelFlag(1);
        when(clubService.updateClub(org.mockito.ArgumentMatchers.any(Club.class))).thenReturn(1);

        controller.edit(request);

        ArgumentCaptor<Club> captor = ArgumentCaptor.forClass(Club.class);
        verify(clubService).updateClub(captor.capture());
        Club saved = captor.getValue();
        assertEquals(7L, saved.getId());
        assertEquals("更新后的俱乐部", saved.getName());
        assertEquals("新的简介", saved.getDescription());
        assertNull(saved.getMemberId());
        assertNull(saved.getStatus());
        assertNull(saved.getRejectReason());
        assertNull(saved.getLevel());
        assertNull(saved.getTags());
        assertNull(saved.getAuditBy());
        assertNull(saved.getDelFlag());
    }

    @Test
    void leaderAuditRejectsLevelOutsideL1ToL5() {
        ClubLeader request = new ClubLeader();
        request.setMemberId(99L);
        request.setLevel(6);

        AjaxResult result = controller.leaderAudit(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(clubLeaderService, never()).selectClubLeaderByMemberId(99L);
    }

    @Test
    void leaderAuditRejectsMissingLevel() {
        ClubLeader request = new ClubLeader();
        request.setMemberId(99L);

        AjaxResult result = controller.leaderAudit(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(clubLeaderService, never()).selectClubLeaderByMemberId(99L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void workspaceReturnsClubAndItsLeaderApplicationProfile() {
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(99L);
        ClubLeader leader = new ClubLeader();
        leader.setMemberId(99L);
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(clubLeaderService.selectClubLeaderByMemberId(99L)).thenReturn(leader);

        AjaxResult result = controller.workspace(7L);

        java.util.Map<String, Object> data = (java.util.Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertSame(club, data.get("club"));
        assertSame(leader, data.get("leader"));
    }

    @Test
    void auditRejectsUnknownStatusBeforeLoadingClub() {
        Club request = new Club();
        request.setId(7L);
        request.setStatus(9L);

        AjaxResult result = controller.audit(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(clubService, never()).selectClubById(7L);
    }

    @Test
    void auditRejectsBlankRejectReasonBeforeLoadingClub() {
        Club request = new Club();
        request.setId(7L);
        request.setStatus(2L);
        request.setRejectReason("  ");

        AjaxResult result = controller.audit(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(clubService, never()).selectClubById(7L);
    }

    @Test
    void editWithOnlyGovernanceFieldsFailsClosed() {
        Club request = new Club();
        request.setId(7L);
        request.setStatus(1L);
        request.setLevel(5);

        AjaxResult result = controller.edit(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(clubService, never()).updateClub(org.mockito.ArgumentMatchers.any(Club.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void governanceReturnsMembersAndActivePostsForTheSameClub() {
        Club club = new Club();
        club.setId(7L);
        ClubMember member = new ClubMember();
        member.setClubId(7L);
        ClubPost post = new ClubPost();
        post.setClubId(7L);
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(clubMemberService.selectClubMemberList(org.mockito.ArgumentMatchers.any(ClubMember.class)))
            .thenReturn(Collections.singletonList(member));
        when(clubPostService.selectClubPostList(org.mockito.ArgumentMatchers.any(ClubPost.class)))
            .thenReturn(Collections.singletonList(post));

        AjaxResult result = controller.governance(7L);

        java.util.Map<String, Object> data = (java.util.Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertSame(member, ((java.util.List<ClubMember>) data.get("members")).get(0));
        assertSame(post, ((java.util.List<ClubPost>) data.get("posts")).get(0));
    }

    @Test
    void updateMemberRoleRejectsUnknownRoleBeforeLoadingData() {
        ClubMember request = new ClubMember();
        request.setRole(9);

        AjaxResult result = controller.updateMemberRole(7L, 99L, request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(clubService, never()).selectClubById(7L);
        verify(clubMemberService, never()).setMemberRoleWithAdminCap(7L, 99L, 9);
    }

    @Test
    void updateMemberRoleCannotTreatClubOwnerAsOrdinaryMember() {
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(99L);
        ClubMember request = new ClubMember();
        request.setRole(1);
        when(clubService.selectClubById(7L)).thenReturn(club);

        AjaxResult result = controller.updateMemberRole(7L, 99L, request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(clubMemberService, never()).setMemberRoleWithAdminCap(7L, 99L, 1);
    }

    @Test
    void updateMemberRoleUsesTransactionalAdminCapService() {
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(1L);
        ClubMember member = new ClubMember();
        member.setClubId(7L);
        member.setMemberId(99L);
        ClubMember request = new ClubMember();
        request.setRole(1);
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(clubMemberService.selectByClubAndMember(7L, 99L)).thenReturn(member);
        when(clubMemberService.setMemberRoleWithAdminCap(7L, 99L, 1)).thenReturn(true);
        loginAs(42L);

        AjaxResult result = controller.updateMemberRole(7L, 99L, request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(clubMemberService).setMemberRoleWithAdminCap(7L, 99L, 1);
        verify(clubRoleAssignmentService).setPlatformOperatorRole(
                42L, 7L, 99L, true, null);
    }

    @Test
    void removeMemberVerifiesRelationshipBeforeChangingMemberCount() {
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(1L);
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(clubMemberService.selectByClubAndMember(7L, 99L)).thenReturn(null);

        AjaxResult result = controller.removeMember(7L, 99L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(clubMemberService, never()).quitMemberAndLeaveGroup(7L, 99L);
    }

    @Test
    void removeMemberUsesAtomicRelationshipAndCountService() {
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(1L);
        ClubMember member = new ClubMember();
        member.setClubId(7L);
        member.setMemberId(99L);
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(clubMemberService.selectByClubAndMember(7L, 99L)).thenReturn(member);
        loginAs(42L);
        when(clubMemberService.quitMemberAndLeaveGroup(
                7L, 99L, 42L, "PLATFORM_ADMIN", "MEMBER_REMOVED_BY_PLATFORM")).thenReturn(1);

        AjaxResult result = controller.removeMember(7L, 99L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(clubMemberService).quitMemberAndLeaveGroup(
                7L, 99L, 42L, "PLATFORM_ADMIN", "MEMBER_REMOVED_BY_PLATFORM");
        verify(clubMembershipService).handleMemberAccessEnded(
                7L, 99L, "MEMBER_REMOVED", "platform-club-member-removed-7-99");
    }

    @Test
    void removeMemberReportsImCleanupFailureInsteadOfFalseSuccess() {
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(1L);
        ClubMember member = new ClubMember();
        member.setClubId(7L);
        member.setMemberId(99L);
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(clubMemberService.selectByClubAndMember(7L, 99L)).thenReturn(member);
        loginAs(42L);
        doThrow(new RuntimeException("IM_MARK_DELETED_FAILED"))
                .when(clubMemberService).quitMemberAndLeaveGroup(
                        7L, 99L, 42L, "PLATFORM_ADMIN", "MEMBER_REMOVED_BY_PLATFORM");

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> controller.removeMember(7L, 99L));

        assertEquals("IM_MARK_DELETED_FAILED", error.getMessage());
        verify(clubMemberService).quitMemberAndLeaveGroup(
                7L, 99L, 42L, "PLATFORM_ADMIN", "MEMBER_REMOVED_BY_PLATFORM");
        verify(clubMembershipService, never()).handleMemberAccessEnded(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void removeClubCascadesPaidMembershipAccessEndAfterDissolution() {
        Long[] ids = new Long[] { 7L, 8L };
        when(clubService.selectClubById(7L)).thenReturn(new Club());
        when(clubService.selectClubById(8L)).thenReturn(new Club());
        when(clubService.deleteClubByIds(ids)).thenReturn(2);

        AjaxResult result = controller.remove(ids);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(clubMembershipService).handleClubAccessEnded(
                7L, "CLUB_DISSOLVED", "platform-club-dissolved-7");
        verify(clubMembershipService).handleClubAccessEnded(
                8L, "CLUB_DISSOLVED", "platform-club-dissolved-8");
    }

    @Test
    void removeClubPropagatesAccessEndFailureUnderOneTransaction() throws Exception {
        Long[] ids = new Long[] { 7L };
        when(clubService.selectClubById(7L)).thenReturn(new Club());
        when(clubService.deleteClubByIds(ids)).thenReturn(1);
        doThrow(new RuntimeException("ACCESS_END_FAILED"))
                .when(clubMembershipService).handleClubAccessEnded(
                        7L, "CLUB_DISSOLVED", "platform-club-dissolved-7");

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> controller.remove(ids));

        assertEquals("ACCESS_END_FAILED", error.getMessage());
        assertSame(Transactional.class, ClubController.class
                .getMethod("remove", Long[].class).getAnnotation(Transactional.class).annotationType());
        verify(clubService).deleteClubByIds(ids);
    }

    @Test
    void submitPostForReviewRejectsCrossClubPost() {
        Club club = new Club();
        club.setId(7L);
        ClubPost post = new ClubPost();
        post.setId(88L);
        post.setClubId(8L);
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(clubPostService.selectClubPostById(88L)).thenReturn(post);

        AjaxResult result = controller.submitPostForReview(7L, 88L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(auditTaskService, never()).reportContent(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void submitPostForReviewUsesUnifiedAuditQueueAndCurrentOperator() {
        Club club = new Club();
        club.setId(7L);
        ClubPost post = new ClubPost();
        post.setId(88L);
        post.setClubId(7L);
        when(clubService.selectClubById(7L)).thenReturn(club);
        when(clubPostService.selectClubPostById(88L)).thenReturn(post);
        when(auditTaskService.reportContent("CLUB_POST", 88L, 42L, "后台俱乐部工作区发起复核"))
            .thenReturn(123L);
        loginAs(42L);

        AjaxResult result = controller.submitPostForReview(7L, 88L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(auditTaskService).reportContent("CLUB_POST", 88L, 42L, "后台俱乐部工作区发起复核");
        verify(clubPostService, never()).deleteClubPostById(88L);
        assertEquals(123L, ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("taskId"));
    }

    private void loginAs(Long userId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        LoginUser loginUser = new LoginUser(user, new HashSet<>());
        loginUser.setUserId(userId);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }
}
