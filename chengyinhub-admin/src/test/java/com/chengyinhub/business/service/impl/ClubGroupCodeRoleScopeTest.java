package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessServiceImpl;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClubGroupCodeRoleScopeTest {
    private static final Long TOPIC = 300L;
    private static final Long CLUB = 610L;
    private static final Long ACTIVITY = 301L;
    private static final Long OWNER = 42L;
    private static final Long MEMBER = 43L;

    private ClubMapper clubs;
    private ClubMemberMapper members;
    private ClubRoleAssignmentMapper assignments;
    private CmsActivityMapper activities;
    private UmsMemberMapper accounts;
    private CoopOrderMapper orders;
    private ICmsActivityService activityService;
    private ClubAccessServiceImpl access;
    private ClubMemberServiceImpl memberService;

    @BeforeEach
    void setUp() {
        clubs = mock(ClubMapper.class);
        members = mock(ClubMemberMapper.class);
        assignments = mock(ClubRoleAssignmentMapper.class);
        activities = mock(CmsActivityMapper.class);
        accounts = mock(UmsMemberMapper.class);
        orders = mock(CoopOrderMapper.class);
        activityService = mock(ICmsActivityService.class);

        access = new ClubAccessServiceImpl();
        ReflectionTestUtils.setField(access, "clubMapper", clubs);
        ReflectionTestUtils.setField(access, "clubMemberMapper", members);
        ReflectionTestUtils.setField(access, "clubRoleAssignmentMapper", assignments);
        ReflectionTestUtils.setField(access, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(access, "umsMemberMapper", accounts);

        memberService = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(memberService, "clubMapper", clubs);
        ReflectionTestUtils.setField(memberService, "clubMemberMapper", members);
        ReflectionTestUtils.setField(memberService, "coopOrderMapper", orders);
        ReflectionTestUtils.setField(memberService, "clubAccessService", access);

        when(clubs.selectClubById(CLUB)).thenReturn(activeClub());
        when(accounts.selectRolePermissionSubject(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            com.chengyinhub.business.domain.UmsMember m = new com.chengyinhub.business.domain.UmsMember();
            m.setId(call.getArgument(0)); m.setRole("club"); m.setStatus(1L); m.setDelFlag(0); return m;
        });
        when(accounts.lockActiveMemberById(any())).thenAnswer(call -> call.getArgument(0));
        when(members.selectByClubAndMember(CLUB, MEMBER)).thenReturn(activeLegacyAdmin());
        when(assignments.selectActiveForAccess(eq(CLUB), eq(MEMBER), eq(null), any(Date.class)))
                .thenReturn(Collections.emptyList());
        when(orders.selectEffectiveOrdersByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(clubOrder()));
    }

    @Test
    void revokedAssignmentDoesNotRestoreLegacyAdminTopicAuthority() {
        assertFalse(memberService.isTopicOrganizer(TOPIC, CLUB, MEMBER),
                "assignment 撤销后，历史 club_member.role=1 不得继续放大组织权限");
    }

    @Test
    void activeClubCoOwnerAssignmentGrantsTopicOrganizerAuthority() {
        when(assignments.selectActiveForAccess(eq(CLUB), eq(MEMBER), eq(null), any(Date.class)))
                .thenReturn(Collections.singletonList(clubRole(ClubRoleCode.CLUB_CO_OWNER)));

        assertTrue(memberService.isTopicOrganizer(TOPIC, CLUB, MEMBER));
    }

    @Test
    void clubOperatorCannotOrganizeTopicsWithoutActivityScope() {
        when(assignments.selectActiveForAccess(eq(CLUB), eq(MEMBER), eq(null), any(Date.class)))
                .thenReturn(Collections.singletonList(clubRole(ClubRoleCode.CLUB_OPERATOR)));

        assertFalse(memberService.isTopicOrganizer(TOPIC, CLUB, MEMBER));
    }

    @Test
    void clubCoOwnerAssignmentCanIssueActivityGroupCode() {
        CmsActivity activity = activeActivity();
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(assignments.selectActiveForAccess(eq(CLUB), eq(MEMBER), eq(ACTIVITY), any(Date.class)))
                .thenReturn(Collections.singletonList(clubRole(ClubRoleCode.CLUB_CO_OWNER)));
        GroupCodeServiceImpl groupCodes = new GroupCodeServiceImpl(activityService, access,
                orders, mock(CoopGroupVerifyMapper.class), qrcodeService(), "test-secret");

        AjaxResult result = groupCodes.issue(ACTIVITY, MEMBER);

        assertEquals(200, result.get("code"));
        assertNotNull(result.get("data"));
    }

    @Test
    void revokedAssignmentDoesNotRestoreLegacyAdminGroupCodeAuthority() {
        CmsActivity activity = activeActivity();
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        GroupCodeServiceImpl groupCodes = groupCodes();

        AjaxResult result = groupCodes.issue(ACTIVITY, MEMBER);

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("团码核销权限"));
    }

    @Test
    void clubOwnerRemainsAuthorizedWithoutPersistedAssignment() {
        CmsActivity activity = activeActivity();
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        AjaxResult result = groupCodes().issue(ACTIVITY, OWNER);

        assertEquals(200, result.get("code"));
    }

    @Test
    void activityWithUnknownDeletionStateCannotIssueGroupCode() {
        CmsActivity activity = activeActivity();
        activity.setDelFlag(null);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        AjaxResult result = groupCodes().issue(ACTIVITY, OWNER);

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("不可带队核销"));
    }

    @Test
    void draftOrUnpublishedActivityCannotIssueSettlementGroupCode() {
        CmsActivity draft = activeActivity();
        draft.setStatus(0L);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(draft);
        assertEquals(500, groupCodes().issue(ACTIVITY, OWNER).get("code"));

        CmsActivity unpublished = activeActivity();
        unpublished.setPublishStatus(0L);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(unpublished);
        assertEquals(500, groupCodes().issue(ACTIVITY, OWNER).get("code"));
    }

    private Club activeClub() {
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private ClubMember activeLegacyAdmin() {
        ClubMember member = new ClubMember();
        member.setId(71L);
        member.setClubId(CLUB);
        member.setMemberId(MEMBER);
        member.setRole(1);
        member.setStatus(1);
        member.setDelFlag(0);
        return member;
    }

    private CoopOrder clubOrder() {
        CoopOrder order = new CoopOrder();
        order.setTopicId(TOPIC);
        order.setToType("club");
        order.setToId(CLUB);
        return order;
    }

    private CmsActivity activeActivity() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(CLUB);
        activity.setDelFlag(0);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        return activity;
    }

    private ClubRoleAssignment clubRole(String roleCode) {
        ClubRoleAssignment assignment = new ClubRoleAssignment();
        assignment.setId(81L);
        assignment.setClubId(CLUB);
        assignment.setTargetMemberId(MEMBER);
        assignment.setRoleCode(roleCode);
        assignment.setScopeType(ClubRoleAssignment.SCOPE_CLUB);
        assignment.setScopeId(CLUB);
        assignment.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        return assignment;
    }

    private GroupCodeServiceImpl groupCodes() {
        return new GroupCodeServiceImpl(activityService, access, orders,
                mock(CoopGroupVerifyMapper.class), qrcodeService(), "test-secret");
    }

    // CU-C-78:签发接口现在要求「有码图」才算成功,所以这些权限类用例必须给出图服务
    // (原来什么都不桩的 mock 返回 null,会被新契约判成失败,掩掉它们真正在测的权限判据)。
    private IQrcodeService qrcodeService() {
        IQrcodeService qrcode = mock(IQrcodeService.class);
        when(qrcode.generateInlineQrcode(any(), anyInt(), anyInt())).thenReturn("https://oss.test/qr/group.png");
        return qrcode;
    }
}
