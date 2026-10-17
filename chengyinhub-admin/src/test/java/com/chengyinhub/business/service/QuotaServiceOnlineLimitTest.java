package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.QuotaServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1 上架数额度后端强制单测。
 *
 * 规则:玩家「同时在架公开主题」不得超过角色 maxThemes(=2);草稿/待审(user_status=0)与 selfPlay 不占,
 * 下架即释放;maxThemes=null(俱乐部/商家)不限。创建阶段不再按创建总数拦截(草稿无限)。
 */
class QuotaServiceOnlineLimitTest {

    private IUmsMemberService umsMemberService;
    private IRoleService roleService;
    private ICmsTopicService cmsTopicService;
    private ICmsActivityService cmsActivityService;
    private ISmsCouponService smsCouponService;
    private QuotaServiceImpl svc;

    @Test
    void topicNodesAreUnlimitedEvenWithLegacyTwoNodePermission() {
        UmsMember member = new UmsMember();
        UserPermission permission = new UserPermission();
        permission.setMaxNodesPerTheme(2);
        when(umsMemberService.selectUmsMemberById(42L)).thenReturn(member);
        when(roleService.getPermission(member)).thenReturn(permission);
        assertNull(svc.checkNodesPerTheme(42L, 11));
        assertNull(svc.checkNodesPerTheme(42L, 10000));
        assertNotNull(svc.checkNodesPerTheme(null, 11));
        assertNotNull(svc.checkNodesPerTheme(43L, 11));
    }

