package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPendingSettlement;
import com.chengyinhub.business.domain.ClubPendingDeposit;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.ClubDissolutionDepositException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 俱乐部解散闸与审批制入会状态机。每道闸都断具体文案，防止相邻闸同型异常假绿。 */
class ClubLifecycleServiceTest {

    private static final Long CLUB_ID = 73L;
    private static final Long OWNER_ID = 9L;
    private static final Long MEMBER_ID = 42L;

    private ClubMapper clubMapper;
    private ClubMemberMapper clubMemberMapper;
    private IImService imService;
    private ClubServiceImpl clubService;
    private ClubMemberServiceImpl memberService;
    private IClubAccessService clubAccessService;
    private IClubRoleAssignmentService roleAssignmentService;

    @BeforeEach
    void setUp() {
        clubMapper = mock(ClubMapper.class);
        clubMemberMapper = mock(ClubMemberMapper.class);
        imService = mock(IImService.class);
        clubAccessService = mock(IClubAccessService.class);
        roleAssignmentService = mock(IClubRoleAssignmentService.class);

        clubService = new ClubServiceImpl();
        ReflectionTestUtils.setField(clubService, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(clubService, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(clubService, "imService", imService);

        memberService = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(memberService, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(memberService, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(memberService, "imService", imService);
        ReflectionTestUtils.setField(memberService, "clubAccessService", clubAccessService);
        ReflectionTestUtils.setField(memberService, "clubParticipationGuard", mock(IClubParticipationGuard.class));
        ReflectionTestUtils.setField(memberService, "clubRoleAssignmentService", roleAssignmentService);
    }

    private Club ownerClub() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(OWNER_ID);
        club.setStatus(1L);
        return club;
    }

    private void givenDissolvableClub() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMapper.countUnsettledRevenueByClub(CLUB_ID)).thenReturn(0);
        when(clubMapper.selectUnpaidSettlementsByClub(CLUB_ID))
                .thenReturn(Collections.<ClubPendingSettlement>emptyList());
        when(clubMapper.countActiveCooperationsWithMissingTopicByClub(CLUB_ID)).thenReturn(0);
        when(clubMapper.countActiveCooperationsByClub(CLUB_ID)).thenReturn(0);
        when(clubMapper.selectPendingDepositsByClub(CLUB_ID))
                .thenReturn(Collections.<ClubPendingDeposit>emptyList());
        when(clubMapper.countOngoingSessionsByClub(CLUB_ID)).thenReturn(0);
        when(clubMapper.countIncompleteRegistrationsByClub(CLUB_ID)).thenReturn(0);
        when(clubMemberMapper.countByClub(CLUB_ID)).thenReturn(0);
        when(clubMapper.dissolveClubById(CLUB_ID)).thenReturn(1);
    }

    @Test
    void dissolve_zeroMembersStillRequiresFirstConfirmation() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, false, false));

        assertEquals("请先确认解散俱乐部及其后果", error.getMessage());
        verify(clubMapper, never()).selectClubByIdForUpdate(any());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_nonOwnerIsRejectedByOwnershipGate() {
        givenDissolvableClub();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> clubService.dissolveClub(CLUB_ID, 10L, true, true));

        assertEquals("无权解散该俱乐部", error.getMessage());
        verify(clubMapper, never()).countUnsettledRevenueByClub(any());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void quitLocksClubBeforeRemovingMembership() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.deleteByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(1);

        assertEquals(1, memberService.quitMember(CLUB_ID, MEMBER_ID));

        InOrder order = inOrder(clubMapper, roleAssignmentService, clubMemberMapper);
        order.verify(clubMapper).selectClubByIdForUpdate(CLUB_ID);
        order.verify(roleAssignmentService).revokeAllForAccessEnd(
                CLUB_ID, MEMBER_ID, MEMBER_ID, "SYSTEM_ACCESS_END", "MEMBER_QUIT_OR_REMOVED");
        order.verify(clubMemberMapper).deleteByClubAndMember(CLUB_ID, MEMBER_ID);
        verify(imService).leaveClubGroup(CLUB_ID, MEMBER_ID);
    }

    @Test
    void managerRemovalRecordsTheRealActorInsteadOfTheRemovedMember() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.deleteByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(1);

        assertEquals(1, memberService.quitMemberAndLeaveGroup(
                CLUB_ID, MEMBER_ID, OWNER_ID, ClubRoleCode.CLUB_OWNER, "MEMBER_REMOVED"));

        verify(roleAssignmentService).revokeAllForAccessEnd(
                CLUB_ID, MEMBER_ID, OWNER_ID, ClubRoleCode.CLUB_OWNER, "MEMBER_REMOVED");
    }

    @Test
    void dissolve_unsettledRevenueGate_namesExactRiskAndDoesNotDelete() {
        givenDissolvableClub();
        when(clubMapper.countUnsettledRevenueByClub(CLUB_ID)).thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, true, true));

        assertEquals("俱乐部还有 1 个主题未结算，请先到俱乐部项目完成结算", error.getMessage());
        verify(clubMapper, never()).selectUnpaidSettlementsByClub(any());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_unpaidPayableGate_namesExactRiskAndDoesNotDelete() {
        givenDissolvableClub();
        when(clubMapper.selectUnpaidSettlementsByClub(CLUB_ID)).thenReturn(Arrays.asList(
                pendingSettlement(51L, "incoming", "12.50"),
                pendingSettlement(52L, "outgoing", "8.00")));

        com.chengyinhub.business.service.ClubDissolutionSettlementException error = assertThrows(
                com.chengyinhub.business.service.ClubDissolutionSettlementException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, true, true));

        assertEquals("俱乐部还有 2 笔未打款结算（待收 1 笔、待付 1 笔），请先逐笔处理", error.getMessage());
        assertEquals(51L, error.getSettlements().get(0).getId());
        assertEquals("incoming", error.getSettlements().get(0).getDirection());
        assertEquals(52L, error.getSettlements().get(1).getId());
        assertEquals("outgoing", error.getSettlements().get(1).getDirection());
        verify(clubMapper, never()).countOngoingSessionsByClub(any());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_nullAmountSettlementFailsClosedAndNamesUnknownDirection() {
        givenDissolvableClub();
        when(clubMapper.selectUnpaidSettlementsByClub(CLUB_ID))
                .thenReturn(Collections.singletonList(pendingSettlement(53L, "unknown", null)));

        com.chengyinhub.business.service.ClubDissolutionSettlementException error = assertThrows(
                com.chengyinhub.business.service.ClubDissolutionSettlementException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, true, true));

        assertEquals("俱乐部还有 1 笔未打款结算（待收 0 笔、待付 0 笔、待确认 1 笔），请先逐笔处理",
                error.getMessage());
        assertEquals(53L, error.getSettlements().get(0).getId(), "NULL 金额不能按 0 放行");
        verify(clubMapper, never()).countActiveCooperationsByClub(any());
    }

    @Test
    void dissolve_ongoingSessionGate_namesExactRiskAndDoesNotDelete() {
        givenDissolvableClub();
        when(clubMapper.countOngoingSessionsByClub(CLUB_ID)).thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, true, true));

        assertEquals("俱乐部还有 1 个未结束场次，请先完成或取消场次", error.getMessage());
        verify(clubMapper, never()).countIncompleteRegistrationsByClub(any());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_activeCooperationGatePreventsOrphanedSettlementOwner() {
        givenDissolvableClub();
        when(clubMapper.countActiveCooperationsByClub(CLUB_ID)).thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, true, true));

        assertEquals("俱乐部还有 1 个合作生效中，请先到我的合作取消或完成结算", error.getMessage());
        verify(clubMapper, never()).countOngoingSessionsByClub(any());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_pendingDepositGateNamesCountAndSpecificInvites() {
        givenDissolvableClub();
        when(clubMapper.selectPendingDepositsByClub(CLUB_ID)).thenReturn(Arrays.asList(
                pendingDeposit(81L, 1), pendingDeposit(82L, 2)));

        ClubDissolutionDepositException error = assertThrows(ClubDissolutionDepositException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, true, true));

        assertEquals("俱乐部还有 2 笔合作保证金待处理，请先到合作管理逐笔处理", error.getMessage());
        assertEquals(81L, error.getDeposits().get(0).getId());
        assertEquals(Integer.valueOf(1), error.getDeposits().get(0).getDepositStatus());
        assertEquals(82L, error.getDeposits().get(1).getId(),
                "保证金闸必须指出具体邀约，前端才能给可点的逐笔入口");
        verify(clubMapper, never()).countOngoingSessionsByClub(any());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_incompleteRegistrationGate_namesExactRiskAndDoesNotDelete() {
        givenDissolvableClub();
        when(clubMapper.countIncompleteRegistrationsByClub(CLUB_ID)).thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, true, true));

        assertEquals("俱乐部还有 1 笔未完成报名，请先处理报名或退款", error.getMessage());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_membersRequireDedicatedSecondConfirmation() {
        givenDissolvableClub();
        when(clubMemberMapper.countByClub(CLUB_ID)).thenReturn(2);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.dissolveClub(CLUB_ID, OWNER_ID, true, false));

        assertEquals("俱乐部仍有成员，请再次确认成员将失去俱乐部与群聊入口", error.getMessage());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_successUsesLifecycleFlagAndClosesGroupInSameTransaction() {
        givenDissolvableClub();

        assertEquals(0, clubService.dissolveClub(CLUB_ID, OWNER_ID, true, false));

        verify(clubMapper).dissolveClubById(CLUB_ID);
        verify(imService).closeClubGroup(CLUB_ID);
    }

    @Test
    void dissolutionBlockerDetailsAreOwnerScopedAndKeepFinancialFacts() {
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(ownerClub());
        when(clubMapper.selectPendingDepositsByClub(CLUB_ID))
                .thenReturn(Collections.singletonList(pendingDeposit(81L, 2)));
        when(clubMapper.selectUnpaidSettlementsByClub(CLUB_ID))
                .thenReturn(Collections.singletonList(pendingSettlement(51L, "incoming", "12.50")));

        com.chengyinhub.business.domain.ClubDissolutionBlockers blockers =
                clubService.selectDissolutionBlockers(CLUB_ID, OWNER_ID);

        assertEquals(81L, blockers.getDeposits().get(0).getId());
        assertEquals(51L, blockers.getSettlements().get(0).getId());
    }

    @Test
    void dissolutionBlockerDetailsRejectNonOwnerBeforeReadingMoney() {
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(ownerClub());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> clubService.selectDissolutionBlockers(CLUB_ID, MEMBER_ID));

        assertEquals("无权查看该俱乐部的解散阻断明细", error.getMessage());
        verify(clubMapper, never()).selectPendingDepositsByClub(any());
        verify(clubMapper, never()).selectUnpaidSettlementsByClub(any());
    }

    @Test
    void platformDeleteCannotBypassUnsettledRevenueGate() {
        givenDissolvableClub();
        when(clubMapper.countUnsettledRevenueByClub(CLUB_ID)).thenReturn(2);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.deleteClubByIds(new Long[] { CLUB_ID }));

        assertEquals("俱乐部还有 2 个主题未结算，请先到俱乐部项目完成结算", error.getMessage());
        verify(clubMapper, never()).deleteClubByIds(any());
        verify(clubMapper, never()).dissolveClubById(any());
    }

    @Test
    void dissolve_serviceMethodIsTransactional() throws Exception {
        Method method = ClubServiceImpl.class.getMethod(
                "dissolveClub", Long.class, Long.class, boolean.class, boolean.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, "解散、关闭群聊与全部闸必须在同一事务");
        assertEquals(Exception.class, transactional.rollbackFor()[0]);
    }

    @Test
    void profileAndJoinPolicyAreOneTransactionAndProfileFailureCannotReportSuccess() throws Exception {
        Club club = ownerClub();
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.updateJoinPolicy(CLUB_ID, 1)).thenReturn(1);
        when(clubMapper.updateClub(club)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.updateOwnedClubProfile(club, OWNER_ID, 1));

        assertEquals("俱乐部资料保存失败，请重试", error.getMessage());
        InOrder writes = inOrder(clubMapper);
        writes.verify(clubMapper).selectClubByIdForUpdate(CLUB_ID);
        writes.verify(clubMapper).updateJoinPolicy(CLUB_ID, 1);
        writes.verify(clubMapper).updateClub(club);
        Method method = ClubServiceImpl.class.getMethod(
                "updateOwnedClubProfile", Club.class, Long.class, Integer.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, "策略与资料写入必须共享事务，后一步失败才能回滚前一步");
        assertEquals(Exception.class, transactional.rollbackFor()[0]);
    }

    @Test
    void profile_joinPolicyProbeFailureRejectsWholeSaveInsteadOfSilentlyGoingPublic() {
        Club club = ownerClub();
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMapper.countJoinPolicyColumn()).thenThrow(new RuntimeException("probe unavailable"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> clubService.updateOwnedClubProfile(club, OWNER_ID, 1));

        assertEquals("加入方式状态暂不可确认，未保存任何修改，请稍后重试", error.getMessage());
        verify(clubMapper, never()).updateJoinPolicy(any(), any());
        verify(clubMapper, never()).updateClub(any());
    }

    private ClubPendingSettlement pendingSettlement(Long id, String direction, String amount)
    {
        ClubPendingSettlement result = new ClubPendingSettlement();
        result.setId(id);
        result.setTopicId(7L);
        result.setDirection(direction);
        result.setAmount(amount == null ? null : new BigDecimal(amount));
        return result;
    }

    private ClubPendingDeposit pendingDeposit(Long id, Integer status)
    {
        ClubPendingDeposit result = new ClubPendingDeposit();
        result.setId(id);
        result.setTopicId(7L);
        result.setDepositStatus(status);
        result.setAmount(new BigDecimal("50.00"));
        return result;
    }

    @Test
    void join_privateClubCreatesPendingRecordWithoutInflatingMemberCount() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.selectJoinPolicy(CLUB_ID)).thenReturn(1);

        String state = memberService.requestJoin(CLUB_ID, MEMBER_ID);

        assertEquals(IClubMemberService.JOIN_STATE_PENDING, state);
        ArgumentCaptor<ClubMember> inserted = ArgumentCaptor.forClass(ClubMember.class);
        verify(clubMemberMapper).insertClubMember(inserted.capture());
        assertEquals(Integer.valueOf(0), inserted.getValue().getStatus(), "待审不能获得正常成员身份");
        verify(clubMapper, never()).updateMemberCount(any(), any(Integer.class));
        InOrder policyAfterLock = inOrder(clubMapper);
        policyAfterLock.verify(clubMapper).selectClubByIdForUpdate(CLUB_ID);
        policyAfterLock.verify(clubMapper).countJoinPolicyColumn();
        policyAfterLock.verify(clubMapper).selectJoinPolicy(CLUB_ID);
    }

    @Test
    void join_privateClubKeepsApplicantMessage() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.selectJoinPolicy(CLUB_ID)).thenReturn(1);

        memberService.requestJoin(CLUB_ID, MEMBER_ID, "  朋友推荐来的，想跟一次夜行  ");

        ArgumentCaptor<ClubMember> inserted = ArgumentCaptor.forClass(ClubMember.class);
        verify(clubMemberMapper).insertClubMember(inserted.capture());
        assertEquals("朋友推荐来的，想跟一次夜行", inserted.getValue().getJoinMessage(), "两头空白要去掉");
    }

    @Test
    void join_publicClubDropsMessageBecauseNobodyEverReadsIt() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.selectJoinPolicy(CLUB_ID)).thenReturn(0);

        memberService.requestJoin(CLUB_ID, MEMBER_ID, "写了也没人看");

        ArgumentCaptor<ClubMember> inserted = ArgumentCaptor.forClass(ClubMember.class);
        verify(clubMemberMapper).insertClubMember(inserted.capture());
        assertNull(inserted.getValue().getJoinMessage(), "公开直进当场就是成员,不该留一条没有读者的留言");
    }

    @Test
    void join_blankMessageIsStoredAsNullNotEmptyString() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.selectJoinPolicy(CLUB_ID)).thenReturn(1);

        memberService.requestJoin(CLUB_ID, MEMBER_ID, "   ");

        ArgumentCaptor<ClubMember> inserted = ArgumentCaptor.forClass(ClubMember.class);
        verify(clubMemberMapper).insertClubMember(inserted.capture());
        assertNull(inserted.getValue().getJoinMessage(), "只有空格 = 没写,空串会在名册上渲染出一对空引号");
    }

    @Test
    void join_reapplyOverwritesThePreviousMessage() {
        Club club = ownerClub();
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        ClubMember rejected = new ClubMember();
        rejected.setId(3301L);
        rejected.setClubId(CLUB_ID);
        rejected.setMemberId(MEMBER_ID);
        rejected.setStatus(2);
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(rejected);
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.selectJoinPolicy(CLUB_ID)).thenReturn(1);

        memberService.requestJoin(CLUB_ID, MEMBER_ID, "这次换个说法");

        // 上次被拒时写的那句必须被盖掉,不然主理人拿旧留言判断新申请
        verify(clubMemberMapper).updateJoinMessage(3301L, "这次换个说法");
        verify(clubMemberMapper, never()).insertClubMember(any());
    }

    @Test
    void join_publicClubActivatesImmediatelyAndCountsOnce() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.selectJoinPolicy(CLUB_ID)).thenReturn(0);

        assertEquals(IClubMemberService.JOIN_STATE_JOINED,
                memberService.requestJoin(CLUB_ID, MEMBER_ID));

        verify(clubMapper).updateMemberCount(CLUB_ID, 1);
    }

    @Test
    void join_missingPolicyColumnProbeFallsBackToExistingPublicBehavior() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);
        when(clubMapper.countJoinPolicyColumn()).thenReturn(0);

        assertEquals(IClubMemberService.JOIN_STATE_JOINED,
                memberService.requestJoin(CLUB_ID, MEMBER_ID));

        verify(clubMapper, never()).selectJoinPolicy(any());
        verify(clubMapper).updateMemberCount(CLUB_ID, 1);
    }

    @Test
    void join_policyProbeFailureCannotFailOpenIntoMembership() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);
        when(clubMapper.countJoinPolicyColumn()).thenThrow(new RuntimeException("probe unavailable"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> memberService.requestJoin(CLUB_ID, MEMBER_ID));

        assertEquals("入会服务暂不可用，请稍后重试", error.getMessage());
        verify(clubMemberMapper, never()).insertClubMember(any());
        verify(clubMapper, never()).updateMemberCount(any(), any(Integer.class));
    }

    @Test
    void join_policyReadFailureCannotFailOpenIntoMembership() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);
        when(clubMapper.countJoinPolicyColumn()).thenReturn(1);
        when(clubMapper.selectJoinPolicy(CLUB_ID)).thenThrow(new RuntimeException("read unavailable"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> memberService.requestJoin(CLUB_ID, MEMBER_ID));

        assertEquals("入会服务暂不可用，请稍后重试", error.getMessage());
        verify(clubMemberMapper, never()).insertClubMember(any());
        verify(clubMapper, never()).updateMemberCount(any(), any(Integer.class));
    }

    @Test
    void approve_pendingRequestActivatesAndCountsExactlyOnce() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        ClubMember pending = new ClubMember();
        pending.setId(88L);
        pending.setClubId(CLUB_ID);
        pending.setMemberId(MEMBER_ID);
        pending.setStatus(0);
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(pending);

        memberService.reviewJoinRequest(CLUB_ID, MEMBER_ID, OWNER_ID, true);

        verify(clubMemberMapper).updateMembershipState(eq(88L), eq(1), eq(0), any(Date.class));
        verify(clubMapper).updateMemberCount(CLUB_ID, 1);
    }

    @Test
    void reject_pendingRequestDoesNotGrantMembershipOrCount() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        ClubMember pending = new ClubMember();
        pending.setId(88L);
        pending.setStatus(0);
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(pending);

        memberService.reviewJoinRequest(CLUB_ID, MEMBER_ID, OWNER_ID, false);

        verify(clubMemberMapper).updateMembershipState(88L, 2, 0, null);
        verify(clubMapper, never()).updateMemberCount(any(), any(Integer.class));
    }

    @Test
    void reviewRequest_outsiderGetsNoPermission() {
        // 2026-08-26:审批已放开给 role=1 管理员(管人类)。10L 既不是主理人、
        // 也没有在册管理员记录 —— 仍然一点权限都不该有。
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> memberService.reviewJoinRequest(CLUB_ID, MEMBER_ID, 10L, true));

        assertEquals("只有主理人或管理员可审批入会申请", error.getMessage());
        verify(clubMemberMapper, never()).updateMembershipState(any(), any(), any(), any());
    }

    @Test
    void activeLegacyAdminCanApprovePendingRequest() {
        Long adminId = 10L;
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        ClubMember admin = new ClubMember();
        admin.setClubId(CLUB_ID);
        admin.setMemberId(adminId);
        admin.setRole(1);
        admin.setStatus(1);
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, adminId)).thenReturn(admin);
        ClubMember pending = new ClubMember();
        pending.setId(88L);
        pending.setClubId(CLUB_ID);
        pending.setMemberId(MEMBER_ID);
        pending.setStatus(0);
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(pending);

        memberService.reviewJoinRequest(CLUB_ID, MEMBER_ID, adminId, true);

        verify(clubMemberMapper).updateMembershipState(eq(88L), eq(1), eq(0), any(Date.class));
        verify(clubMapper).updateMemberCount(CLUB_ID, 1);
    }

    @Test
    void coOwnerWithMemberManageCanApprovePendingRequest() {
        Long coOwnerId = 10L;
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(ownerClub());
        when(clubAccessService.resolve(coOwnerId, CLUB_ID, null)).thenReturn(
                new ClubAccessContext(coOwnerId, CLUB_ID, OWNER_ID, null,
                        new java.util.LinkedHashSet<>(Arrays.asList(
                                ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_CO_OWNER))));
        ClubMember pending = new ClubMember();
        pending.setId(88L);
        pending.setClubId(CLUB_ID);
        pending.setMemberId(MEMBER_ID);
        pending.setStatus(0);
        when(clubMemberMapper.selectAnyByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(pending);

        memberService.reviewJoinRequest(CLUB_ID, MEMBER_ID, coOwnerId, true);

        verify(clubMemberMapper).updateMembershipState(eq(88L), eq(1), eq(0), any(Date.class));
        verify(clubMapper).updateMemberCount(CLUB_ID, 1);
    }

    @Test
    void coOwnerWithMemberManageCanListPendingRequests() {
        Long coOwnerId = 10L;
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(ownerClub());
        when(clubAccessService.resolve(coOwnerId, CLUB_ID, null)).thenReturn(
                new ClubAccessContext(coOwnerId, CLUB_ID, OWNER_ID, null,
                        new java.util.LinkedHashSet<>(Arrays.asList(
                                ClubRoleCode.CLUB_MEMBER, ClubRoleCode.CLUB_CO_OWNER))));
        ClubMember pending = new ClubMember();
        pending.setClubId(CLUB_ID);
        pending.setMemberId(MEMBER_ID);
        pending.setStatus(0);
        when(clubMemberMapper.selectPendingByClub(CLUB_ID)).thenReturn(Collections.singletonList(pending));

        assertEquals(1, memberService.selectPendingJoinRequests(CLUB_ID, coOwnerId).size());
    }

    @Test
    void missingJoinPolicyColumnFallsBackToPublicWithoutTouchingMissingColumn() {
        when(clubMapper.countJoinPolicyColumn()).thenReturn(0);

        assertEquals(0, clubService.selectJoinPolicy(CLUB_ID));
        assertTrue(!clubService.isJoinPolicySupported());
        verify(clubMapper, never()).selectJoinPolicy(any());
    }
}
