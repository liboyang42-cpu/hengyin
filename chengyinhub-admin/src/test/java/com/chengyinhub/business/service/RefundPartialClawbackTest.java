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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HF-04(A-32) 部分退款按比例冲销单测。
 * 场景:实付100、已结算给商家(receiver=merchant) settlement=90,退款20(20%)。
 * 断言:冲销明细 settlementAmount = -18.00(90×0.2),商家余额扣回 18.00 —— 而非整单 90。
 */
class RefundPartialClawbackTest {

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
        when(cmsRegistrationMapper.claimRegistrationRefunded(anyLong(), any(), any())).thenReturn(1);
        // [P0-1] 本类断言的是「扣多少」,前提是**该扣** = 系统1 已真打过款(is_settlement=1)。
        //        该前提现在必须显式给,否则冲销闸判「没打过款」→ 一分不扣 → 本类断言全落空。
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(svc, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailMapper", umsMemberBalanceDetailMapper);
        when(umsMemberMapper.lockMemberById(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(umsMemberMapper.deductBalance(anyLong(), any())).thenReturn(1);
        when(umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(any())).thenReturn(1);
    }

    private RefundApplication refund(String amount, String paid) {
        RefundApplication r = new RefundApplication();
        r.setId(1L);
        r.setSourceType("registration");
        r.setSourceId(10L);
        r.setRefundAmount(new BigDecimal(amount));
        r.setPaidAmount(new BigDecimal(paid));
        return r;
    }

    private SettlementDetail settled() {
        SettlementDetail s = new SettlementDetail();
        s.setMerchantId(5L);
        s.setReceiverType("merchant");
        s.setReceiverId(5L);
        s.setGrossAmount(new BigDecimal("100.00"));
        s.setCommissionAmount(new BigDecimal("10.00"));
        s.setSettlementAmount(new BigDecimal("90.00"));
        return s;
    }

    @Test
    void partialRefund_clawsProportionalNotFull() {
        RefundReviewDTO req = new RefundReviewDTO();
        req.setId(1L);
        req.setStatus(1); // approved

        when(refundApplicationMapper.selectRefundApplicationById(1L)).thenReturn(refund("20", "100"));
        CmsRegistration reg = new CmsRegistration();
        reg.setPaymentStatus(2);
        reg.setPayableAmount(new BigDecimal("100"));
        reg.setId(10L);
        reg.setOwnerType(2L);   // 活动报名
        reg.setOwnerId(77L);
        when(cmsRegistrationMapper.selectCmsRegistrationById(10L)).thenReturn(reg);
        CmsActivity activity = new CmsActivity();
        activity.setId(77L);
        activity.setIsSettlement(1);   // [P0-1] 系统1 已打款 ⇒ 该扣,本类才测得了「扣多少」
        when(cmsActivityMapper.selectCmsActivityById(77L)).thenReturn(activity);
        when(refundApplicationMapper.updateRefundStatusIfPending(anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(1);
        when(settlementDetailMapper.selectSettledDetails("registration", 10L))
                .thenReturn(java.util.Collections.singletonList(settled()));
        when(settlementDetailMapper.insertAdjustment(any())).thenReturn(1);
        UmsMember m = new UmsMember();
        m.setBalance(new BigDecimal("90.00"));
        when(umsMemberMapper.selectUmsMemberById(5L)).thenReturn(m);

        svc.reviewRefund(req, 42L);

        // 冲销明细按 20% 计:settlement 90 → -18.00
        ArgumentCaptor<SettlementDetail> cap = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(settlementDetailMapper).insertAdjustment(cap.capture());
        assertEquals(0, new BigDecimal("-18.00").compareTo(cap.getValue().getSettlementAmount()),
                "部分退款应按比例冲销 settlement,期望 -18.00 实际 " + cap.getValue().getSettlementAmount());
        assertEquals(0, new BigDecimal("-2.00").compareTo(cap.getValue().getCommissionAmount()),
                "commission 应按比例 10×0.2=2 → -2.00");

        // 商家余额原子扣回 18.00。
        verify(umsMemberMapper).deductBalance(eq(5L), eq(new BigDecimal("18.00")));
    }

    @Test
    void refundOverPaid_rejectedAtSave() {
        // saveRefund 夹取:退款 150 > 实付 100 → 拒绝
        CmsRegistration reg = new CmsRegistration();
        reg.setPaymentStatus(2);
        reg.setPayableAmount(new BigDecimal("100"));
        reg.setId(10L);
        reg.setPaymentStatus(2);
        reg.setPayableAmount(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectCmsRegistrationById(10L)).thenReturn(reg);

        RefundApplication over = new RefundApplication();
        over.setSourceType("registration");
        over.setSourceId(10L);
        over.setRefundAmount(new BigDecimal("150"));

        assertThrows(IllegalArgumentException.class, () -> svc.saveRefund(over));
        verify(refundApplicationMapper, org.mockito.Mockito.never()).insertRefundApplication(any());
    }
}
