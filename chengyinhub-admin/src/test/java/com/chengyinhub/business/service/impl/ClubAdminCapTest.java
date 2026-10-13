package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D22:俱乐部管理员至多 2 个(主理人之外额外 2 个)。
 *
 * <p>为什么这条闸值钱:管理员有**核销资格** —— 组织槽有人时由俱乐部(主理人或 role==1 管理员)
 * 扫玩家票,并且他扫出的 scan 行就是商家团口径分账的基数来源。多一个管理员 = 多一个能动钱的人。
 *
 * <p>⚠️ 并发解法(§7-F 拍板 (b) 悲观锁):
 *  · 不能在 controller 里「先 count 再拒」—— 那是读判,两个请求并发都能过 count → 3 个管理员;
 *  · 不能用 confirmWinnerIfPending 那种 CAS —— 它是**单行状态机**,ApiCoopController:768 自己就写着
 *    「挡不住同节点已有别家中标」,跨行计数不适用;
 *  · 不能用条件 UPDATE —— MySQL 不许在 UPDATE 子查询里引用目标表(error 1093),
 *    套 derived table 绕开后子查询变快照读,原子性没了;
 *  · **锁的必须是 club 行(计数的分组键),不是 club_member 行** —— 后者两个不同 memberId 各锁各的,
 *    谁也拦不住谁。
 *
 * ⚠️ 负控已实测(见 D22 提交说明):
 *  - 去掉 countAdminsByClub 判断      ⇒ thirdAdmin_rejected 红
 *  - 去掉 selectClubByIdForUpdate     ⇒ promote_locksClubRowBeforeCounting 红
 *  - 锁在 count 之后才取              ⇒ promote_locksClubRowBeforeCounting 红
 *  - 去掉 @Transactional              ⇒ serviceMethod_isTransactional 红(锁没事务 = 没锁,且它不会自己报错)
 *  - 已是管理员时也走上限闸           ⇒ existingAdmin_idempotent 红
 */
class ClubAdminCapTest {

    private static final Long CLUB = 610L;
    private static final Long MEMBER = 42L;

    private final ClubMemberMapper clubMemberMapper = mock(ClubMemberMapper.class);
    private final ClubMapper clubMapper = mock(ClubMapper.class);
    private final ClubMemberServiceImpl svc = new ClubMemberServiceImpl();

