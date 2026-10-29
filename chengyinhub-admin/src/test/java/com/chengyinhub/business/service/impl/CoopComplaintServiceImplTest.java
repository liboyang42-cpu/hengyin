package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopComplaint;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客诉工单责任链:受理 → 先行垫付(平台→玩家)→ 过错扣划 + 关闭(解冻)。
 */
class CoopComplaintServiceImplTest {

    private CoopComplaintMapper mapper;
    private IUmsMemberService umsMemberService;
    private IUmsMemberBalanceDetailService balanceDetailService;
    private IPlayerWithdrawalCooldownService cooldownService;
    private CoopComplaintServiceImpl svc;
    private com.chengyinhub.business.service.support.ComplaintCloseReporterNotice notices;
    private com.chengyinhub.business.service.support.CommittedOfficialNoticeService durableNotices;

    private final com.chengyinhub.business.service.support.SettlementDebtLedger debtLedger = org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class);

    @BeforeEach
    void setUp() {
        mapper = mock(CoopComplaintMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        balanceDetailService = mock(IUmsMemberBalanceDetailService.class);
        svc = new CoopComplaintServiceImpl();
        notices = mock(com.chengyinhub.business.service.support.ComplaintCloseReporterNotice.class);
        ReflectionTestUtils.setField(svc, "complaintCloseReporterNotice", notices);
        durableNotices = mock(com.chengyinhub.business.service.support.CommittedOfficialNoticeService.class);
        ReflectionTestUtils.setField(svc, "committedOfficialNoticeService", durableNotices);
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", debtLedger);
        ReflectionTestUtils.setField(svc, "coopComplaintMapper", mapper);
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailService", balanceDetailService);
        cooldownService = mock(IPlayerWithdrawalCooldownService.class);
        ReflectionTestUtils.setField(svc, "playerWithdrawalCooldownService", cooldownService);
        when(mapper.claimOpenForClose(eq(1L), any(), any())).thenReturn(1);
        // 明细插入回填 generated id —— 冷静期就是靠它挂到「刚写的那一条」上的。
        when(balanceDetailService.insertUmsMemberBalanceDetail(any())).thenAnswer(invocation -> {
            UmsMemberBalanceDetail detail = invocation.getArgument(0);
            detail.setId(4242L);
            return 1;
        });
    }

    private CoopComplaint open(int status) {
        CoopComplaint c = new CoopComplaint();
        c.setId(1L); c.setTopicId(700L); c.setReporterMemberId(100L);
        c.setRespondentMemberId(200L); c.setRespondentType("club"); c.setStatus(status);
        return c;
    }

    private void member(Long id, String balance) {
        UmsMember m = new UmsMember(); m.setId(id); m.setBalance(new BigDecimal(balance));
        when(umsMemberService.selectUmsMemberById(id)).thenReturn(m);
    }

    @Test
    void create_setsAcceptedStatus() {
        CoopComplaint c = new CoopComplaint();
        c.setTopicId(700L); c.setReporterMemberId(100L);
        svc.create(c);
        assertEquals(Integer.valueOf(CoopComplaint.STATUS_ACCEPTED), c.getStatus(), "受理即 ACCEPTED(冻结)");
        verify(mapper).insertCoopComplaint(c);
    }

    @Test
    void advance_paysReporter_andMarksAdvanced() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_ACCEPTED));
        when(mapper.claimAcceptedForAdvance(eq(1L), eq(new BigDecimal("50")), eq(999L), any())).thenReturn(1);
        member(100L, "0");

        svc.advance(1L, new BigDecimal("50"), 999L);

        verify(umsMemberService).addBalance(eq(100L), eq(new BigDecimal("50"))); // 平台垫付给投诉玩家
        verify(mapper).claimAcceptedForAdvance(eq(1L), eq(new BigDecimal("50")), eq(999L), any());
        // 资金状态迁移不得再走无旧状态条件的通用 UPDATE —— 那正是双付的成因。
        verify(mapper, never()).updateCoopComplaint(any());
        verify(durableNotices).enqueueOfficial(eq("complaint-advance:1"), eq(100L),
                eq("投诉处理有进展"), eq("工单#1已先行垫付 ¥50，请在投诉记录查看处理进度。"));
        org.mockito.Mockito.verifyNoInteractions(notices);
    }

    /** ★并发双付:第二个 advance 抢不到 CAS,必须在任何余额变动之前出局。 */
    @Test
    void advance_losingTheClaimCas_movesNoMoneyAtAll() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_ACCEPTED));
        when(mapper.claimAcceptedForAdvance(eq(1L), any(), eq(999L), any())).thenReturn(0);
        member(100L, "0");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.advance(1L, new BigDecimal("50"), 999L));

        assertEquals("工单已垫付或处理中,勿重复垫付", error.getMessage());
        verify(umsMemberService, never()).addBalance(any(), any());
        verify(balanceDetailService, never()).insertUmsMemberBalanceDetail(any());
        verify(cooldownService, never()).registerFromPositiveComplaintDetail(any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(notices, durableNotices);
    }

    /** 冷静期必须挂在刚插入那条明细的 generated id 上,不许按 complaintId 二次模糊查。 */
    @Test
    void advance_registersCooldownOnTheGeneratedDetailId() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_ACCEPTED));
        when(mapper.claimAcceptedForAdvance(eq(1L), any(), eq(999L), any())).thenReturn(1);
        member(100L, "0");

        svc.advance(1L, new BigDecimal("50"), 999L);

        verify(cooldownService).registerFromPositiveComplaintDetail(4242L, 100L, 1L, new BigDecimal("50"));
        ArgumentCaptor<UmsMemberBalanceDetail> cap = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(balanceDetailService).insertUmsMemberBalanceDetail(cap.capture());
        assertEquals(Long.valueOf(9L), cap.getValue().getEventType(), "只跟踪投诉正向补偿这一类事件");
        assertEquals(Long.valueOf(1L), cap.getValue().getChangeType(), "只跟踪加钱那一侧");
    }

    /** 冷静期写失败必须把整笔垫付一起炸掉,不许「先补偿、事务外补冷静期」。 */
    @Test
    void advance_propagatesCooldownFailureSoTheWholeAdvanceRollsBack() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_ACCEPTED));
        when(mapper.claimAcceptedForAdvance(eq(1L), any(), eq(999L), any())).thenReturn(1);
        member(100L, "0");
        org.mockito.Mockito.doThrow(new IllegalStateException("PLAYER_WITHDRAWAL_COOLDOWN_WRITE_FAILED"))
                .when(cooldownService).registerFromPositiveComplaintDetail(any(), any(), any(), any());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.advance(1L, new BigDecimal("50"), 999L));

        assertEquals("PLAYER_WITHDRAWAL_COOLDOWN_WRITE_FAILED", error.getMessage());
    }

    @Test
    void advance_onAlreadyAdvanced_throws_noDoublePay() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_ADVANCED));
        assertThrows(IllegalStateException.class, () -> svc.advance(1L, new BigDecimal("50"), 999L),
                "已垫付工单再 advance 应拒绝,防重复给玩家打款(资损)");
        verify(umsMemberService, never()).addBalance(any(), any());
    }

    @Test
    void close_losingTheClaim_movesNoMoney() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_ACCEPTED));
        when(mapper.claimOpenForClose(eq(1L), eq(CoopComplaint.STATUS_ACCEPTED), any())).thenReturn(0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.close(1L, "club", 200L, 70, BigDecimal.ONE, "n", 999L));
        assertEquals("工单状态已变化，请刷新后重试", error.getMessage());
        verify(umsMemberService, never()).deductBalance(any(), any());
        verify(balanceDetailService, never()).insertUmsMemberBalanceDetail(any());
        verify(mapper, never()).updateCoopComplaint(any());
        org.mockito.Mockito.verifyNoInteractions(notices, durableNotices);
    }

    @Test
    void close_deductsFromRespondent_andCloses() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_ADVANCED));
        member(200L, "80"); // 过错方余额充足

        svc.close(1L, "club", 200L, 70, new BigDecimal("50"), "俱乐部带队失误主责", 999L);

        verify(umsMemberService).deductBalance(eq(200L), eq(new BigDecimal("50"))); // 从过错方扣划
        ArgumentCaptor<CoopComplaint> cap = ArgumentCaptor.forClass(CoopComplaint.class);
        verify(mapper).updateCoopComplaint(cap.capture());
        assertEquals(Integer.valueOf(CoopComplaint.STATUS_CLOSED), cap.getValue().getStatus(), "关闭=解冻");
        assertEquals(Integer.valueOf(70), cap.getValue().getFaultRatio());
        assertEquals(0, new BigDecimal("50").compareTo(cap.getValue().getDeductAmount()));
        verify(notices).submitReporter(eq(1L), eq(100L), eq(999L), any(), any());
        verify(durableNotices).enqueueOfficial(eq("complaint-close-respondent:1"), eq(200L),
                eq("投诉工单处理结果"), eq("工单#1已处理，实际扣划 ¥50。请核对账务及处理说明。"));
    }

    @Test
    void close_insufficientBalance_deductsOnlyAvailable() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_ADVANCED));
        member(200L, "30"); // 余额不足(应扣50)

        svc.close(1L, "club", 200L, 70, new BigDecimal("50"), "n", 999L);

        verify(umsMemberService).deductBalance(eq(200L), eq(new BigDecimal("30"))); // 只扣可扣的30
        ArgumentCaptor<CoopComplaint> cap = ArgumentCaptor.forClass(CoopComplaint.class);
        verify(mapper).updateCoopComplaint(cap.capture());
        assertEquals(0, new BigDecimal("30").compareTo(cap.getValue().getDeductAmount()), "记实扣30");
        // 9-16 拍板第 2 条:差额 20 记欠款,从过错方后续结算抵扣
        verify(debtLedger).record(200L, com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_COMPLAINT, 1L, new BigDecimal("20"));
        verify(durableNotices).enqueueOfficial(eq("complaint-close-respondent:1"), eq(200L),
                eq("投诉工单处理结果"), eq("工单#1已处理，实际扣划 ¥30。请核对账务及处理说明。"));
    }

    @Test
    void mineUsesOnlyTheAuthenticatedReporterAndRejectsMissingIdentity() {
        when(mapper.selectMine(100L)).thenReturn(java.util.Collections.emptyList());
        assertEquals(java.util.Collections.emptyList(), svc.listMine(100L));
        verify(mapper).selectMine(100L);
        assertThrows(IllegalArgumentException.class, () -> svc.listMine(null));
        assertThrows(IllegalArgumentException.class, () -> svc.listMine(0L));
        verify(mapper, never()).selectMine(null);
    }

    @Test
    void advanceOrClose_onClosedTicket_throws() {
        when(mapper.selectCoopComplaintById(1L)).thenReturn(open(CoopComplaint.STATUS_CLOSED));
        assertThrows(IllegalStateException.class, () -> svc.advance(1L, new BigDecimal("10"), 1L));
        assertThrows(IllegalStateException.class, () -> svc.close(1L, "club", 200L, 50, new BigDecimal("10"), "n", 1L));
        verify(umsMemberService, never()).addBalance(any(), any());
        verify(umsMemberService, never()).deductBalance(any(), any());
    }
}
