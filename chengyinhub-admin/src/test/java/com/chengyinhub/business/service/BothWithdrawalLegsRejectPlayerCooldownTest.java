package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.mapper.PlayerWithdrawalCooldownMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import com.chengyinhub.business.security.WithdrawalPiiProtector;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CashWithdrawServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.PlayerWithdrawalCooldownServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberWithdrawalServiceImpl;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ★C12：提现有两条腿。只给银行卡那条接冻结源，微信零钱就是冷静期的旁路 ——
 * 同一个 bug 在赏金冻结上已经发生过一次（见 CashWithdrawServiceImpl 里的注释），
 * 这次是玩家真金，不许再造一遍。
 *
 * <p>本用例把<b>同一个</b>未到期冷静额喂给两条腿，两条都必须拒绝，且都不留副作用。
 * 只接一条腿 ⇒ 另一条腿这里当场红。
 */
class BothWithdrawalLegsRejectPlayerCooldownTest {
    private static final long MEMBER = 900002L;

    private UmsMemberMapper umsMemberMapper;
    private UmsMemberWithdrawalMapper withdrawalMapper;
    private MemberCashWithdrawMapper cashMapper;
    private IUmsMemberService umsMemberService;
    private IWechatPayService wechatPayService;
    private PlayerWithdrawalCooldownMapper cooldownMapper;
    private UmsMemberBalanceDetailMapper balanceDetailMapper;

    private UmsMemberWithdrawalServiceImpl bankLeg;
    private CashWithdrawServiceImpl wechatLeg;
    private CashWithdrawIntentTestSupport evidence;

