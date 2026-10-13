package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.mapper.MerchantReferralBountyMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import com.chengyinhub.business.security.WithdrawalPiiProtector;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.UmsMemberWithdrawalServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 48h 赏金冻结:提现路径真的刨掉冻结额了吗。
 *
 * <p><b>本用例存在的理由</b>:{@code frozen_until} 列刚建出来时<b>全仓零读方</b> ——
 * 提现只校验 {@code balance >= amount},压根不认冻结。那样「48h 冻结」就只是列名里的一句话:
 * 钱当天提走,clawback 追回时只能把余额扣成负数,而挂账抵扣(定稿 D14)依赖收付通、尚未到位。
 * 「规则只活在字段名里」正是本轨要消灭的病,所以冻结必须有一条能打红的断言看着。
 *
 * <p>负控实测(2026-07-17):
 * <ul>
 *   <li>把 {@code balance.subtract(frozen)} 改回 {@code balance} ⇒ blocks/boundary 两条变红</li>
 *   <li>把 deductWithdrawableBalance 的 frozen 实参改传 BigDecimal.ZERO
 *       ⇒ passesFrozenFloorIntoTheAtomicGuard_notJustThePreCheck 变红</li>
 * </ul>
 * 撤销后均绿。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferralBountyFreezeTest {

    private static final long MEMBER = 100L;

    @Mock
    private UmsMemberMapper umsMemberMapper;
    @Mock
    private UmsMemberWithdrawalMapper umsMemberWithdrawalMapper;
    @Mock
    private UmsMemberBalanceDetailMapper umsMemberBalanceDetailMapper;
    @Mock
    private WithdrawalPiiProtector withdrawalPiiProtector;
    @Mock
    private MerchantReferralBountyService merchantReferralBountyService;

    @Mock
    private com.chengyinhub.business.service.IPlayerWithdrawalCooldownService playerWithdrawalCooldownService;

    @InjectMocks
    private UmsMemberWithdrawalServiceImpl service;

    /**
     * 两条提现腿现在共用 WithdrawalFrozenAmountResolver。这里装**真的** resolver（只把两个来源
     * mock 掉），所以本用例仍然在测「提现真的刨掉了冻结额」，而不是测一个 mock 的返回值。
     */
    @org.junit.jupiter.api.BeforeEach
    void wireSharedFrozenResolver() {
        when(playerWithdrawalCooldownService.frozenAmount(anyLong())).thenReturn(BigDecimal.ZERO);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "frozenAmountResolver",
                new com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver(merchantReferralBountyService, playerWithdrawalCooldownService));
    }

    private void givenBalance(String balance) {
        UmsMember m = new UmsMember();
        m.setId(MEMBER);
        m.setBalance(new BigDecimal(balance));
        when(umsMemberMapper.selectUmsMemberById(MEMBER)).thenReturn(m);
    }

    private void givenFrozen(String frozen) {
        when(merchantReferralBountyService.frozenAmount(MEMBER)).thenReturn(new BigDecimal(frozen));
    }

    private static WithdrawalDTO request(String amount) {
        WithdrawalDTO r = new WithdrawalDTO();
        r.setWithdrawalAmount(new BigDecimal(amount));
        r.setRealname("张三");
        r.setBankName("招商银行");
        r.setBankAccount("6225880000000000");
        r.setMobilephone("13800138000");
        r.setRequestId("referral-freeze-withdrawal");
        return r;
    }

    private void givenWithdrawalInsertsOk() {
        when(umsMemberMapper.deductWithdrawableBalance(anyLong(), any(), any())).thenReturn(1);
        when(umsMemberWithdrawalMapper.insertUmsMemberWithdrawal(any())).thenReturn(1);
        when(umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(any())).thenReturn(1);
    }

    /** ★ 余额 50、其中 30 是冻结赏金 ⇒ 提 40 必须拒(可用只有 20)。 */
    @Test
    void blocksWithdrawal_whenBountyStillFrozen() {
        givenBalance("50.00");
        givenFrozen("30.00");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.withdrawalRequest(request("40.00"), MEMBER, "u"));

        assertTrue(e.getMessage().contains("48"), "拒绝理由该告诉用户 48 小时后可提,实际:" + e.getMessage());
        // 钱一分不能动
        verify(umsMemberMapper, never()).deductWithdrawableBalance(anyLong(), any(), any());
        verify(umsMemberWithdrawalMapper, never()).insertUmsMemberWithdrawal(any());
    }

    /** 可用额度内的提现照常放行 —— 冻结不能把人全锁死。 */
    @Test
    void allowsWithdrawal_withinUnfrozenPortion() {
        givenBalance("50.00");
        givenFrozen("30.00");
        givenWithdrawalInsertsOk();

        service.withdrawalRequest(request("20.00"), MEMBER, "u");

        verify(umsMemberMapper).deductWithdrawableBalance(eq(MEMBER), eq(new BigDecimal("20.00")), any());
    }

    /**
     * ★★ 冻结额必须**传进原子护栏**,不能只用来算那句错误提示。
     *
     * <p>自审(/code-review high)抓到的:Java 里算 available 再判是 check-then-act。
     * 余额 50/冻结 30(可用 20)时,两个并发提现各 20 都会读到可用 20 而通过前置判断;
     * 而旧的 deductBalance 护栏只认裸余额(50≥20、30≥20)也都放行 ⇒ 余额剩 10 &lt; 冻结 30,
     * <b>¥20 冻结款被提走、48h 冻结当场失效</b>。
     *
     * <p>真防线是 deductWithdrawableBalance 把「给冻结额留底」和扣款放进同一条 UPDATE。
     * 本用例锁死 frozen 真的被传下去了 —— 传成 0 或漏传,这条就红。
     */
    @Test
    void passesFrozenFloorIntoTheAtomicGuard_notJustThePreCheck() {
        givenBalance("50.00");
        givenFrozen("30.00");
        givenWithdrawalInsertsOk();

        service.withdrawalRequest(request("20.00"), MEMBER, "u");

        ArgumentCaptor<BigDecimal> floor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(umsMemberMapper).deductWithdrawableBalance(eq(MEMBER), eq(new BigDecimal("20.00")), floor.capture());
        assertEquals(0, new BigDecimal("30.00").compareTo(floor.getValue()),
                "冻结额必须原样传给 UPDATE 的护栏,实际:" + floor.getValue());
    }

    /** 边界:可用额度 = 20,提 20 该过,提 20.01 该拒。别把 < 写成 <=。 */
    @Test
    void boundaryIsInclusive_atExactlyAvailable() {
        givenBalance("50.00");
        givenFrozen("30.00");
        givenWithdrawalInsertsOk();

        service.withdrawalRequest(request("20.00"), MEMBER, "u");
        verify(umsMemberMapper).deductWithdrawableBalance(eq(MEMBER), eq(new BigDecimal("20.00")), any());

        assertThrows(RuntimeException.class,
                () -> service.withdrawalRequest(request("20.01"), MEMBER, "u"));
    }

    /** 无冻结时行为与改动前完全一致(理由文案也不该提赏金)。 */
    @Test
    void unchangedBehaviour_whenNothingFrozen() {
        givenBalance("50.00");
        givenFrozen("0.00");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.withdrawalRequest(request("60.00"), MEMBER, "u"));
        assertEquals("用户余额不足", e.getMessage());
    }

    @Test
    void allowsFullBalance_whenNothingFrozen() {
        givenBalance("50.00");
        givenFrozen("0.00");
        givenWithdrawalInsertsOk();

        service.withdrawalRequest(request("50.00"), MEMBER, "u");

        verify(umsMemberMapper).deductWithdrawableBalance(eq(MEMBER), eq(new BigDecimal("50.00")), any());
    }

    /**
     * ★ 冻结额算不出来时必须 fail-close(整个提现失败),<b>不能</b>当 0 放行。
     *
     * <p>这与 onVerification/onRefund 的 fail-open 是相反的:那两个是「赏金别连累核销/退款」,
     * 这里是「算不出冻结额就不许放钱出去」。两个方向都对,别统一成一种写法。
     */
    @Test
    void failsClosed_whenFrozenQueryBlowsUp() {
        givenBalance("50.00");
        when(merchantReferralBountyService.frozenAmount(MEMBER))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class,
                () -> service.withdrawalRequest(request("10.00"), MEMBER, "u"));

        verify(umsMemberMapper, never()).deductWithdrawableBalance(anyLong(), any(), any());
    }
}
