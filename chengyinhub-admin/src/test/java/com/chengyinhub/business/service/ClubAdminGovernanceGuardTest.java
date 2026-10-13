package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubMemberServiceImpl;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-26 管理员(role=1)治理权限放开的闸门测试。
 *
 * 分界线:**管人和执行 → 给;碰钱和合同 → 不给。**
 * 每条放开都配一条负控 —— 只断言「能过」不算验证,得同时证明该拦的真拦得住。
 */
public class ClubAdminGovernanceGuardTest
{
    private static final Long CLUB_ID = 9L;
    private static final Long OWNER_ID = 100L;
    private static final Long ADMIN_ID = 200L;
    private static final Long MEMBER_ID = 300L;
    private static final Long STRANGER_ID = 400L;

    private ClubMapper clubMapper;
    private ClubMemberMapper clubMemberMapper;
    private ClubMemberServiceImpl clubMemberService;
    private ClubServiceImpl clubService;

    private static Club club()
    {
        Club c = new Club();
        c.setId(CLUB_ID);
        c.setMemberId(OWNER_ID);
        c.setName("外滩夜行俱乐部");
        return c;
    }

    private static ClubMember membership(Integer role, Integer status)
    {
        ClubMember cm = new ClubMember();
        cm.setClubId(CLUB_ID);
        cm.setRole(role);
        cm.setStatus(status);
        return cm;
    }

    @BeforeEach
    void setUp()
    {
        clubMapper = mock(ClubMapper.class);
        clubMemberMapper = mock(ClubMemberMapper.class);

        clubMemberService = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(clubMemberService, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(clubMemberService, "clubMemberMapper", clubMemberMapper);

        clubService = new ClubServiceImpl();
        ReflectionTestUtils.setField(clubService, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(clubService, "clubMemberMapper", clubMemberMapper);

        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(club());
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club());
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, ADMIN_ID)).thenReturn(membership(1, 1));
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(membership(0, 1));
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, STRANGER_ID)).thenReturn(null);
    }

    // ---------- canGovernClub:治理判据只有这一份 ----------

    @Test
    void 主理人与在册管理员算治理者()
    {
        assertTrue(clubMemberService.canGovernClub(CLUB_ID, OWNER_ID), "主理人必须算治理者");
        assertTrue(clubMemberService.canGovernClub(CLUB_ID, ADMIN_ID), "role=1 且 status=1 的管理员算治理者");
    }

    @Test
    void 负控_普通成员与陌生人不算治理者()
    {
        assertFalse(clubMemberService.canGovernClub(CLUB_ID, MEMBER_ID), "role=0 的普通成员不得治理");
        assertFalse(clubMemberService.canGovernClub(CLUB_ID, STRANGER_ID), "非成员不得治理");
        assertFalse(clubMemberService.canGovernClub(CLUB_ID, (Long) null), "actorId 为空必须 false,不能因为空值放行");
        assertFalse(clubMemberService.canGovernClub((Long) null, ADMIN_ID), "clubId 为空必须 false");
        assertFalse(clubMemberService.canGovernClub((Club) null, ADMIN_ID), "club 对象为空必须 false");
    }

    @Test
    void 负控_退出后残留的管理员记录不再算治理者()
    {
        // 退出俱乐部只把 status 置非 1,role 可能还留着 1。只看 role 不看 status 就会放行幽灵管理员。
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, ADMIN_ID)).thenReturn(membership(1, 0));
        assertFalse(clubMemberService.canGovernClub(CLUB_ID, ADMIN_ID), "status≠1 的旧管理员记录不得再治理");
    }

    // ---------- 编辑资料:展示字段给管理员,经营配置不给 ----------

    @Test
    void 管理员可以改展示资料()
    {
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.updateClub(any(Club.class))).thenReturn(1);

        clubService.updateOwnedClubProfile(club(), ADMIN_ID, null);

        verify(clubMapper).updateClub(any(Club.class));
    }

    @Test
    void 负控_管理员传加入方式必须报错而不是被静默丢弃()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> clubService.updateOwnedClubProfile(club(), ADMIN_ID, 1));
        assertEquals("只有主理人可以修改加入方式", e.getMessage(),
                "必须明确报错;悄悄忽略会让管理员以为改成功了");
        verify(clubMapper, never()).updateClub(any(Club.class));
        verify(clubMapper, never()).updateJoinPolicy(any(), any());
    }

    @Test
    void 负控_普通成员改资料被拒()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> clubService.updateOwnedClubProfile(club(), MEMBER_ID, null));
        assertEquals("无权修改该俱乐部", e.getMessage());
        verify(clubMapper, never()).updateClub(any(Club.class));
    }

    @Test
    void 负控_锁内被降级的管理员当场失效()
    {
        // 早退判据(controller 的 canGovernClub)通过之后、进锁之前被撤销管理员 ——
        // 闸必须在锁内重算,拿调用方算好的布尔值进来就是假闸。
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, ADMIN_ID)).thenReturn(membership(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> clubService.updateOwnedClubProfile(club(), ADMIN_ID, null));
        verify(clubMapper, never()).updateClub(any(Club.class));
    }

    @Test
    void 主理人仍可改加入方式()
    {
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.updateJoinPolicy(CLUB_ID, 1)).thenReturn(1);
        when(clubMapper.updateClub(any(Club.class))).thenReturn(1);

        assertTrue(clubService.updateOwnedClubProfile(club(), OWNER_ID, 1));

        verify(clubMapper).updateJoinPolicy(CLUB_ID, 1);
    }
}
