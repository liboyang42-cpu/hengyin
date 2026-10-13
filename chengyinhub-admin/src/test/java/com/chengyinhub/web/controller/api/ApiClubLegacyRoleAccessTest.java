package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubEventAccess;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.vo.ClubLifecycleCommandResultVO;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubLifecycleCommandService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 旧 role=1 不能绕过新的俱乐部角色委派口径。 */
class ApiClubLegacyRoleAccessTest {
    private static final Long CLUB = 11L;
    private static final Long PRINCIPAL = 901L;

    @Test
    void revokedLegacyAdminCannotKeepModeratingContent() {
        Harness h = harness();
        ClubMember staleLegacyAdmin = new ClubMember();
        staleLegacyAdmin.setClubId(CLUB);
        staleLegacyAdmin.setMemberId(PRINCIPAL);
        staleLegacyAdmin.setRole(1);
        when(h.members.selectByClubAndMember(CLUB, PRINCIPAL)).thenReturn(staleLegacyAdmin);
        when(h.access.resolve(PRINCIPAL, CLUB, null)).thenReturn(new ClubAccessContext(
                PRINCIPAL, CLUB, 900L, null, Collections.singleton(ClubRoleCode.CLUB_MEMBER)));

        ClubPost request = new ClubPost();
        request.setId(81L);
        AjaxResult result = h.controller.postDelete(request);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.posts, never()).deleteClubPostById(81L);
        verify(h.members, never()).selectByClubAndMember(CLUB, PRINCIPAL);
    }

    @Test
    void activeCoOwnerCanModerateContent() {
        Harness h = harness();
        when(h.access.resolve(PRINCIPAL, CLUB, null)).thenReturn(new ClubAccessContext(
                PRINCIPAL, CLUB, 900L, null,
                new java.util.LinkedHashSet<>(Arrays.asList(
                        ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_CO_OWNER))));

        ClubPost request = new ClubPost();
        request.setId(81L);
        AjaxResult result = h.controller.postDelete(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.posts).deleteClubPostById(81L);
    }

    @Test
    void ownerCanModerateContentThroughAccessContext() {
        Harness h = harness();
        when(h.access.resolve(PRINCIPAL, CLUB, null)).thenReturn(new ClubAccessContext(
                PRINCIPAL, CLUB, PRINCIPAL, null,
                Collections.singleton(ClubRoleCode.CLUB_OWNER)));

        ClubPost request = new ClubPost();
        request.setId(81L);
        AjaxResult result = h.controller.postDelete(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.posts).deleteClubPostById(81L);
    }

    @Test
    void mismatchedAccessContextCannotCrossClubModerate() {
        Harness h = harness();
        when(h.access.resolve(PRINCIPAL, CLUB, null)).thenReturn(new ClubAccessContext(
                PRINCIPAL, 99L, PRINCIPAL, null,
                Collections.singleton(ClubRoleCode.CLUB_OWNER)));

        ClubPost request = new ClubPost();
        request.setId(81L);
        AjaxResult result = h.controller.postDelete(request);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.posts, never()).deleteClubPostById(81L);
    }

    @Test
    void crossClubAccessFailureCannotModerateContent() {
        Harness h = harness();
        when(h.access.resolve(PRINCIPAL, CLUB, null))
                .thenThrow(new ServiceException("俱乐部身份无效或权限不足", 403));

        ClubPost request = new ClubPost();
        request.setId(81L);
        AjaxResult result = h.controller.postDelete(request);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.posts, never()).deleteClubPostById(81L);
    }

    /**
     * #817↔#845 缝合:治理入口(canGovernClub / detail 的 viewerIsAdmin)读的是
     * club_member.role,只写 assignment 的「管理员」是个不生效的空衔。
     * C 端提权必须在 assign 之外同步写 role=1(复用带上限闸的后台同款 Service)。
     */
    @Test
    void legacySetRoleAssignsOperatorAndSyncsLegacyAdminRole() {
        Harness h = harness();
        when(h.roles.list(PRINCIPAL, CLUB, null)).thenReturn(Collections.emptyList());
        when(h.members.setMemberRoleWithAdminCap(CLUB, 902L, 1)).thenReturn(true);
        ClubMember request = roleRequest(902L, 1);

        AjaxResult result = h.controller.setMemberRole(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.roles).assign(PRINCIPAL, CLUB, 902L, ClubRoleCode.CLUB_OPERATOR,
                null, null, null);
        verify(h.members).setMemberRoleWithAdminCap(CLUB, 902L, 1);
    }

    @Test
    void legacySetRoleFailsClosedWhenAdminCapReached() {
        Harness h = harness();
        when(h.roles.list(PRINCIPAL, CLUB, null)).thenReturn(Collections.emptyList());
        when(h.members.setMemberRoleWithAdminCap(CLUB, 902L, 1)).thenReturn(false);

        AjaxResult result = h.controller.setMemberRole(roleRequest(902L, 1));

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        // cap 闸必须先于 assign:两步各自独立提交,若先 assign 再撞上限,
        // 会留下「报错但 assignment 已生效」的持久半态(cap 一直满就一直洗不掉)。
        verify(h.roles, never()).assign(any(), any(), any(), any(), any(), any(), any());
    }

    /** #817 遗留的坏状态(有 assignment、role=0)在 owner 重新点一次「设为管理员」时自愈。 */
    @Test
    void legacySetRoleHealsExistingAssignmentMissingLegacyRole() {
        Harness h = harness();
        when(h.roles.list(PRINCIPAL, CLUB, null))
                .thenReturn(Collections.singletonList(assignment(71L, 902L, 0)));
        when(h.members.setMemberRoleWithAdminCap(CLUB, 902L, 1)).thenReturn(true);

        AjaxResult result = h.controller.setMemberRole(roleRequest(902L, 1));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.roles, never()).assign(any(), any(), any(), any(), any(), any(), any());
        verify(h.members).setMemberRoleWithAdminCap(CLUB, 902L, 1);
    }

    @Test
    void legacyRevokeDelegatesToCanonicalLockedService() {
        Harness h = harness();
        ClubRoleAssignment assignment = assignment(71L, 902L, 3);
        when(h.roles.list(PRINCIPAL, CLUB, null)).thenReturn(Collections.singletonList(assignment));

        AjaxResult result = h.controller.setMemberRole(roleRequest(902L, 0));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.roles).revokeLegacyOperator(PRINCIPAL, CLUB, 902L,
                "通过旧成员角色入口撤销", null);
        verify(h.members, never()).setMemberRoleWithAdminCap(CLUB, 902L, 0);
    }

    @Test
    void legacyRevokeWithoutAssignmentStillClearsStoredLegacyAdmin() {
        Harness h = harness();
        when(h.roles.list(PRINCIPAL, CLUB, null)).thenReturn(Collections.emptyList());

        AjaxResult result = h.controller.setMemberRole(roleRequest(902L, 0));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.roles).revokeLegacyOperator(PRINCIPAL, CLUB, 902L,
                "通过旧成员角色入口撤销", null);
        verify(h.members, never()).setMemberRoleWithAdminCap(CLUB, 902L, 0);
    }

    @Test
    void nonOwnerCannotUseLegacyRoleEndpoint() {
        Harness h = harness();
        when(h.roles.list(PRINCIPAL, CLUB, null))
                .thenThrow(new ServiceException("俱乐部身份无效或权限不足", 403));

        AjaxResult result = h.controller.setMemberRole(roleRequest(902L, 1));

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(h.roles, never()).assign(PRINCIPAL, CLUB, 902L, ClubRoleCode.CLUB_OPERATOR,
                null, null, null);
        verify(h.members, never()).setMemberRoleWithAdminCap(CLUB, 902L, 1);
    }

    @Test
    void activeCoOwnerCanRemoveMemberThroughMemberManagePermission() {
        Harness h = harness();
        Club club = club(900L);
        ClubMember target = new ClubMember();
        target.setClubId(CLUB);
        target.setMemberId(902L);
        when(h.clubs.selectClubById(CLUB)).thenReturn(club);
        when(h.members.selectByClubAndMember(CLUB, 902L)).thenReturn(target);
        ClubLifecycleCommandResultVO lifecycle = new ClubLifecycleCommandResultVO();
        lifecycle.setAffectedRows(1);
        when(h.lifecycle.removeMember(eq(CLUB), eq(902L), eq(PRINCIPAL), any())).thenReturn(lifecycle);
        when(h.access.resolve(PRINCIPAL, CLUB, null)).thenReturn(new ClubAccessContext(
                PRINCIPAL, CLUB, 900L, null,
                new java.util.LinkedHashSet<>(Arrays.asList(
                        ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_CO_OWNER))));

        ClubMember request = new ClubMember();
        request.setClubId(CLUB);
        request.setMemberId(902L);
        AjaxResult result = h.controller.removeMember(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.lifecycle).removeMember(eq(CLUB), eq(902L), eq(PRINCIPAL), any());
    }

    /**
     * #845 治理权对撞:CLUB_CO_OWNER/CLUB_OPERATOR 走 club_role_assignment,club_member.role
     * 是 0,旧的「管理员不得互踢」只查 role==1 → legacy 管理员可以移除 co-owner(低权剥高权,
     * 移除即全权限归零),绕过「角色分配仅 owner」。非 owner 不得移除任何持有活跃角色委派的成员。
     */
    @Test
    void legacyAdminCannotRemoveMemberHoldingActiveRoleAssignment() {
        Harness h = harness();
        Club club = club(900L);
        ClubMember target = new ClubMember();
        target.setClubId(CLUB);
        target.setMemberId(902L);
        target.setRole(0);   // co-owner 的 legacy role 就是 0 —— 这正是旧闸的盲区
        when(h.clubs.selectClubById(CLUB)).thenReturn(club);
        when(h.members.selectByClubAndMember(CLUB, 902L)).thenReturn(target);
        when(h.members.canGovernClub(club, PRINCIPAL)).thenReturn(true);   // 演员是 legacy 管理员
        when(h.access.resolveWithEventAccesses(902L, CLUB)).thenReturn(new ClubAccessContext(
                902L, CLUB, 900L, null,
                new java.util.LinkedHashSet<>(Arrays.asList(
                        ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_CO_OWNER))));

        ClubMember request = new ClubMember();
        request.setClubId(CLUB);
        request.setMemberId(902L);
        AjaxResult result = h.controller.removeMember(request);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.lifecycle, never()).removeMember(any(), any(), any(), any());
    }

    @Test
    void legacyAdminCannotRemoveEventRoleHolder() {
        Harness h = harness();
        Club club = club(900L);
        ClubMember target = new ClubMember();
        target.setClubId(CLUB);
        target.setMemberId(902L);
        target.setRole(0);
        when(h.clubs.selectClubById(CLUB)).thenReturn(club);
        when(h.members.selectByClubAndMember(CLUB, 902L)).thenReturn(target);
        when(h.members.canGovernClub(club, PRINCIPAL)).thenReturn(true);
        when(h.access.resolveWithEventAccesses(902L, CLUB)).thenReturn(new ClubAccessContext(
                902L, CLUB, 900L, null,
                Collections.singleton(ClubRoleCode.CLUB_MEMBER),
                Collections.singletonList(new ClubEventAccess(55L, 501L,
                        Collections.singleton(ClubRoleCode.EVENT_LEAD)))));

        ClubMember request = new ClubMember();
        request.setClubId(CLUB);
        request.setMemberId(902L);
        AjaxResult result = h.controller.removeMember(request);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.lifecycle, never()).removeMember(any(), any(), any(), any());
    }

    @Test
    void ownerCanStillRemoveRoleAssignmentHolder() {
        Harness h = harness();
        Club club = club(PRINCIPAL);   // 演员就是主理人
        ClubMember target = new ClubMember();
        target.setClubId(CLUB);
        target.setMemberId(902L);
        target.setRole(0);
        when(h.clubs.selectClubById(CLUB)).thenReturn(club);
        when(h.members.selectByClubAndMember(CLUB, 902L)).thenReturn(target);
        ClubLifecycleCommandResultVO lifecycle = new ClubLifecycleCommandResultVO();
        lifecycle.setAffectedRows(1);
        when(h.lifecycle.removeMember(eq(CLUB), eq(902L), eq(PRINCIPAL), any())).thenReturn(lifecycle);

        ClubMember request = new ClubMember();
        request.setClubId(CLUB);
        request.setMemberId(902L);
        AjaxResult result = h.controller.removeMember(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.lifecycle).removeMember(eq(CLUB), eq(902L), eq(PRINCIPAL), any());
    }

    @Test
    void clubOperatorCannotRemoveMemberWithoutMemberManagePermission() {
        Harness h = harness();
        when(h.clubs.selectClubById(CLUB)).thenReturn(club(900L));
        when(h.access.resolve(PRINCIPAL, CLUB, null)).thenReturn(new ClubAccessContext(
                PRINCIPAL, CLUB, 900L, null,
                new java.util.LinkedHashSet<>(Arrays.asList(
                        ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_OPERATOR))));

        ClubMember request = new ClubMember();
        request.setClubId(CLUB);
        request.setMemberId(902L);
        AjaxResult result = h.controller.removeMember(request);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(h.lifecycle, never()).removeMember(any(), any(), any(), any());
    }

    private Harness harness() {
        Harness h = new Harness();
        h.controller = spy(new ApiClubController());
        doReturn(PRINCIPAL).when(h.controller).getAppUserId();
        h.clubs = mock(IClubService.class);
        h.members = mock(IClubMemberService.class);
        h.posts = mock(IClubPostService.class);
        h.access = mock(IClubAccessService.class);
        h.roles = mock(IClubRoleAssignmentService.class);
        h.lifecycle = mock(IClubLifecycleCommandService.class);
        ReflectionTestUtils.setField(h.controller, "clubService", h.clubs);
        ReflectionTestUtils.setField(h.controller, "clubMemberService", h.members);
        ReflectionTestUtils.setField(h.controller, "clubPostService", h.posts);
        ReflectionTestUtils.setField(h.controller, "clubAccessService", h.access);
        ReflectionTestUtils.setField(h.controller, "clubRoleAssignmentService", h.roles);
        ReflectionTestUtils.setField(h.controller, "clubLifecycleCommandService", h.lifecycle);
        ClubPost post = new ClubPost();
        post.setId(81L);
        post.setClubId(CLUB);
        post.setAuthorMemberId(902L);
        when(h.posts.selectClubPostById(81L)).thenReturn(post);
        return h;
    }

    private ClubMember roleRequest(Long targetMemberId, int role) {
        ClubMember request = new ClubMember();
        request.setClubId(CLUB);
        request.setMemberId(targetMemberId);
        request.setRole(role);
        return request;
    }

    private Club club(Long ownerId) {
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(ownerId);
        club.setStatus(1L);
        return club;
    }

    private ClubRoleAssignment assignment(Long id, Long targetMemberId, int version) {
        ClubRoleAssignment row = new ClubRoleAssignment();
        row.setId(id);
        row.setClubId(CLUB);
        row.setTargetMemberId(targetMemberId);
        row.setRoleCode(ClubRoleCode.CLUB_OPERATOR);
        row.setScopeType(ClubRoleAssignment.SCOPE_CLUB);
        row.setScopeId(CLUB);
        row.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        row.setVersion(version);
        return row;
    }

    private static final class Harness {
        private ApiClubController controller;
        private IClubService clubs;
        private IClubMemberService members;
        private IClubPostService posts;
        private IClubAccessService access;
        private IClubRoleAssignmentService roles;
        private IClubLifecycleCommandService lifecycle;
    }
}
