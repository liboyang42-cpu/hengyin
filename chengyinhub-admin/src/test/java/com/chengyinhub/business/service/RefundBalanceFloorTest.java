package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-3 退款冲销把商家余额扣成负数。
 *
 * 病:debitReceiverBalance 直接 addBalance(id, -amount),全程不看当前余额。商家把分成提现走之后
 * 再发生退款,余额就被扣成负数 —— 负余额既不是真债权(没有追偿凭据),又会被后续结算/提现逻辑当成
 * 正常余额参与运算。同 codebase 的 CoopComplaintServiceImpl:87-97 有保护(只扣可扣部分 + 差额转人工),
 * 两处不一致像遗漏不像设计。
 *
 * 口径(照 CoopComplaintServiceImpl 同款):余额不足只扣到 0 为止,差额告警转人工追偿,不制造负余额。
 */
class RefundBalanceFloorTest {

    private RefundApplicationMapper refundApplicationMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private CmsActivityMapper cmsActivityMapper;
    private SettlementDetailMapper settlementDetailMapper;
    private UmsMemberMapper umsMemberMapper;
    private UmsMemberBalanceDetailMapper umsMemberBalanceDetailMapper;
    private RefundServiceImpl svc;
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;
    private com.chengyinhub.business.mapper.ActivitySettlementHoldMapper holdMapper;