    ClubAdminCapTest() {
        ReflectionTestUtils.setField(svc, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(svc, "clubMapper", clubMapper);
        Club active = new Club();
        active.setId(CLUB);
        when(clubMapper.selectClubByIdForUpdate(CLUB)).thenReturn(active);
    }

    private void givenMember(Integer role) {
        ClubMember cm = new ClubMember();
        cm.setClubId(CLUB);
        cm.setMemberId(MEMBER);
        cm.setRole(role);
        cm.setStatus(1);
        when(clubMemberMapper.selectByClubAndMember(CLUB, MEMBER)).thenReturn(cm);
    }

    /** 已有 0 个管理员 ⇒ 第 1 个放行。 */
    @Test
    void firstAdmin_allowed() {
        givenMember(0);
        when(clubMemberMapper.countAdminsByClub(CLUB)).thenReturn(0);

        assertTrue(svc.setMemberRoleWithAdminCap(CLUB, MEMBER, 1));
        verify(clubMemberMapper).updateClubMember(any());
    }

    /** 已有 1 个 ⇒ 第 2 个放行(上限是 2)。 */
    @Test
    void secondAdmin_allowed() {
        givenMember(0);
        when(clubMemberMapper.countAdminsByClub(CLUB)).thenReturn(1);

        assertTrue(svc.setMemberRoleWithAdminCap(CLUB, MEMBER, 1));
        verify(clubMemberMapper).updateClubMember(any());
    }

    /** ★ 已有 2 个 ⇒ 第 3 个拒,且不得落库。 */
    @Test
    void thirdAdmin_rejected() {
        givenMember(0);
        when(clubMemberMapper.countAdminsByClub(CLUB)).thenReturn(2);

        assertFalse(svc.setMemberRoleWithAdminCap(CLUB, MEMBER, 1));
        verify(clubMemberMapper, never()).updateClubMember(any());
    }

    /**
     * ★ 锁必须在 count **之前**取,且锁的是 club 行。
     * 顺序反了(先 count 再锁)= 两个请求都读到旧 count 才开始排队 = 闸形同虚设。
     */
    @Test
    void promote_locksClubRowBeforeCounting() {
        givenMember(0);
        when(clubMemberMapper.countAdminsByClub(CLUB)).thenReturn(1);

        svc.setMemberRoleWithAdminCap(CLUB, MEMBER, 1);

        InOrder ord = inOrder(clubMapper, clubMemberMapper);
        ord.verify(clubMapper).selectClubByIdForUpdate(CLUB);   // 锁分组键
        ord.verify(clubMemberMapper).countAdminsByClub(CLUB);   // 再数
    }

    /**
     * ★★ 锁只有在事务里才成立 —— 没有 @Transactional 的话,行锁随语句结束就释放,
     * 闸退化回「先 count 再拒」的读判,而且**它不会自己报错**(这正是最危险的假绿形态)。
     * 源码断言是这里唯一能证明它的手段:行为测试看不见事务边界。
     */
    @Test
    void serviceMethod_isTransactional() throws NoSuchMethodException {
        Method m = ClubMemberServiceImpl.class.getMethod(
                "setMemberRoleWithAdminCap", Long.class, Long.class, int.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "setMemberRoleWithAdminCap 必须有 @Transactional —— 否则 for update 的锁立即释放 = 没锁");
        assertEquals(Exception.class, tx.rollbackFor()[0], "与仓库既有写法一致:rollbackFor = Exception");
    }

    /** 已经是管理员 ⇒ 幂等放行,不能因为「已达上限 2」把他自己顶掉。 */
    @Test
    void existingAdmin_idempotent() {
        givenMember(1);
        when(clubMemberMapper.countAdminsByClub(CLUB)).thenReturn(2);

        assertTrue(svc.setMemberRoleWithAdminCap(CLUB, MEMBER, 1), "他本来就是那 2 个之一");
        verify(clubMemberMapper, never()).updateClubMember(any());
    }

    /** 降级不受上限约束，但仍与解散共用 club 行锁，不能在解散后写幽灵角色。 */
    @Test
    void demote_locksClubBeforeWriteButSkipsAdminCount() {
        givenMember(1);

        assertTrue(svc.setMemberRoleWithAdminCap(CLUB, MEMBER, 0));
        InOrder order = inOrder(clubMapper, clubMemberMapper);
        order.verify(clubMapper).selectClubByIdForUpdate(CLUB);
        order.verify(clubMemberMapper).updateClubMember(any());
        verify(clubMemberMapper, never()).countAdminsByClub(any());
    }

    @Test
    void dissolvedClubRejectsRoleWriteBeforeReadingOrUpdatingMember() {
        when(clubMapper.selectClubByIdForUpdate(CLUB)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.setMemberRoleWithAdminCap(CLUB, MEMBER, 0));

        assertEquals("俱乐部不存在或已解散", error.getMessage());
        verify(clubMemberMapper, never()).selectByClubAndMember(any(), any());
        verify(clubMemberMapper, never()).updateClubMember(any());
    }

    /** 成员不存在 ⇒ 不落库(上游 controller 已先报错,这里不重复报)。 */
    @Test
    void memberNotFound_noWrite() {
        when(clubMemberMapper.selectByClubAndMember(CLUB, MEMBER)).thenReturn(null);

        svc.setMemberRoleWithAdminCap(CLUB, MEMBER, 1);

        verify(clubMemberMapper, never()).updateClubMember(any());
        verify(clubMapper).selectClubByIdForUpdate(CLUB);
    }
}
