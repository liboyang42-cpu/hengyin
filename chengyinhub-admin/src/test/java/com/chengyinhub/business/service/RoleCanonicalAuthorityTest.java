package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoleCanonicalAuthorityTest {
    private RoleServiceImpl roles;
    private UmsMemberMapper members;
    private MemberCapabilityMapper projections;
    private IUserPermissionService permissions;
    @BeforeEach void setup() {
        roles = new RoleServiceImpl(); members = mock(UmsMemberMapper.class);
        projections = mock(MemberCapabilityMapper.class); permissions = mock(IUserPermissionService.class);
        ReflectionTestUtils.setField(roles, "umsMemberMapper", members);
        ReflectionTestUtils.setField(roles, "memberCapabilityMapper", projections);
        ReflectionTestUtils.setField(roles, "userPermissionService", permissions);
    }
    private UmsMember member(String role) {
        UmsMember m = new UmsMember(); m.setId(91L); m.setRole(role); m.setStatus(1L); m.setDelFlag(0); m.setUserType(2L); return m;
    }
    @Test void compatibilityTypeAndOldViewCannotGrantMerchantPermission() {
        UmsMember player = member("player");
        when(members.selectRolePermissionSubject(91L)).thenReturn(player);
        UserPermission p = new UserPermission(); p.setRole("player"); p.setCanRedeemCoupon(false); p.setMaxThemes(2);
        when(permissions.getByRole("player")).thenReturn(p);
        UserPermission actual = roles.getPermission(member("merchant"));
        assertFalse(actual.getCanRedeemCoupon()); assertEquals(2, actual.getMaxThemes());
        verify(permissions, never()).getByRole("merchant"); verifyNoInteractions(projections);
        assertEquals("player", roles.resolveRole(player));
    }
    @Test void unknownOrMissingRoleIsDeniedAndNoConfigurationIsRead() {
        for (String role : new String[]{null, "", "admin", "MERCHANT"}) {
            when(members.selectRolePermissionSubject(91L)).thenReturn(member(role));
            assertEquals(403, assertThrows(ServiceException.class, () -> roles.getPermission(member("player"))).getCode());
        }
        verifyNoInteractions(permissions);
    }
    @Test void failedRoleReadNeverFallsBackToCompatibilityOrCapability() {
        when(members.selectRolePermissionSubject(91L)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> roles.getPermission(member("merchant")));
        verifyNoInteractions(permissions, projections);
    }
    @Test void roleWriteUpdatesBothCompatibilityProjectionsWithoutReadingProfiles() {
        when(members.lockActiveMemberById(91L)).thenReturn(91L);
        when(members.updateCanonicalRole(91L, "merchant", 2L)).thenReturn(1);
        MemberCapability old = new MemberCapability(); old.setStatus(1);
        when(projections.selectByMemberAndCapability(91L, MemberCapability.CAP_CLUB_LEADER)).thenReturn(old);
        when(projections.updateStatus(91L, MemberCapability.CAP_CLUB_LEADER, 0, 7L)).thenReturn(1);
        when(projections.insertMemberCapability(any())).thenReturn(1);
        roles.changeRole(91L, "merchant", 7L);
        verify(members).updateCanonicalRole(91L, "merchant", 2L);
        verify(projections).updateStatus(91L, MemberCapability.CAP_CLUB_LEADER, 0, 7L);
        verify(projections).insertMemberCapability(argThat(c -> "merchant".equals(c.getCapability()) && c.getStatus() == 1));
    }
    @Test void failedLockOrWriteStopsBeforeGrantingProjection() {
        assertThrows(ServiceException.class, () -> roles.changeRole(91L, "merchant", 7L));
        verifyNoInteractions(projections);
        when(members.lockActiveMemberById(91L)).thenReturn(91L);
        assertThrows(ServiceException.class, () -> roles.changeRole(91L, "merchant", 7L));
        verifyNoInteractions(projections);
        assertThrows(ServiceException.class, () -> roles.changeRole(91L, "admin", 7L));
    }
    @Test void capabilityReadsCanonicalRoleAndNotLegacyCapabilityRows() {
        CapabilityServiceImpl caps = new CapabilityServiceImpl();
        ReflectionTestUtils.setField(caps, "umsMemberMapper", members);
        ReflectionTestUtils.setField(caps, "memberCapabilityMapper", projections);
        ReflectionTestUtils.setField(caps, "roleService", roles);
        when(members.selectRolePermissionSubject(91L)).thenReturn(member("player"));
        assertFalse(caps.hasCapability(91L, "merchant")); assertFalse(caps.hasCapability(91L, "club_leader"));
        assertTrue(caps.hasCapability(91L, "player")); assertFalse(caps.hasCapability(null, "player"));
        verifyNoInteractions(projections);
    }
    @Test void ordinaryProfileUpdateCannotChangeCanonicalRole() {
        UmsMemberServiceImpl profiles = new UmsMemberServiceImpl();
        ReflectionTestUtils.setField(profiles, "umsMemberMapper", members);
        when(members.selectUmsMemberById(91L)).thenReturn(member("player"));
        assertEquals(403, assertThrows(ServiceException.class,
                () -> profiles.updateUmsMember(member("merchant"))).getCode());
        verify(members, never()).updateUmsMember(any());
    }
    @Test void genericAccountCreationIsPlayerEvenIfInputRequestsMerchant() {
        UmsMemberServiceImpl profiles = new UmsMemberServiceImpl();
        ReflectionTestUtils.setField(profiles, "umsMemberMapper", members);
        when(members.insertUmsMember(any())).thenReturn(1);
        UmsMember input = member("merchant");
        assertEquals(1, profiles.insertUmsMember(input));
        verify(members).insertUmsMember(argThat(m -> "player".equals(m.getRole()) && Long.valueOf(1L).equals(m.getUserType())));
    }

}
