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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-B 多方分账退款漏冲回归。
 * 场景:实付100,已结算拆 商家90(receiver=merchant,id=5)+ 平台10(receiver=platform,id=0),均 status=1。
 * 玩家全额退款100。旧 selectSettledDetail 的 `order by id desc limit 1` 只取最大 id 一条(平台,inserted 后=id 大),
 * 平台 receiverId=0 → debitReceiverBalance no-op → 商家 90 永不回扣 = 资损。
 * 修复后逐条冲销:商家余额必须扣回 -90.00,且两条明细各生成一条负向冲销。
 */
class RefundMultiPartyClawbackTest {

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

    private SettlementDetail detail(String receiverType, Long receiverId, String amount) {
        SettlementDetail s = new SettlementDetail();
        s.setMerchantId(5L);
        s.setReceiverType(receiverType);
        s.setReceiverId(receiverId);
        s.setGrossAmount(new BigDecimal(amount));
        s.setCommissionAmount(BigDecimal.ZERO);
        s.setSettlementAmount(new BigDecimal(amount));
        s.setStatus(1);
        return s;
    }

    @Test
    void multiPartyRefund_clawsBackMerchant_notJustNewestLeg() {
        RefundReviewDTO req = new RefundReviewDTO();
        req.setId(1L);
        req.setStatus(1); // approved

        RefundApplication refund = new RefundApplication();
        refund.setId(1L);
        refund.setSourceType("registration");
        refund.setSourceId(10L);
        refund.setRefundAmount(new BigDecimal("100"));
        refund.setPaidAmount(new BigDecimal("100")); // 全额退款 → ratio=1
        when(refundApplicationMapper.selectRefundApplicationById(1L)).thenReturn(refund);

        CmsRegistration reg = new CmsRegistration();
        reg.setPaymentStatus(2);
        reg.setPayableAmount(new BigDecimal("100"));
        reg.setId(10L);
        reg.setOwnerType(2L);   // 活动报名
        reg.setOwnerId(77L);
        when(cmsRegistrationMapper.selectCmsRegistrationById(10L)).thenReturn(reg);
        CmsActivity activity = new CmsActivity();
        activity.setId(77L);
        activity.setIsSettlement(1);   // [P0-1] 系统1 已打款 ⇒ 该扣,本类才测得了「多方逐条冲销」
        when(cmsActivityMapper.selectCmsActivityById(77L)).thenReturn(activity);
        when(refundApplicationMapper.updateRefundStatusIfPending(anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(1);

        SettlementDetail merchant = detail("merchant", 5L, "90.00");
        SettlementDetail platform = detail("platform", 0L, "10.00");
        // 旧路径证据:limit-1 只拿平台(receiverId=0),商家漏冲
        when(settlementDetailMapper.selectSettledDetail("registration", 10L)).thenReturn(platform);
        // 修复路径:返回全部已结算明细,逐条冲销
        when(settlementDetailMapper.selectSettledDetails("registration", 10L))
                .thenReturn(Arrays.asList(merchant, platform));
        when(settlementDetailMapper.insertAdjustment(any())).thenReturn(1);

        UmsMember m = new UmsMember();
        m.setBalance(new BigDecimal("90.00"));
        when(umsMemberMapper.selectUmsMemberById(5L)).thenReturn(m);

        svc.reviewRefund(req, 42L);

        // 资损核心断言:商家分成 90 必须被原子扣回。
        verify(umsMemberMapper).deductBalance(eq(5L), eq(new BigDecimal("90.00")));

        // 每条已结算明细各生成一条负向冲销(商家 -90 + 平台 -10)
        ArgumentCaptor<SettlementDetail> cap = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(settlementDetailMapper, times(2)).insertAdjustment(cap.capture());
        BigDecimal totalReversed = cap.getAllValues().stream()
                .map(SettlementDetail::getSettlementAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("-100.00").compareTo(totalReversed),
                "两条冲销合计应 = -100.00(商家-90 + 平台-10),实际 " + totalReversed);
    }
}
