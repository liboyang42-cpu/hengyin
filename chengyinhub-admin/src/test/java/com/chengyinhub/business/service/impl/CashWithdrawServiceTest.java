package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5 微信零钱提现单测:成功出金 / 出金失败退余额 / 余额不足 / 未绑微信。mock mapper+member+wechat。
 */
class CashWithdrawServiceTest {

    private MemberCashWithdrawMapper mapper;
    private IUmsMemberService umsMemberService;
    private IWechatPayService wechatPayService;
    private AlarmService alarmService;
    private MerchantReferralBountyService merchantReferralBountyService;
    // R1 起 withdraw 要按来源选同源 appid,新依赖不注入就是 NPE(10/12 个用例连坐)
    private com.chengyinhub.common.config.WxPayConfig wxPayConfig;
    private CashWithdrawServiceImpl svc;

    @BeforeEach
    void setUp() {
        mapper = mock(MemberCashWithdrawMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        wechatPayService = mock(IWechatPayService.class);
        alarmService = mock(AlarmService.class);
        merchantReferralBountyService = mock(MerchantReferralBountyService.class);
        wxPayConfig = mock(com.chengyinhub.common.config.WxPayConfig.class);
        when(wxPayConfig.getAppid()).thenReturn("wx-mp-appid");
        when(wxPayConfig.getAppAppid()).thenReturn("wx-app-appid");
        svc = new CashWithdrawServiceImpl();
        org.springframework.transaction.PlatformTransactionManager transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        ReflectionTestUtils.setField(svc, "transactionManager", transactions);
        when(mapper.updateStatusIfManual(any())).thenReturn(1);
        ReflectionTestUtils.setField(svc, "mapper", mapper);
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(svc, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(svc, "alarmService", alarmService);
        ReflectionTestUtils.setField(svc, "wxPayConfig", wxPayConfig);
        com.chengyinhub.business.service.IPlayerWithdrawalCooldownService cooldownStub = mock(com.chengyinhub.business.service.IPlayerWithdrawalCooldownService.class);
        when(cooldownStub.frozenAmount(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(BigDecimal.ZERO);
        ReflectionTestUtils.setField(svc, "frozenAmountResolver",
                new com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver(merchantReferralBountyService, cooldownStub));
        ReflectionTestUtils.setField(svc, "balanceAdmissionService",
                com.chengyinhub.business.longrun.OWN_C04_21Test.cashFixture(umsMemberService, mapper, (com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver) ReflectionTestUtils.getField(svc, "frozenAmountResolver")));
        when(merchantReferralBountyService.frozenAmount(any())).thenReturn(BigDecimal.ZERO);
        // insert 时给对象分配 id(用于 out_batch_no=CW+id)
        when(mapper.updateMemberCashWithdraw(any())).thenReturn(1);
        when(mapper.updateReceiptIfProcessing(any())).thenReturn(1);
        when(mapper.insertMemberCashWithdraw(any())).thenAnswer(inv -> {
            ((MemberCashWithdraw) inv.getArgument(0)).setId(500L);
            return 1;
        });
        when(mapper.selectById(500L)).thenReturn(new MemberCashWithdraw());
    }

    private UmsMember member(String openid, String balance) {
        UmsMember m = new UmsMember();
        m.setId(900002L); m.setWxOpenId(openid); m.setBalance(new BigDecimal(balance));
        return m;
    }

    private Map<String, String> transferResult(boolean ok, String batchIdOrErr) {
        Map<String, String> r = new HashMap<>();
        r.put("ok", ok ? "true" : "false");
        if (ok) r.put("batchId", batchIdOrErr); else r.put("error", batchIdOrErr);
        return r;
    }

    @Test
    void withdraw_success_marksProcessing_noRefund() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(umsMemberService.deductWithdrawableBalance(900002L, new BigDecimal("30.00"), BigDecimal.ZERO)).thenReturn(true);
        when(wechatPayService.transferToBalance(eq("CW500"), eq("CD500"), eq(new BigDecimal("30.00")), eq("oXAbc"), any(), any()))
                .thenReturn(transferResult(true, "BATCH123"));

        MemberCashWithdraw current = svc.withdraw(900002L, new BigDecimal("30"));
        assertEquals(Integer.valueOf(0), current.getStatus());

        // 扣款一次、不退款、结算单置「处理中」(修复:已受理≠成功,待定时查询确认到账才成功)+回写批次号
        verify(umsMemberService).deductWithdrawableBalance(900002L, new BigDecimal("30.00"), BigDecimal.ZERO);
        verify(umsMemberService, never()).addBalance(any(), any());
        verify(mapper, never()).updateStatusIfProcessing(any());
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateReceiptIfProcessing(cap.capture());
        boolean accepted = cap.getAllValues().stream().anyMatch(v ->
                v.getStatus() == null && "BATCH123".equals(v.getTransferBatchId()));
        assertTrue(accepted, "受理仅回写微信批次号，原持久状态仍处理中");
    }

    @Test
    void withdraw_transferFails_refundsBalance_andThrows() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(umsMemberService.deductWithdrawableBalance(900002L, new BigDecimal("30.00"), BigDecimal.ZERO)).thenReturn(true);
        when(umsMemberService.addBalance(900002L, new BigDecimal("30.00"))).thenReturn(true);
        when(wechatPayService.transferToBalance(any(), any(), any(), any(), any(), any()))
                .thenReturn(transferResult(false, "转账服务未就绪"));
        when(mapper.updateStatusIfProcessing(any())).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> svc.withdraw(900002L, new BigDecimal("30")));

        // 关键:出金失败必须原路退回余额,不丢钱,并置失败已退款
        verify(umsMemberService).addBalance(900002L, new BigDecimal("30.00"));
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper).updateStatusIfManual(cap.capture());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, cap.getValue().getStatus());
    }

    /**
     * P0-1 复现:转账发起「结果不确定」(超时/网络/验签异常,transferToBalance 返回 ok=false + ambiguous=true)。
     * 微信可能已受理并已打款,此时绝不能退款+置终态失败,否则「已打款 + 余额回补」= 双花丢钱。
     * 期望:不调用 addBalance、不置终态失败(status=2),保持 status=0 处理中,交由查询任务用 out_batch_no 核实。
     */
    @Test
    void withdraw_transferAmbiguous_keepsProcessing_noRefund() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(umsMemberService.deductWithdrawableBalance(900002L, new BigDecimal("30.00"), BigDecimal.ZERO)).thenReturn(true);
        Map<String, String> ambiguous = new HashMap<>();
        ambiguous.put("ok", "false");
        ambiguous.put("ambiguous", "true");
        ambiguous.put("error", "connect timed out");
        when(wechatPayService.transferToBalance(any(), any(), any(), any(), any(), any())).thenReturn(ambiguous);

        // 不确定态不应抛异常给用户(单已受理待查),正常返回处理中单
        MemberCashWithdraw current = svc.withdraw(900002L, new BigDecimal("30"));
        assertEquals(Integer.valueOf(0), current.getStatus());

        // 核心断言:结果不确定时绝不退款,绝不置终态失败
        verify(umsMemberService, never()).addBalance(any(), any());
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateReceiptIfProcessing(cap.capture());
        boolean anyTerminalFail = cap.getAllValues().stream()
                .anyMatch(v -> Integer.valueOf(2).equals(v.getStatus()));
        assertTrue(!anyTerminalFail, "结果不确定禁止置『失败已退款』终态(会造成双花丢钱)");
    }

    @Test
    void withdraw_insufficientBalance_noTransfer() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "5"));

        assertThrows(IllegalStateException.class, () -> svc.withdraw(900002L, new BigDecimal("30")));

        verify(mapper, never()).insertMemberCashWithdraw(any());
        verify(umsMemberService, never()).deductWithdrawableBalance(any(), any(), any());
        verify(wechatPayService, never()).transferToBalance(any(), any(), any(), any(), any(), any());
        verify(umsMemberService, never()).addBalance(any(), any());
    }

    @Test
    void withdraw_noWechatBound_rejectedBeforeInsert() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member(null, "100"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.withdraw(900002L, new BigDecimal("30")));
        assertTrue(ex.getMessage().contains("微信"));
        verify(mapper, never()).insertMemberCashWithdraw(any());
        verify(umsMemberService, never()).deductWithdrawableBalance(any(), any(), any());
    }

    /**
     * P2-21 负控:余额 100 里有 80 是仍在 48h 冻结期内的拓展赏金,可用只有 20。
     * 旧路径用 deductBalance(护栏只认裸余额 100≥30)会放行 ⇒ 冻结款被提走。
     * 期望:发起前就拒绝,不建单、不扣款、不发起转账 —— 也就不会留处理中僵尸单。
     */
    @Test
    void withdraw_frozenBounty_rejectedBeforeInsert() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(merchantReferralBountyService.frozenAmount(900002L)).thenReturn(new BigDecimal("80"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.withdraw(900002L, new BigDecimal("30")));
        assertTrue(ex.getMessage().contains("赏金"), "错误文案须指明是赏金冻结这道闸,实际:" + ex.getMessage());

        verify(mapper, never()).insertMemberCashWithdraw(any());
        verify(umsMemberService, never()).deductWithdrawableBalance(any(), any(), any());
        verify(wechatPayService, never()).transferToBalance(any(), any(), any(), any(), any(), any());
    }

    /**
     * P2-21:冻结额算不出来必须 fail-closed —— 吞异常返回 0 等于冻结静默失效、钱当场提走。
     */
    @Test
    void withdraw_frozenQueryFails_failsClosed() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(merchantReferralBountyService.frozenAmount(900002L))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> svc.withdraw(900002L, new BigDecimal("30")));

        verify(mapper, never()).insertMemberCashWithdraw(any());
        verify(wechatPayService, never()).transferToBalance(any(), any(), any(), any(), any(), any());
    }

    /**
     * P2-21:并发下 Java 侧 available 预检会被绕过(check-then-act),真防线是带冻结留底的原子扣款。
     * 原子扣款返回 false(0 行)→ 置终态 5(未扣款)、不告警、不假称已退款、不进人工回补。
     */
    @Test
    void withdraw_atomicDeductLosesRace_noZombieProcessing() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(merchantReferralBountyService.frozenAmount(900002L)).thenReturn(new BigDecimal("60"));
        // 预检通过(可用 40 ≥ 30),但原子扣款被并发抢先 ⇒ 返回 false
        when(umsMemberService.deductWithdrawableBalance(900002L, new BigDecimal("30.00"), new BigDecimal("60")))
                .thenReturn(false);

        assertThrows(IllegalStateException.class, () -> svc.withdraw(900002L, new BigDecimal("30")));

        verify(wechatPayService, never()).transferToBalance(any(), any(), any(), any(), any(), any());
        verify(umsMemberService, never()).addBalance(any(), any());
        verify(alarmService, never()).alarm(anyString(), anyString());
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateMemberCashWithdraw(cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(v -> Integer.valueOf(5).equals(v.getStatus())),
                "原子扣款 false 须置终态 5(未扣款),实际:" + cap.getAllValues());
        assertTrue(cap.getAllValues().stream().anyMatch(v -> v.getManualNote() != null
                && v.getManualNote().contains("UPDATE 0 行")),
                "manualNote 须写明未扣款,实际:" + cap.getAllValues());
    }

    /**
     * P2-21:deductWithdrawableBalance 抛 RuntimeException → 扣款状态不明(DB 可能已扣但响应丢失)。
     * 不得自动退款、不得标已退款、必须告警+置待人工(3)。*/
    @Test
    void withdraw_deductThrows_marksManualAndAlarms() {
        when(mapper.updateStatusIfProcessing(any())).thenReturn(1);
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(merchantReferralBountyService.frozenAmount(900002L)).thenReturn(new BigDecimal("60"));
        doThrow(new RuntimeException("DB connection lost"))
                .when(umsMemberService).deductWithdrawableBalance(900002L, new BigDecimal("30.00"), new BigDecimal("60"));

        assertThrows(IllegalStateException.class, () -> svc.withdraw(900002L, new BigDecimal("30")));

        verify(wechatPayService, never()).transferToBalance(any(), any(), any(), any(), any(), any());
        verify(umsMemberService, never()).addBalance(any(), any());
        verify(alarmService, times(1)).alarm(anyString(), anyString());
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateStatusIfProcessing(cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(v -> Integer.valueOf(3).equals(v.getStatus())),
                "扣款异常须置待人工(3),实际:" + cap.getAllValues());
    }

    /**
     * P2-21 负控:deductWithdrawableBalance 抛 IllegalStateException(如 db down)
     * 也走异常分支 → status=3+告警,不因异常类型而被漏成 status=0。
     */
    @Test
    void withdraw_deductThrowsIllegalState_goesManualNotZombie() {
        when(mapper.updateStatusIfProcessing(any())).thenReturn(1);
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(merchantReferralBountyService.frozenAmount(900002L)).thenReturn(new BigDecimal("60"));
        doThrow(new IllegalStateException("db down"))
                .when(umsMemberService).deductWithdrawableBalance(900002L, new BigDecimal("30.00"), new BigDecimal("60"));

        assertThrows(IllegalStateException.class, () -> svc.withdraw(900002L, new BigDecimal("30")));

        verify(alarmService, times(1)).alarm(anyString(), anyString());
        ArgumentCaptor<MemberCashWithdraw> cap = ArgumentCaptor.forClass(MemberCashWithdraw.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateStatusIfProcessing(cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(v -> Integer.valueOf(3).equals(v.getStatus())),
                "IllegalStateException 也是未知异常,必须 status=3 不是 0,实际:" + cap.getAllValues());
    }

    /** 无冻结额时照常放行,冻结闸不得误伤正常提现。 */
    @Test
    void withdraw_noFrozen_stillAllowed() {
        when(umsMemberService.selectUmsMemberById(900002L)).thenReturn(member("oXAbc", "100"));
        when(umsMemberService.deductWithdrawableBalance(900002L, new BigDecimal("30.00"), BigDecimal.ZERO))
                .thenReturn(true);
        when(wechatPayService.transferToBalance(eq("CW500"), eq("CD500"), eq(new BigDecimal("30.00")), eq("oXAbc"), any(), any()))
                .thenReturn(transferResult(true, "BATCH123"));

        svc.withdraw(900002L, new BigDecimal("30"));

        verify(umsMemberService).deductWithdrawableBalance(900002L, new BigDecimal("30.00"), BigDecimal.ZERO);
    }

    @Test
    void withdraw_belowMinAmount_rejected() {
        assertThrows(IllegalStateException.class, () -> svc.withdraw(900002L, new BigDecimal("0.5")));
        verify(mapper, never()).insertMemberCashWithdraw(any());
    }
}
