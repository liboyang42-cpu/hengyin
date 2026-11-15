package com.chengyinhub.business.gamemech;

import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.mapper.MemberCapabilityMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUserPermissionService;
import com.chengyinhub.business.service.impl.RoleServiceImpl;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** G2 / GAM-12: player defaults stay as today unless the owner turns the unlock on AND the creator is verified. */
class VerifiedCreatorUnlockTest {
    private RoleServiceImpl roles;
    private ISysConfigService config;
    private MemberCapabilityMapper caps;

    @BeforeEach
    void setUp() {
        roles = new RoleServiceImpl();
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember m = new UmsMember();
        m.setId(7L); m.setRole("player"); m.setStatus(1L); m.setDelFlag(0);
        when(members.selectRolePermissionSubject(7L)).thenReturn(m);
        IUserPermissionService perms = mock(IUserPermissionService.class);
        UserPermission player = new UserPermission();
        player.setRole("player"); player.setCanBranch(Boolean.FALSE); player.setCanGameMechanic(Boolean.FALSE);
        when(perms.getByRole("player")).thenReturn(player);
        config = mock(ISysConfigService.class);
        caps = mock(MemberCapabilityMapper.class);
        ReflectionTestUtils.setField(roles, "umsMemberMapper", members);
        ReflectionTestUtils.setField(roles, "userPermissionService", perms);
        ReflectionTestUtils.setField(roles, "memberCapabilityMapper", caps);
        ReflectionTestUtils.setField(roles, "sysConfigService", config);
    }

    private UmsMember subject() { UmsMember m = new UmsMember(); m.setId(7L); return m; }

    private void verified(boolean on) {
        MemberCapability c = new MemberCapability();
        c.setStatus(on ? 1 : 0);
        when(caps.selectByMemberAndCapability(7L, RoleServiceImpl.CAP_VERIFIED_CREATOR)).thenReturn(c);
    }

    @Test
    void defaultConfigKeepsTodaysPlayerDefaults() {
        verified(true);
        when(config.selectConfigByKey(RoleServiceImpl.CONFIG_VERIFIED_UNLOCK)).thenReturn("false");
        UserPermission p = roles.getPermission(subject());
        assertEquals(Boolean.FALSE, p.getCanBranch());
        assertEquals(Boolean.FALSE, p.getCanGameMechanic());
    }

    @Test
    void unlockNeedsVerification() {
        when(config.selectConfigByKey(RoleServiceImpl.CONFIG_VERIFIED_UNLOCK)).thenReturn("true");
        verified(false);
        assertEquals(Boolean.FALSE, roles.getPermission(subject()).getCanBranch());
        verified(true);
        UserPermission p = roles.getPermission(subject());
        assertEquals(Boolean.TRUE, p.getCanBranch());
        assertEquals(Boolean.TRUE, p.getCanGameMechanic());
    }
}