    @Test
    void permissionProjectionReportsUnlimitedFromPersistedRoleWithoutMutatingPolicy() {
        com.chengyinhub.business.service.impl.RoleServiceImpl roles =
                new com.chengyinhub.business.service.impl.RoleServiceImpl();
        IUserPermissionService permissions = mock(IUserPermissionService.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        ReflectionTestUtils.setField(roles, "userPermissionService", permissions);
        ReflectionTestUtils.setField(roles, "umsMemberMapper", members);
        UserPermission old = new UserPermission();
        old.setRole("player");
        old.setMaxNodesPerTheme(2);
        old.setMaxThemes(3);
        when(permissions.getByRole("player")).thenReturn(old);
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setRole("merchant");
        when(members.selectRolePermissionSubject(42L)).thenReturn(persistedRole("player"));
        UserPermission projected = roles.getPermission(member);
        assertNotSame(old, projected);
        assertNull(projected.getMaxNodesPerTheme());
        assertEquals(Integer.valueOf(3), projected.getMaxThemes());
        assertEquals(Integer.valueOf(2), old.getMaxNodesPerTheme());
        assertEquals(Integer.valueOf(3), old.getMaxThemes());
        verify(members).selectRolePermissionSubject(42L);
        verify(permissions).getByRole("player");
        verify(permissions, never()).getByRole("merchant");
    }

    @Test
    void staleClientRoleCannotOverridePersistedRole() {
        com.chengyinhub.business.service.impl.RoleServiceImpl roles =
                new com.chengyinhub.business.service.impl.RoleServiceImpl();
        IUserPermissionService permissions = mock(IUserPermissionService.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        ReflectionTestUtils.setField(roles, "userPermissionService", permissions);
        ReflectionTestUtils.setField(roles, "umsMemberMapper", members);
        UserPermission playerPolicy = new UserPermission();
        playerPolicy.setRole("player");
        playerPolicy.setMaxThemes(3);
        UserPermission merchantPolicy = new UserPermission();
        merchantPolicy.setRole("merchant");
        merchantPolicy.setMaxThemes(50);
        when(permissions.getByRole("player")).thenReturn(playerPolicy);
        when(permissions.getByRole("merchant")).thenReturn(merchantPolicy);
        when(members.selectRolePermissionSubject(42L))
                .thenReturn(persistedRole("player"), persistedRole("merchant"));
        UmsMember staleClient = new UmsMember();
        staleClient.setId(42L);
        staleClient.setRole("merchant");

        UserPermission actualPlayer = roles.getPermission(staleClient);
        assertEquals("player", actualPlayer.getRole());
        assertEquals(Integer.valueOf(3), actualPlayer.getMaxThemes());
        staleClient.setRole("player");
        UserPermission actualMerchant = roles.getPermission(staleClient);
        assertEquals("merchant", actualMerchant.getRole());
        assertEquals(Integer.valueOf(50), actualMerchant.getMaxThemes());
        verify(permissions).getByRole("player");
        verify(permissions).getByRole("merchant");
    }

    private static UmsMember persistedRole(String role) {
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setRole(role);
        return member;
    }

    @BeforeEach
    void setUp() {
        umsMemberService = mock(IUmsMemberService.class);
        roleService = mock(IRoleService.class);
        cmsTopicService = mock(ICmsTopicService.class);
        cmsActivityService = mock(ICmsActivityService.class);
        smsCouponService = mock(ISmsCouponService.class);

        svc = new QuotaServiceImpl();
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "roleService", roleService);
        ReflectionTestUtils.setField(svc, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(svc, "cmsActivityService", cmsActivityService);
        ReflectionTestUtils.setField(svc, "smsCouponService", smsCouponService);
    }

    private void stubPlayer(Integer maxThemes) {
        stubPlayer(maxThemes, null, null);
    }

    private void stubPlayer(Integer maxThemes, Integer maxEvents, Integer maxCoupons) {
        UmsMember m = new UmsMember();
        m.setId(100L);
        when(umsMemberService.selectUmsMemberById(100L)).thenReturn(m);
        UserPermission perm = new UserPermission();
        perm.setRole("player");
        perm.setCanCreateTheme(true);
        perm.setCanCreateEvent(true);
        perm.setCanPublishCoupon(true);
        perm.setMaxThemes(maxThemes);
        perm.setMaxEvents(maxEvents);
        perm.setMaxCoupons(maxCoupons);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(perm);
    }

    @Test
    void online_belowLimit_pass() {
        stubPlayer(2);
        when(cmsTopicService.selectOnlineThemeNumByMemberId(eq(100L), any())).thenReturn(1);
        assertNull(svc.checkThemeOnline(100L, null), "在架1<上限2应放行");
    }

    @Test
    void online_atLimit_blocked() {
        stubPlayer(2);
        when(cmsTopicService.selectOnlineThemeNumByMemberId(eq(100L), any())).thenReturn(2);
        String err = svc.checkThemeOnline(100L, null);
        assertNotNull(err, "在架2>=上限2应拦截");
        assertTrue(err.contains("上限"), "提示应含上限文案: " + err);
    }

    @Test
    void online_nullMax_unlimited() {
        stubPlayer(null); // 俱乐部/商家:maxThemes=null
        when(cmsTopicService.selectOnlineThemeNumByMemberId(eq(100L), any())).thenReturn(99);
        assertNull(svc.checkThemeOnline(100L, null), "maxThemes=null 视为不限,应放行");
    }

    @Test
    void online_notLoggedIn_blocked() {
        assertNotNull(svc.checkThemeOnline(0L, null), "未登录应拦截");
        assertNotNull(svc.checkThemeOnline(null, null), "memberId=null 应拦截");
    }

    @Test
    void create_draftsUnlimited_notBlockedByTotal() {
        stubPlayer(2);
        // 即使创建总数已远超上限,创建阶段也放行(草稿无限);拦截只在上架时刻
        when(cmsTopicService.selectCmsTopicNumByMemberId(anyLong())).thenReturn(999);
        assertNull(svc.checkCreate(100L, IQuotaService.TYPE_THEME), "创建阶段不按总数拦截(草稿无限)");
    }

    // ---- 竞态安全 guard:锁会员 → 复查在架数 → 写库,三步原子 ----

    @Test
    void guard_belowLimit_locksThenRunsWrite() {
        stubPlayer(2);
        when(cmsTopicService.selectOnlineThemeNumByMemberId(eq(100L), any())).thenReturn(1);
        AtomicBoolean ran = new AtomicBoolean(false);
        String r = svc.runWithThemeOnlineGuard(100L, null, () -> { ran.set(true); return "ok"; });
        assertEquals("ok", r, "在架1<上限2应执行并返回写操作结果");
        assertTrue(ran.get(), "校验通过应执行写操作");
        verify(umsMemberService).lockMemberById(100L); // 写前必先加会员行锁
    }

    @Test
    void guard_atLimit_throwsAndSkipsWrite() {
        stubPlayer(2);
        when(cmsTopicService.selectOnlineThemeNumByMemberId(eq(100L), any())).thenReturn(2);
        AtomicBoolean ran = new AtomicBoolean(false);
        // 负控:达上限时必须抛错且绝不执行写操作,否则并发下会真实超发(资损)
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.runWithThemeOnlineGuard(100L, null, () -> { ran.set(true); return "ok"; }));
        assertTrue(ex.getMessage().contains("上限"), "异常应含上限文案: " + ex.getMessage());
        assertFalse(ran.get(), "达上限必须跳过写操作(防超发)");
        verify(umsMemberService).lockMemberById(100L); // 校验前也已加锁
    }

    // ---- 创建 guard(活动/优惠券):锁会员 → 复查 checkCreate → 写库,三步原子 ----

    @Test
    void createGuard_event_belowLimit_runsWrite() {
        stubPlayer(null, 3, null); // maxEvents=3
        when(cmsActivityService.selectCmsActivityNumByMemberId(anyLong())).thenReturn(2);
        AtomicBoolean ran = new AtomicBoolean(false);
        Long id = svc.runWithCreateGuard(100L, IQuotaService.TYPE_EVENT, () -> { ran.set(true); return 9L; });
        assertEquals(9L, id, "活动2<上限3应执行并返回写结果");
        assertTrue(ran.get());
        verify(umsMemberService).lockMemberById(100L);
    }

    @Test
    void createGuard_event_atLimit_throwsAndSkipsWrite() {
        stubPlayer(null, 3, null);
        when(cmsActivityService.selectCmsActivityNumByMemberId(anyLong())).thenReturn(3);
        AtomicBoolean ran = new AtomicBoolean(false);
        // 负控:活动达上限必须抛错且不写,否则并发创建会真实超发
        assertThrows(ServiceException.class,
                () -> svc.runWithCreateGuard(100L, IQuotaService.TYPE_EVENT, () -> { ran.set(true); return 9L; }));
        assertFalse(ran.get(), "活动达上限必须跳过写操作");
        verify(umsMemberService).lockMemberById(100L);
    }

    @Test
    void createGuard_coupon_atLimit_throwsAndSkipsWrite() {
        stubPlayer(null, null, 2); // maxCoupons=2
        when(smsCouponService.selectSmsCouponNumByMemberId(anyLong())).thenReturn(2);
        AtomicBoolean ran = new AtomicBoolean(false);
        // 负控:优惠券达上限必须抛错且不写
        assertThrows(ServiceException.class,
                () -> svc.runWithCreateGuard(100L, IQuotaService.TYPE_COUPON, () -> { ran.set(true); return 1; }));
        assertFalse(ran.get(), "优惠券达上限必须跳过写操作");
        verify(umsMemberService).lockMemberById(100L);
    }
}
