package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.CoopSettlementOriginalMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSettlementGuard;
import com.chengyinhub.business.payout.FakeEcomPayoutMapper;
import com.chengyinhub.business.payout.FakeEcommerceGateway;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** l-payout2：开关打开时 T+14 到期入余额只记平台收款的那部分；由二级商户收款的份额留给微信分账。 */
class EcommerceSettlementWiringTest {
    static final PayoutSwitches ON = new PayoutSwitches(true, true, true, "1900000001", "wx_mini", "", 30, false, "none");

    UmsMemberMapper members;
    CoopSettlementMapper settlements;
    IUmsMemberBalanceDetailService details;
    FakeEcomPayoutMapper payoutMapper = new FakeEcomPayoutMapper();

    CmsTopicServiceImpl service(PayoutSwitches switches) {
        CmsTopicServiceImpl target = new CmsTopicServiceImpl();
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(target, "transactionManager", tm);
        CmsTopic topic = new CmsTopic();
        topic.setId(200L);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(200L)).thenReturn(topic);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        CoopSettlement head = new CoopSettlement();
        head.setId(700L);
        head.setTopicId(200L);
        head.setMerchantId(300L);
        head.setAmount(new BigDecimal("30.00"));
        head.setStatus(0);
        head.setVerifiedHeads(1);
        settlements = mock(CoopSettlementMapper.class);
        when(settlements.selectDuePayouts(any())).thenReturn(List.of(head));
        when(settlements.selectDuePayoutForUpdate(eq(700L), any())).thenReturn(head);
        when(settlements.markPaidIfPending(eq(700L), any())).thenReturn(1);
        ReflectionTestUtils.setField(target, "coopSettlementMapper", settlements);
        CoopSettlementOriginalMapper originals = mock(CoopSettlementOriginalMapper.class);
        when(originals.selectTopicHeadsForUpdate(200L)).thenReturn(List.of(head));
        when(originals.selectAdjustmentsForSettlementForUpdate(700L)).thenReturn(List.of());
        ReflectionTestUtils.setField(target, "coopSettlementOriginalMapper", originals);
        CoopSettlementAdjustmentMapper adjustments = mock(CoopSettlementAdjustmentMapper.class);
        when(adjustments.selectExecutedBySettlementForUpdate(700L)).thenReturn(List.of());
        ReflectionTestUtils.setField(target, "coopSettlementAdjustmentMapper", adjustments);
        ReflectionTestUtils.setField(target, "coopComplaintMapper", mock(CoopComplaintMapper.class));
        members = mock(UmsMemberMapper.class);
        UmsMember before = new UmsMember();
        before.setId(300L);
        before.setBalance(new BigDecimal("100.00"));
        UmsMember after = new UmsMember();
        after.setId(300L);
        after.setBalance(new BigDecimal("130.00"));
        when(members.lockMemberById(300L)).thenReturn(300L);
        when(members.selectUmsMemberById(300L)).thenReturn(before, after);
        when(members.addBalance(eq(300L), any())).thenReturn(1);
        ReflectionTestUtils.setField(target, "umsMemberMapper", members);
        details = mock(IUmsMemberBalanceDetailService.class);
        when(details.insertUmsMemberBalanceDetail(any())).thenReturn(1);
        ReflectionTestUtils.setField(target, "umsMemberBalanceDetailService", details);
        ReflectionTestUtils.setField(target, "settlementDebtLedger",
                mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        ReflectionTestUtils.setField(target, "notifyService", mock(INotifyService.class));
        EcommerceProfitShareService sharing = new EcommerceProfitShareService(payoutMapper, new FakeEcommerceGateway(), switches,
                TransactionOperations.withoutTransaction());
        ReflectionTestUtils.setField(target, "ecommerceSettlementGuard", new EcommerceSettlementGuard(switches, payoutMapper, sharing));
        return target;
    }

    @Test
    void mixedHeadCreditsOnlyThePlatformCollectedPart() {
        payoutMapper.partnerPortion = new BigDecimal("10.00");
        assertEquals(1, service(ON).payoutDueCoopSettlements());
        ArgumentCaptor<BigDecimal> credited = ArgumentCaptor.forClass(BigDecimal.class);
        verify(members).addBalance(eq(300L), credited.capture());
        assertEquals(new BigDecimal("20.00"), credited.getValue());
        verify(settlements).markPaidIfPending(eq(700L), any(Date.class));
        assertEquals(List.of("partnerPortion:700"), payoutMapper.calls);
    }

    @Test
    void headFullyCollectedBySubMerchantIsClosedWithoutAnyBalanceCredit() {
        payoutMapper.partnerPortion = new BigDecimal("30.00");
        assertEquals(0, service(ON).payoutDueCoopSettlements());
        verify(members, never()).addBalance(anyLong(), any());
        verify(details, never()).insertUmsMemberBalanceDetail(any());
        verify(settlements).markPaidIfPending(eq(700L), any(Date.class));
    }

    @Test
    void partnerPortionAboveHeadNetFailsClosedWithoutCredit() {
        payoutMapper.partnerPortion = new BigDecimal("30.01");
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> service(ON).payoutDueCoopSettlements());
        assertEquals(true, failed.getMessage().contains("成功 0 笔"), failed.getMessage());
        verify(members, never()).addBalance(anyLong(), any());
        verify(settlements, never()).markPaidIfPending(anyLong(), any());
    }

    @Test
    void switchOffReadsNoPartnerPortion() {
        payoutMapper.partnerPortion = new BigDecimal("10.00");
        assertEquals(1, service(PayoutSwitches.allOff()).payoutDueCoopSettlements());
        ArgumentCaptor<BigDecimal> credited = ArgumentCaptor.forClass(BigDecimal.class);
        verify(members).addBalance(eq(300L), credited.capture());
        assertEquals(new BigDecimal("30.00"), credited.getValue());
        assertEquals(List.of(), payoutMapper.calls);
    }
}
