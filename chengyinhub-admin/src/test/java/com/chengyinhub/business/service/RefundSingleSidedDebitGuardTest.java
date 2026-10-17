package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.UmsMember;
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
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-1 结算单边扣款回归(活动路)。
 *
 * <p><b>病</b>:PR#78 拆掉了系统2 的贷方({@code confirmBatch} 不再 addBalance,防与系统1 双花),
 * 但退款侧的借方原样留着 —— {@code reviewRefund} 读 {@code settlement_detail.status=1} 就扣钱。
 * 而 status=1 的真实含义只是「运营在后台点过**对账确认**」,不是「钱打过」。
 * ⇒ 运营点一次确认 + 玩家退一次款 = 商家凭空少一笔从没收到过的钱。
 *
 * <p><b>判据</b>:
 * 活动路唯一的真实打款方是 {@code CmsActivityServiceImpl.settleActivity},它以
 * {@code claimActivityForSettlement} 原子把 {@code is_settlement} 由 0 翻 1 后才发钱
 * ⇒ 商家仍以 {@code cms_activity.is_settlement==1} 作父级凭据。H8 取消 creator 现金后，
 * creator 还必须回读 event_type=6 正向现金流水，否则只有整单结算标记也不得扣款。
 *
 * <p>⚠️ 余额流水是 {@code event_id=activityId}，不挂 registration，所以仍需先从报名单解析父级活动。
 * 而交接档原定的 {@code change_reason='结算打款'} 在全仓余额流水里**不存在**(只在日志文案里),
 * 照它实现会恒 0 行 ⇒ 永不扣 ⇒ 翻转成反向漏付。
 *
 * <p>⚠️ 本类刻意**真调 reviewRefund 入口**并断言余额动作,不做源码文本 grep ——
 * {@code SettlementPayoutDecommissionedTest} 正是因为只 grep SettlementServiceImpl 单个文件,
 * 结构上不可能看见 RefundServiceImpl 这一侧的借方,才让本 P0 全程绿着溜过去。
 */
class RefundSingleSidedDebitGuardTest {

    private static final Long ACTIVITY_ID = 77L;
    private static final Long REGISTRATION_ID = 10L;
    private static final Long MERCHANT_ID = 5L;
    private static final Long CREATOR_ID = 6L;

