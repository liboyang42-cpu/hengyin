package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.service.impl.PlayerRoleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 俱乐部活动角色分配:①轮转口径 = 已分配人数 % 5;②已有角色不重复分;
 * ③并发输家(DuplicateKey)回读赢家写入的角色,一人恒一角色。
 */
class PlayerRoleAssignTest {

    private static final Long ACT = 500L;
    private static final Long MEMBER = 88L;

    private PlayerActivityRoleMapper mapper;
    private PlayerRoleServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(PlayerActivityRoleMapper.class);
        service = new PlayerRoleServiceImpl();
        ReflectionTestUtils.setField(service, "playerActivityRoleMapper", mapper);
    }

    @Test
    void rotatesRolesByAssignedCount() {
        when(mapper.selectByActivityAndMember(eq(ACT), eq(MEMBER))).thenReturn(null);
        when(mapper.countByActivity(ACT)).thenReturn(6); // 6 % 5 = 1 → 第二个角色
        when(mapper.insertPlayerActivityRole(any())).thenReturn(1);

        PlayerActivityRole role = service.assignOrGet(MEMBER, ACT);
        assertEquals("OBSERVER", role.getRoleCode());
        assertEquals("观察员", service.roleName(role.getRoleCode()));
    }

    @Test
    void existingRoleIsReturnedWithoutReassign() {
        PlayerActivityRole existing = new PlayerActivityRole();
        existing.setRoleCode("DECODER");
        when(mapper.selectByActivityAndMember(eq(ACT), eq(MEMBER))).thenReturn(existing);

        assertEquals("DECODER", service.assignOrGet(MEMBER, ACT).getRoleCode());
        verify(mapper, never()).insertPlayerActivityRole(any());
    }

    @Test
    void duplicateKeyLoser_readsBackWinnersRole() {
        when(mapper.selectByActivityAndMember(eq(ACT), eq(MEMBER)))
                .thenReturn(null) // 首查未分配
                .thenReturn(roleOf("NAVIGATOR")); // 撞唯一键后回读赢家
        when(mapper.countByActivity(ACT)).thenReturn(3);
        when(mapper.insertPlayerActivityRole(any())).thenThrow(new DuplicateKeyException("dup"));

        assertEquals("NAVIGATOR", service.assignOrGet(MEMBER, ACT).getRoleCode());
    }

    @Test
    void invalidIds_returnNull() {
        assertNull(service.assignOrGet(null, ACT));
        assertNull(service.assignOrGet(MEMBER, 0L));
    }

    private static PlayerActivityRole roleOf(String code) {
        PlayerActivityRole r = new PlayerActivityRole();
        r.setRoleCode(code);
        return r;
    }
}