    @BeforeEach
    void setUp() {
        umsMemberMapper = mock(UmsMemberMapper.class);
        withdrawalMapper = mock(UmsMemberWithdrawalMapper.class);
        cashMapper = mock(MemberCashWithdrawMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        wechatPayService = mock(IWechatPayService.class);
        cooldownMapper = mock(PlayerWithdrawalCooldownMapper.class);
        balanceDetailMapper = mock(UmsMemberBalanceDetailMapper.class);
        when(balanceDetailMapper.insertUmsMemberBalanceDetail(any())).thenReturn(1);

        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        when(bounty.frozenAmount(anyLong())).thenReturn(BigDecimal.ZERO);
        ISysConfigService configService = mock(ISysConfigService.class);
        // 真的 cooldown service + 真的 resolver：这里测的是两条腿有没有接上，不是 mock 的返回值。
        PlayerWithdrawalCooldownServiceImpl cooldownService =
                new PlayerWithdrawalCooldownServiceImpl(cooldownMapper, configService);
        WithdrawalFrozenAmountResolver resolver =
                new WithdrawalFrozenAmountResolver(bounty, cooldownService);

        bankLeg = new UmsMemberWithdrawalServiceImpl();
        ReflectionTestUtils.setField(bankLeg, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(bankLeg, "umsMemberWithdrawalMapper", withdrawalMapper);
        ReflectionTestUtils.setField(bankLeg, "umsMemberBalanceDetailMapper", balanceDetailMapper);
        ReflectionTestUtils.setField(bankLeg, "withdrawalPiiProtector", mock(WithdrawalPiiProtector.class));
        ReflectionTestUtils.setField(bankLeg, "frozenAmountResolver", resolver);
        ReflectionTestUtils.setField(bankLeg, "balanceAdmissionService",
                com.chengyinhub.business.longrun.OWN_C04_21Test.bankFixture(umsMemberMapper, resolver));

        wechatLeg = new CashWithdrawServiceImpl();
        evidence = new com.chengyinhub.business.service.CashWithdrawIntentTestSupport();
        ReflectionTestUtils.setField(wechatLeg, "transferIntents", evidence.mapper);
        org.springframework.transaction.PlatformTransactionManager cashTransactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(cashTransactions.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        ReflectionTestUtils.setField(wechatLeg, "transactionManager", cashTransactions);
        ReflectionTestUtils.setField(wechatLeg, "mapper", cashMapper);
        ReflectionTestUtils.setField(wechatLeg, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(wechatLeg, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(wechatLeg, "alarmService", mock(AlarmService.class));
        { com.chengyinhub.common.config.WxPayConfig __cfg = org.mockito.Mockito.mock(com.chengyinhub.common.config.WxPayConfig.class);
          org.mockito.Mockito.when(__cfg.getAppid()).thenReturn("wx-mp-appid");
          org.mockito.Mockito.when(__cfg.getMerchantId()).thenReturn("unit-merchant");
          org.mockito.Mockito.when(__cfg.getAppAppid()).thenReturn("wx-app-appid");
          // R1 起 withdraw 要按来源选同源 appid,不注入这个新依赖就是 NPE
          ReflectionTestUtils.setField(wechatLeg, "wxPayConfig", __cfg); }
        ReflectionTestUtils.setField(wechatLeg, "frozenAmountResolver", resolver);
        ReflectionTestUtils.setField(wechatLeg, "balanceAdmissionService",
                com.chengyinhub.business.longrun.OWN_C04_21Test.cashFixture(umsMemberService, cashMapper, resolver, evidence.mapper));

        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setBalance(new BigDecimal("100"));
        member.setWxOpenId("o-openid");
        when(umsMemberMapper.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(umsMemberService.selectUmsMemberById(MEMBER)).thenReturn(member);
    }

    /** 余额 100、冷静期内 20 ⇒ 可用 80。两条腿提 81 都必须拒。 */
    @Test
    void bothLegsRefuseToPayOutMoneyThatIsStillInTheComplaintCooldown() {
        givenComplaintFrozen("20");

        RuntimeException bank = assertThrows(RuntimeException.class,
                () -> bankLeg.withdrawalRequest(request("81"), MEMBER, "player"));
        IllegalStateException wechat = assertThrows(IllegalStateException.class,
                () -> wechatLeg.withdraw(MEMBER, new BigDecimal("81")));

        assertTrue(bank.getMessage().contains("投诉补偿冷静期"),
                "银行卡腿的拒绝理由必须点名冷静期,实际:" + bank.getMessage());
        assertTrue(wechat.getMessage().contains("投诉补偿冷静期"),
                "★微信零钱腿也必须被同一个冷静额挡住,实际:" + wechat.getMessage());
        // ★契约锁定的业务错误码：中文文案给人看,小程序/监控只认这个稳定符号。
        assertTrue(bank.getMessage().contains(
                        WithdrawalFrozenAmountResolver.Frozen.PLAYER_WITHDRAWAL_COOLDOWN),
                "银行卡腿必须带固定机器码,实际:" + bank.getMessage());
        assertTrue(wechat.getMessage().contains(
                        WithdrawalFrozenAmountResolver.Frozen.PLAYER_WITHDRAWAL_COOLDOWN),
                "微信零钱腿必须带同一个机器码,实际:" + wechat.getMessage());
    }

    /** 纯余额不足(零冷静额)时不得挂冷静期的码 —— 否则监控会把普通余额不足误报成冷静期拦截。 */
    @Test
    void aPlainInsufficientBalanceIsNotLabelledAsACooldownRejection() {
        givenComplaintFrozen("0");

        RuntimeException bank = assertThrows(RuntimeException.class,
                () -> bankLeg.withdrawalRequest(request("101"), MEMBER, "player"));
        IllegalStateException wechat = assertThrows(IllegalStateException.class,
                () -> wechatLeg.withdraw(MEMBER, new BigDecimal("101")));

        assertFalse(bank.getMessage().contains(
                WithdrawalFrozenAmountResolver.Frozen.PLAYER_WITHDRAWAL_COOLDOWN), bank.getMessage());
        assertFalse(wechat.getMessage().contains(
                WithdrawalFrozenAmountResolver.Frozen.PLAYER_WITHDRAWAL_COOLDOWN), wechat.getMessage());
    }

    /** 拒绝时不扣余额、不建提现单 —— 两条腿都不许留下任何副作用。 */
    @Test
    void neitherLegLeavesAnySideEffectWhenItRefuses() {
        givenComplaintFrozen("20");

        assertThrows(RuntimeException.class, () -> bankLeg.withdrawalRequest(request("81"), MEMBER, "player"));
        assertThrows(IllegalStateException.class, () -> wechatLeg.withdraw(MEMBER, new BigDecimal("81")));

        verify(umsMemberMapper, never()).deductWithdrawableBalance(anyLong(), any(), any());
        verify(withdrawalMapper, never()).insertUmsMemberWithdrawal(any());
        verify(umsMemberService, never()).deductWithdrawableBalance(anyLong(), any(), any());
        verify(cashMapper, never()).insertMemberCashWithdraw(any());
    }

    /** 冻结额必须一次原子传给扣减：分别扣或先查后扣都会让并发提走冻结款。 */
    @Test
    void bothLegsHandTheCombinedFrozenAmountToTheAtomicDeduction() {
        givenComplaintFrozen("20");
        when(umsMemberMapper.deductWithdrawableBalance(eqMember(), any(), any())).thenReturn(1);
        when(withdrawalMapper.insertUmsMemberWithdrawal(any())).thenReturn(1);
        when(umsMemberService.deductWithdrawableBalance(eqMember(), any(), any())).thenReturn(true);
        when(cashMapper.countPendingByMember(MEMBER)).thenReturn(0);
        java.util.concurrent.atomic.AtomicReference<MemberCashWithdraw> original =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(cashMapper.insertMemberCashWithdraw(any())).thenAnswer(invocation -> {
            MemberCashWithdraw inserted = invocation.getArgument(0);
            inserted.setId(500L);
            original.set(inserted);
            return 1;
        });
        when(cashMapper.selectById(500L)).thenAnswer(invocation -> original.get());
        // cashFixture retains the inserted original and binds an explicit DEBIT fact.

        when(wechatPayService.transferToBalance(any(com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.class)))
                .thenReturn(java.util.Collections.singletonMap("ok", "true"));
        when(cashMapper.updateReceiptIfProcessing(any())).thenReturn(1);

        bankLeg.withdrawalRequest(request("80"), MEMBER, "player");
        MemberCashWithdraw current = wechatLeg.withdraw(MEMBER, new BigDecimal("80"));
        assertSame(original.get(), current, "准入与返回都沿真实插入的原提现单");
        assertEquals(Long.valueOf(500L), current.getId());
        assertEquals(Long.valueOf(MEMBER), current.getMemberId());
        assertEquals(new BigDecimal("80.00"), current.getAmount());
        assertEquals(Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING), current.getStatus());
        assertNull(current.getFrozen(), "沿真实插入默认未冻结状态，不合成准入成功");

        verify(umsMemberMapper).deductWithdrawableBalance(MEMBER, new BigDecimal("80"), new BigDecimal("20"));
        // 微信零钱腿会先 setScale(2,DOWN) 再扣，所以是 80.00；关键是第三个实参必须是同一个总冻结额 20。
        verify(umsMemberService).deductWithdrawableBalance(MEMBER, new BigDecimal("80.00"), new BigDecimal("20"));
    }

    /** 一笔已到期、一笔未到期时，SQL 只汇总未到期的那笔；服务层不得自己再筛一遍或全加。 */
    @Test
    void onlyTheUnexpiredEventIsFrozenBecauseTheSumIsScopedInSql() {
        givenComplaintFrozen("20");

        assertEquals(new BigDecimal("20"),
                bankLegFrozenSeenByTheAtomicGuard(), "服务层只搬运 SQL 汇总出来的未到期额");
    }

    @Test
    void aFailingCooldownLookupBlocksBothLegsInsteadOfBeingReadAsZero() {
        when(cooldownMapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(RuntimeException.class, () -> bankLeg.withdrawalRequest(request("10"), MEMBER, "player"));
        assertThrows(RuntimeException.class, () -> wechatLeg.withdraw(MEMBER, new BigDecimal("10")));

        verify(umsMemberMapper, never()).deductWithdrawableBalance(anyLong(), any(), any());
        verify(umsMemberService, never()).deductWithdrawableBalance(anyLong(), any(), any());
    }

    private BigDecimal bankLegFrozenSeenByTheAtomicGuard() {
        when(umsMemberMapper.deductWithdrawableBalance(eqMember(), any(), any())).thenReturn(1);
        when(withdrawalMapper.insertUmsMemberWithdrawal(any())).thenReturn(1);
        bankLeg.withdrawalRequest(request("80"), MEMBER, "player");
        org.mockito.ArgumentCaptor<BigDecimal> frozen = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(umsMemberMapper).deductWithdrawableBalance(eqMember(), any(), frozen.capture());
        return frozen.getValue();
    }

    private void givenComplaintFrozen(String amount) {
        when(cooldownMapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER)).thenReturn(new BigDecimal(amount));
    }

    private static long eqMember() {
        return org.mockito.ArgumentMatchers.eq(MEMBER);
    }

    private static WithdrawalDTO request(String amount) {
        WithdrawalDTO dto = new WithdrawalDTO();
        dto.setWithdrawalAmount(new BigDecimal(amount));
        dto.setRealname("张三");
        dto.setBankName("招商银行");
        dto.setBankAccount("6225880000000000");
        dto.setMobilephone("13800138000");
        dto.setRequestId("cooldown-bank-withdrawal");
        return dto;
    }
}