    private RefundApplicationMapper refundApplicationMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private CmsActivityMapper cmsActivityMapper;
    private SettlementDetailMapper settlementDetailMapper;
    private UmsMemberMapper umsMemberMapper;
    private UmsMemberBalanceDetailMapper umsMemberBalanceDetailMapper;
    private RefundServiceImpl svc;

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
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "activitySettlementHoldMapper", org.mockito.Mockito.mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "settlementDebtLedger", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        ReflectionTestUtils.setField(svc, "refundApplicationMapper", refundApplicationMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        org.mockito.Mockito.when(cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> cmsRegistrationMapper.selectCmsRegistrationById(call.getArgument(0)));
        when(cmsRegistrationMapper.claimRegistrationRefunded(anyLong(), any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(svc, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailMapper", umsMemberBalanceDetailMapper);
        ReflectionTestUtils.setField(svc, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(svc, "merchantReferralBountyService",
                mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));
        when(umsMemberMapper.lockMemberById(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(umsMemberMapper.deductBalance(anyLong(), any())).thenReturn(1);
        when(umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(any())).thenReturn(1);
    }

    /** 活动报名:owner_type=2 指向 cms_activity。 */
    private void givenPaidRegistrationOnActivity() {
        RefundApplication refund = new RefundApplication();
        refund.setId(1L);
        refund.setSourceType("registration");
        refund.setSourceId(REGISTRATION_ID);
        refund.setRefundAmount(new BigDecimal("100"));
        refund.setPaidAmount(new BigDecimal("100")); // 全额退 → ratio=1
        when(refundApplicationMapper.selectRefundApplicationById(1L)).thenReturn(refund);

        CmsRegistration reg = new CmsRegistration();
        reg.setPaymentStatus(2);
        reg.setPayableAmount(new BigDecimal("100"));
        reg.setId(REGISTRATION_ID);
        reg.setOwnerType(2L);
        reg.setOwnerId(ACTIVITY_ID);
        when(cmsRegistrationMapper.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(reg);

        when(refundApplicationMapper.updateRefundStatusIfPending(anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(1);

        // 运营已点「确认打款」⇒ 明细 status=1。这是**对账确认**,不是打款。
        SettlementDetail merchant = new SettlementDetail();
        merchant.setMerchantId(MERCHANT_ID);
        merchant.setReceiverType("merchant");
        merchant.setReceiverId(MERCHANT_ID);
        merchant.setGrossAmount(new BigDecimal("90.00"));
        merchant.setCommissionAmount(BigDecimal.ZERO);
        merchant.setSettlementAmount(new BigDecimal("90.00"));
        merchant.setStatus(1);
        when(settlementDetailMapper.selectSettledDetails("registration", REGISTRATION_ID))
                .thenReturn(Collections.singletonList(merchant));
        when(settlementDetailMapper.insertAdjustment(any())).thenReturn(1);

        UmsMember m = new UmsMember();
        m.setBalance(new BigDecimal("90.00"));
        when(umsMemberMapper.selectUmsMemberById(MERCHANT_ID)).thenReturn(m);
    }

    private CmsActivity activity(int isSettlement) {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY_ID);
        a.setIsSettlement(isSettlement);
        return a;
    }

    /**
     * 系统1活路径的历史账本形态:支付时已落拆分明细,但系统1结算后没有把 status=0 翻成 1。
     */
    private void givenPendingLedgerOnly() {
        SettlementDetail merchant = new SettlementDetail();
        merchant.setMerchantId(MERCHANT_ID);
        merchant.setReceiverType("merchant");
        merchant.setReceiverId(MERCHANT_ID);
        merchant.setGrossAmount(new BigDecimal("90.00"));
        merchant.setCommissionAmount(BigDecimal.ZERO);
        merchant.setSettlementAmount(new BigDecimal("90.00"));
        merchant.setStatus(0);
        when(settlementDetailMapper.selectSettledDetails("registration", REGISTRATION_ID))
                .thenReturn(Collections.singletonList(merchant));
    }

    private RefundReviewDTO approve() {
        RefundReviewDTO req = new RefundReviewDTO();
        req.setId(1L);
        req.setStatus(1); // 批准
        return req;
    }

    /**
     * 🔴 负控方向①(本 P0 的病):运营点过「确认打款」(status=1),但系统1 从没结算过这个活动
     * (is_settlement=0)⇒ 商家从没收到过这 90 块 ⇒ **绝不能扣**。
     *
     * <p>修复前:reviewRefund 只看 status=1 就 debit ⇒ addBalance(5, -90.00) ⇒ 本测试红。
     */
    @Test
    void confirmedBatchButSystem1NeverPaid_mustNotDebitMerchant() {
        givenPaidRegistrationOnActivity();
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(0));

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(MERCHANT_ID), any());
        verify(umsMemberBalanceDetailMapper, never()).insertUmsMemberBalanceDetail(any());
    }

    /**
     * 🔴 负控方向②(反向):系统1 真结算过(is_settlement=1)⇒ 商家真收到过 90 ⇒ **必须扣回**。
     *
     * <p>这条守住修复不能矫枉过正成「永远不扣」—— 那是把单边扣款翻成商家白拿。
     */
    @Test
    void system1AlreadyPaid_mustStillDebitMerchant() {
        givenPaidRegistrationOnActivity();
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(1));

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper).deductBalance(eq(MERCHANT_ID), eq(new BigDecimal("90.00")));
    }

    /** H8 取消 creator 现金后，is_settlement=1 不能被误当成 creator 收款凭据。 */
    @Test
    void h8ActivitySettledWithoutCreatorCash_mustNotDebitCreator() {
        givenPaidRegistrationOnActivity();
        SettlementDetail creator = new SettlementDetail();
        creator.setMerchantId(MERCHANT_ID);
        creator.setReceiverType("creator");
        creator.setReceiverId(CREATOR_ID);
        creator.setGrossAmount(new BigDecimal("10.00"));
        creator.setCommissionAmount(BigDecimal.ZERO);
        creator.setSettlementAmount(new BigDecimal("10.00"));
        creator.setStatus(1);
        when(settlementDetailMapper.selectSettledDetails("registration", REGISTRATION_ID))
                .thenReturn(Collections.singletonList(creator));
        UmsMember creatorMember = new UmsMember();
        creatorMember.setBalance(new BigDecimal("10.00"));
        when(umsMemberMapper.selectUmsMemberById(CREATOR_ID)).thenReturn(creatorMember);
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(1));
        when(umsMemberBalanceDetailMapper.selectIncomeByEventTypeAndEventId(CREATOR_ID, 6L, ACTIVITY_ID))
                .thenReturn(BigDecimal.ZERO);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(CREATOR_ID), any());
    }

    @Test
    void activityCreatorIncomeEvidenceIsPrefetchedOnceForAllSettlementLines() {
        givenPaidRegistrationOnActivity();
        SettlementDetail first = creatorDetail(1L);
        SettlementDetail second = creatorDetail(2L);
        when(settlementDetailMapper.selectSettledDetails("registration", REGISTRATION_ID))
                .thenReturn(Arrays.asList(first, second));
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(1));
        when(umsMemberBalanceDetailMapper.selectIncomeByEventTypeAndEventId(CREATOR_ID, 6L, ACTIVITY_ID))
                .thenReturn(new BigDecimal("20.00"));
        UmsMember creator = new UmsMember();
        creator.setBalance(new BigDecimal("20.00"));
        when(umsMemberMapper.selectUmsMemberById(CREATOR_ID)).thenReturn(creator);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberBalanceDetailMapper, times(1))
                .selectIncomeByEventTypeAndEventId(CREATOR_ID, 6L, ACTIVITY_ID);
    }

    private SettlementDetail creatorDetail(Long id) {
        SettlementDetail detail = new SettlementDetail();
        detail.setId(id);
        detail.setMerchantId(MERCHANT_ID);
        detail.setReceiverType("creator");
        detail.setReceiverId(CREATOR_ID);
        detail.setGrossAmount(new BigDecimal("10.00"));
        detail.setCommissionAmount(BigDecimal.ZERO);
        detail.setSettlementAmount(new BigDecimal("10.00"));
        detail.setStatus(1);
        return detail;
    }

    /**
     * A1真实漏冲路径:系统1已经打款,但账本仍是支付时留下的 status=0。
     * 退款必须把这条历史明细当作冲销依据,不能被 select status=1 的旧门挡在外面。
     */
    @Test
    void system1AlreadyPaid_withPendingLedgerOnly_mustDebitMerchant() {
        givenPaidRegistrationOnActivity();
        givenPendingLedgerOnly();
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(1));

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper).deductBalance(eq(MERCHANT_ID), eq(new BigDecimal("90.00")));
        verify(settlementDetailMapper).voidPendingBySource("registration", REGISTRATION_ID);
    }

    /** 未真实打款的 status=0 只应作废,不得生成已结算冲销或扣余额。 */
    @Test
    void system1NeverPaid_withPendingLedgerOnly_mustOnlyVoidPending() {
        givenPaidRegistrationOnActivity();
        givenPendingLedgerOnly();
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(0));

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(MERCHANT_ID), any());
        verify(settlementDetailMapper, never()).insertAdjustment(any());
        verify(settlementDetailMapper).voidPendingBySource("registration", REGISTRATION_ID);
    }

    /**
     * 冲销台账与余额是两回事:系统1 没打款时余额不动,但负向冲销明细照落
     * —— 否则 settlement_detail 这本账会停在「已结算 90」,对账永远平不了。
     */
    @Test
    void system1NeverPaid_stillWritesReversalLedger() {
        givenPaidRegistrationOnActivity();
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(0));

        svc.reviewRefund(approve(), 42L);

        ArgumentCaptor<SettlementDetail> cap = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(settlementDetailMapper).insertAdjustment(cap.capture());
        assertEquals(0, new BigDecimal("-90.00").compareTo(cap.getValue().getSettlementAmount()),
                "余额可以不动,但冲销台账必须照落,否则账面停在「已结算90」永远平不了");
    }
}