    @BeforeEach
    void setUp() {
        refundApplicationMapper = mock(RefundApplicationMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        cmsActivityMapper = mock(CmsActivityMapper.class);
        settlementDetailMapper = mock(SettlementDetailMapper.class);
        umsMemberMapper = mock(UmsMemberMapper.class);
        umsMemberBalanceDetailMapper = mock(UmsMemberBalanceDetailMapper.class);

        svc = new RefundServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "firstReviewPointsClawback",

                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(svc, "refundApplicationMapper", refundApplicationMapper);
        // [P0-15] ③ 退款释放权益容量 —— 本类不测该链路,注入空 mock 即可;
        //        不注入会 NPE(手工装配不走 Spring 容器)。
        ReflectionTestUtils.setField(svc, "perkLedgerService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        // [赏金] markRegistrationRefunded 末尾会调 onRefund;本用例不管赏金,哑 mock 即可。
        ReflectionTestUtils.setField(svc, "merchantReferralBountyService",
                mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        org.mockito.Mockito.when(cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> cmsRegistrationMapper.selectCmsRegistrationById(call.getArgument(0)));
        // [P0-1] 本类测的是「扣多少」,前提是**该扣** —— 即系统1 已真打过款。
        //        该前提现在必须显式给(见 RefundSingleSidedDebitGuardTest):退款冲销前会问父级
        //        cms_activity.is_settlement,不给就判定「没打过款」→ 一分不扣 → 本类断言全落空。
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(svc, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailMapper", umsMemberBalanceDetailMapper);
        settlementDebtLedger = mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class);
        holdMapper = mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class);
        ReflectionTestUtils.setField(svc, "settlementDebtLedger", settlementDebtLedger);
        ReflectionTestUtils.setField(svc, "activitySettlementHoldMapper", holdMapper);
    }

    /** 系统1 已结算过该活动(is_settlement=1)⇒ 商家真收到过分成 ⇒ 退款该扣回。 */
    private void givenSystem1AlreadyPaid() {
        CmsActivity activity = new CmsActivity();
        activity.setId(77L);
        activity.setIsSettlement(1);
        when(cmsActivityMapper.selectCmsActivityById(77L)).thenReturn(activity);
    }

    /** 已结算明细:商家(id=5)拿 90 */
    private SettlementDetail merchantDetail(String amount) {
        SettlementDetail s = new SettlementDetail();
        s.setMerchantId(5L);
        s.setReceiverType("merchant");
        s.setReceiverId(5L);
        s.setGrossAmount(new BigDecimal(amount));
        s.setCommissionAmount(BigDecimal.ZERO);
        s.setSettlementAmount(new BigDecimal(amount));
        s.setStatus(1);
        return s;
    }

    /** 全额退款(ratio=1)的审批请求,已结算 90 归商家 */
    private RefundReviewDTO approveFullRefund(String merchantBalance) {
        RefundReviewDTO req = new RefundReviewDTO();
        req.setId(1L);
        req.setStatus(1); // approved

        RefundApplication refund = new RefundApplication();
        refund.setId(1L);
        refund.setSourceType("registration");
        refund.setSourceId(10L);
        refund.setRefundAmount(new BigDecimal("90"));
        refund.setPaidAmount(new BigDecimal("90")); // ratio = 1 → 应扣回 90
        when(refundApplicationMapper.selectRefundApplicationById(1L)).thenReturn(refund);

        CmsRegistration reg = new CmsRegistration();
        reg.setPaymentStatus(2);
        reg.setPayableAmount(new BigDecimal("90"));
        reg.setId(10L);
        reg.setOwnerType(2L);   // 活动报名
        reg.setOwnerId(77L);
        when(cmsRegistrationMapper.selectCmsRegistrationById(10L)).thenReturn(reg);
        when(cmsRegistrationMapper.claimRegistrationRefunded(eq(10L), any(), any())).thenReturn(1);
        givenSystem1AlreadyPaid();
        when(refundApplicationMapper.updateRefundStatusIfPending(anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(1);
        when(settlementDetailMapper.selectSettledDetails("registration", 10L))
                .thenReturn(Collections.singletonList(merchantDetail("90.00")));
        when(settlementDetailMapper.insertAdjustment(any())).thenReturn(1);

        UmsMember m = new UmsMember();
        m.setBalance(new BigDecimal(merchantBalance));
        when(umsMemberMapper.selectUmsMemberById(5L)).thenReturn(m);
        when(umsMemberMapper.lockMemberById(5L)).thenReturn(5L);
        when(umsMemberMapper.deductBalance(eq(5L), any())).thenReturn(1);
        when(umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(any())).thenReturn(1);
        return req;
    }

    /**
     * 核心:商家余额 50 < 应扣 90(分成已提现走 40)。
     * 修复前 addBalance(5, -90.00) → 余额 -40 = 负余额;修复后只扣可扣的 50,余额落到 0。
     */
    @Test
    void insufficientBalance_debitsOnlyAvailable_neverGoesNegative() {
        RefundReviewDTO req = approveFullRefund("50.00");

        svc.reviewRefund(req, 42L);

        // 只扣可扣部分,不制造负余额;修复前这里是 -90.00 → RED
        verify(umsMemberMapper).deductBalance(eq(5L), eq(new BigDecimal("50.00")));
        verify(umsMemberMapper, never()).addBalance(anyLong(), any());

        // 流水的 after_balance 必须落在 0,不能记成 -40
        ArgumentCaptor<UmsMemberBalanceDetail> cap = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(umsMemberBalanceDetailMapper).insertUmsMemberBalanceDetail(cap.capture());
        UmsMemberBalanceDetail flow = cap.getValue();
        assertEquals(0, BigDecimal.ZERO.compareTo(flow.getAfterBalance()),
                "余额不足时 after_balance 应为 0,实际 " + flow.getAfterBalance());
        assertTrue(flow.getAfterBalance().compareTo(BigDecimal.ZERO) >= 0, "after_balance 不得为负");
        // 9-16 拍板第 2 条:扣不动的 40 记欠款,从后续结算抵扣(不再只是告警转人工)
        verify(settlementDebtLedger).record(5L, com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_REFUND + ":merchant", 1L, new BigDecimal("40.00"));
    }

    /** 余额充足时行为不变:足额扣回 90(防保护写过头,把正常路径也削了)。 */
    @Test
    void sufficientBalance_debitsFullAmount_unchanged() {
        RefundReviewDTO req = approveFullRefund("200.00");

        svc.reviewRefund(req, 42L);

        verify(umsMemberMapper).deductBalance(eq(5L), eq(new BigDecimal("90.00")));

        ArgumentCaptor<UmsMemberBalanceDetail> cap = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(umsMemberBalanceDetailMapper).insertUmsMemberBalanceDetail(cap.capture());
        assertEquals(0, new BigDecimal("110.00").compareTo(cap.getValue().getAfterBalance()),
                "余额充足应足额扣回,after_balance = 200-90 = 110");
    }

    /** 余额为 0(全部提现走):一分都扣不动,不该产生 addBalance 调用,更不该扣成 -90。 */
    @Test
    void zeroBalance_doesNotDebitAtAll() {
        RefundReviewDTO req = approveFullRefund("0.00");

        svc.reviewRefund(req, 42L);

        verify(umsMemberMapper, never()).deductBalance(anyLong(), any());
        verify(umsMemberBalanceDetailMapper, never()).insertUmsMemberBalanceDetail(any());
        verify(settlementDebtLedger).record(5L, com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_REFUND + ":merchant", 1L, new BigDecimal("90.00"));
    }

    /** 客诉期提现闸:这单结算时因涉诉被暂扣、还没补结 ⇒ 商家没拿到这单的钱 ⇒ 退款不扣商家、不记欠款。 */
    @Test
    void heldDisputedRegistrationIsNotClawedBack() {
        RefundReviewDTO req = approveFullRefund("200.00");
        when(holdMapper.countHeldByRegistrationId(10L)).thenReturn(1);

        svc.reviewRefund(req, 42L);

        verify(umsMemberMapper, never()).deductBalance(anyLong(), any());
        verify(settlementDebtLedger, never()).record(any(), any(), any(), any());
    }

    @Test
    void zeroAffectedDebitRollsBackInsteadOfWritingAFalseFlow() {
        RefundReviewDTO req = approveFullRefund("50.00");
        when(umsMemberMapper.deductBalance(5L, new BigDecimal("50.00"))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> svc.reviewRefund(req, 42L));

        verify(umsMemberBalanceDetailMapper, never()).insertUmsMemberBalanceDetail(any());
    }
}
