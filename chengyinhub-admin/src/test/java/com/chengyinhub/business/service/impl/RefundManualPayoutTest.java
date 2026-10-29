package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §6.1 退款失败人工兜底状态机单测:
 * 原路受理成功→PROCESSING(0);受理失败→PENDING_MANUAL(2,不转余额);
 * 人工处理 2→3;双人复核 3→4 且复核人须≠处理人。
 */
class RefundManualPayoutTest {

    /** [R6] 回执 URL 有服务端安全合同(站内单斜杠路径/白名单 HTTPS),裸字符串已不再是合法入参。 */
    private static final String RECEIPT = "/profile/upload/2026/07/25/refund-receipt.png";

    private RefundApplicationMapper refundApplicationMapper;
    private INotifyService notifyService;
    private AlarmService alarmService;
    private RefundServiceImpl svc;
    private WechatVerifiedPaymentFactService factsReader;

    @BeforeEach
    void setUp() {
        refundApplicationMapper = mock(RefundApplicationMapper.class);
        notifyService = mock(INotifyService.class);
        alarmService = mock(AlarmService.class);
        svc = new RefundServiceImpl();
        var originalOwner = mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class);
        when(originalOwner.hasFrozenOriginalRefundComponents(anyLong())).thenReturn(false);
        ReflectionTestUtils.setField(svc, "coopSettlementClaimService", originalOwner);
        ReflectionTestUtils.setField(svc, "refundApplicationMapper", refundApplicationMapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(88L); registration.setOwnerType(1L); registration.setOwnerId(7L);
        registration.setMemberId(9L); registration.setTransactionId("TX88");
        registration.setPaymentStatus(4); registration.setRegistrationStatus(3);
        registration.setRegistrationNo("REG88");
        registration.setWechatPaymentAmount(new BigDecimal("30.00"));
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        when(registrations.selectCmsRegistrationById(88L)).thenReturn(registration);
        when(registrations.selectCmsRegistrationByIdForUpdate(88L)).thenReturn(registration);
        var topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        var topic = new com.chengyinhub.business.domain.CmsTopic(); topic.setId(7L); topic.setMemberId(3L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topics);
        factsReader = com.chengyinhub.business.service.ARefundFactFixture.memoryFacts();
        ReflectionTestUtils.setField(svc, "verifiedPaymentFacts", factsReader);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(svc, "notifyService", notifyService);
        ReflectionTestUtils.setField(svc, "alarmService", alarmService);
        // 2026-09-16 补合 paycb:退款回调先落 inbox 去重(cc36b4552),返回值不参与状态推进;
        // inbox 自身语义由 WxPaymentCallbackInboxServiceImplTest 覆盖,这里桩掉,不注入即 NPE。
        ReflectionTestUtils.setField(svc, "wxPaymentCallbackInboxService",
                com.chengyinhub.business.service.PaymentCallbackInboxStubs.passThrough(null, factsReader));
    }

    @Test
    void payoutAccepted_marksProcessing() {
        when(refundApplicationMapper.updatePayoutResultWithLease(eq(7L), eq("lease"),
                eq(RefundApplication.PAYOUT_PROCESSING), any())).thenReturn(1);
        svc.markPayoutResult(7L, "lease", true, null);
        verify(refundApplicationMapper).updatePayoutResultWithLease(eq(7L), eq("lease"),
                eq(RefundApplication.PAYOUT_PROCESSING), any());
    }

    @Test
    void payoutRejected_marksPendingManual_noBalance() {
        when(refundApplicationMapper.updatePayoutResultWithLease(eq(7L), eq("lease"),
                eq(RefundApplication.PAYOUT_PENDING_MANUAL), any())).thenReturn(1);
        svc.markPayoutResult(7L, "lease", false, "微信拒绝");
        verify(refundApplicationMapper).updatePayoutResultWithLease(eq(7L), eq("lease"),
                eq(RefundApplication.PAYOUT_PENDING_MANUAL), eq("微信拒绝"));
    }

    @Test
    void payoutResultZeroRowsRejectsDifferentDurableState() {
        RefundApplication current = new RefundApplication();
        current.setId(7L);
        current.setPayoutStatus(RefundApplication.PAYOUT_DISPATCHING);
        when(refundApplicationMapper.updatePayoutResultWithLease(any(), any(), any(), any())).thenReturn(0);
        when(refundApplicationMapper.selectRefundApplicationById(7L)).thenReturn(current);

        assertThrows(IllegalStateException.class,
                () -> svc.markPayoutResult(7L, "stale-lease", true, null));
    }

    @Test
    void handleManualPayout_requiresMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.handleManualPayout(7L, "  ", RECEIPT, "备注", 100L, "lease"));
        verify(refundApplicationMapper, never()).completeManualPayout(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void handleManualPayout_ok_marksHandled() {
        when(refundApplicationMapper.completeManualPayout(eq(7L), eq("offline_transfer"), eq(RECEIPT),
                eq(100L), any(), eq("已线下退"), eq("lease")))
                .thenReturn(1);
        int rows = svc.handleManualPayout(7L, "offline_transfer", RECEIPT, "已线下退", 100L, "lease");
        assertEquals(1, rows);
    }

    @Test
    void twoOperatorsCannotOwnTheSameManualPayout() {
        when(refundApplicationMapper.claimManualPayout(eq(7L), any(), any(), anyLong()))
                .thenReturn(1, 0);

        assertNotNull(svc.claimManualPayout(7L, 100L));
        assertNull(svc.claimManualPayout(7L, 200L));
    }

    @Test
    void lostCompletionResponseReadsBackTheSameFenceAsSuccess() {
        RefundApplication completed = new RefundApplication();
        completed.setId(7L);
        completed.setPayoutStatus(RefundApplication.PAYOUT_MANUAL_HANDLED);
        completed.setPayoutHandlerId(100L);
        completed.setPayoutLeaseToken("lease");
        completed.setPayoutMethod("offline_transfer");
        completed.setPayoutReceiptUrl(RECEIPT);
        when(refundApplicationMapper.completeManualPayout(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(refundApplicationMapper.selectRefundApplicationById(7L)).thenReturn(completed);

        assertEquals(1, svc.handleManualPayout(
                7L, "offline_transfer", RECEIPT, "已线下退", 100L, "lease"));
    }

    @Test
    void verifyManualPayout_sameAsHandler_rejected() {
        RefundApplication r = new RefundApplication();
        r.setId(7L);
        r.setRefundNo("RF88"); r.setSourceType("registration"); r.setSourceId(88L); r.setStatus(1);
        r.setRefundAmount(new BigDecimal("12.50")); r.setPayoutStatus(3);
        r.setPayoutMethod("offline_transfer"); r.setPayoutReceiptUrl(RECEIPT); r.setPayoutHandledAt(new java.util.Date());
        when(refundApplicationMapper.selectRegistrationHistoryForUpdate(88L)).thenReturn(java.util.List.of(r));
        r.setPayoutHandlerId(100L);
        when(refundApplicationMapper.selectRefundApplicationById(7L)).thenReturn(r);

        // 复核人 = 处理人 → 双人复核拦截
        assertThrows(IllegalStateException.class, () -> svc.verifyManualPayout(7L, 100L));
        verify(refundApplicationMapper, never()).markPayoutVerified(any(), any(), any());
    }

    @Test
    void verifyManualPayout_differentReviewer_verifiesAndNotifies() {
        RefundApplication r = new RefundApplication();
        r.setId(7L);
        r.setRefundNo("RF88"); r.setSourceType("registration"); r.setSourceId(88L); r.setStatus(1);
        r.setRefundAmount(new BigDecimal("12.50")); r.setPayoutStatus(3);
        r.setPayoutMethod("offline_transfer"); r.setPayoutReceiptUrl(RECEIPT); r.setPayoutHandledAt(new java.util.Date());
        when(refundApplicationMapper.selectRegistrationHistoryForUpdate(88L)).thenReturn(java.util.List.of(r));
        r.setMemberId(9L);
        r.setPayoutHandlerId(100L);
        r.setRefundAmount(new BigDecimal("12.50"));
        when(refundApplicationMapper.selectRefundApplicationById(7L)).thenReturn(r);
        when(refundApplicationMapper.markPayoutVerified(eq(7L), eq(200L), any())).thenReturn(1);

        int rows = svc.verifyManualPayout(7L, 200L);

        assertEquals(1, rows);
        verify(refundApplicationMapper).markPayoutVerified(eq(7L), eq(200L), any());
        verify(notifyService).notifyRefund(eq(9L), any(), eq("12.50"));
    }

    @Test
    void verifyManualPayout_notFound_throws() {
        when(refundApplicationMapper.selectRefundApplicationById(7L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> svc.verifyManualPayout(7L, 200L));
    }

    // ===== P3 退款结果通知回调状态映射 =====

    private RefundApplication byRefundNo(Long id) {
        RefundApplication r = new RefundApplication();
        r.setId(id);
        r.setRefundNo("RF88"); r.setStatus(1);
        r.setSourceType("registration");
        r.setSourceId(88L);
        r.setRefundAmount(new BigDecimal("12.50"));
        when(refundApplicationMapper.selectByRefundNo("RF88")).thenReturn(r);
        when(refundApplicationMapper.selectRegistrationHistoryForUpdate(88L)).thenReturn(java.util.List.of(r));
        when(refundApplicationMapper.updatePayoutResult(eq(id), any(), any())).thenReturn(1);
        return r;
    }

    private Map<String, String> facts(String refundNo, String status) {
        Map<String, String> facts = new HashMap<>();
        facts.put("outRefundNo", refundNo);
        facts.put("outTradeNo", "REG88");
        facts.put("status", status);
        facts.put("refund", "1250");
        facts.put("total", "3000");
        facts.put("currency", "CNY");
        facts.put("mchid", "merchant");
        facts.put("refundId", "WX-" + refundNo);
        return "SUCCESS".equals(status) && "RF88".equals(refundNo)
                ? com.chengyinhub.business.service.ARefundFactFixture.bindSuccess(factsReader, facts, "TX88") : facts;
    }

    @Test
    void notify_success_marksPayoutSuccess() {
        byRefundNo(3L);
        assertTrue(svc.handleRefundNotify(facts("RF88", "SUCCESS")));
        verify(refundApplicationMapper).updatePayoutResult(eq(3L), eq(RefundApplication.PAYOUT_SUCCESS), any());
    }

    @Test
    void notify_abnormal_marksPendingManual() {
        byRefundNo(3L);
        assertTrue(svc.handleRefundNotify(facts("RF88", "ABNORMAL")));
        verify(refundApplicationMapper).updatePayoutResult(eq(3L), eq(RefundApplication.PAYOUT_PENDING_MANUAL), any());
    }

    @Test
    void notify_closed_marksPendingManual() {
        byRefundNo(3L);
        assertTrue(svc.handleRefundNotify(facts("RF88", "CLOSED")));
        verify(refundApplicationMapper).updatePayoutResult(eq(3L), eq(RefundApplication.PAYOUT_PENDING_MANUAL), any());
    }

    @Test
    void notify_processing_noChange() {
        byRefundNo(3L);
        assertTrue(svc.handleRefundNotify(facts("RF88", "PROCESSING")));
        verify(refundApplicationMapper, never()).updatePayoutResult(any(), any(), any());
    }

    @Test
    void notify_zeroAffectedOnlyAcksTheExactPersistedTerminal() {
        RefundApplication initial = new RefundApplication();
        initial.setId(3L);
        initial.setRefundNo("RF88"); initial.setStatus(1);
        initial.setSourceType("registration");
        initial.setSourceId(88L);
        initial.setRefundAmount(new BigDecimal("12.50"));
        RefundApplication exact = new RefundApplication();
        exact.setId(3L);
        exact.setRefundNo("RF88");
        org.springframework.beans.BeanUtils.copyProperties(initial, exact);
        exact.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
        when(refundApplicationMapper.selectByRefundNo("RF88")).thenReturn(initial, exact);
        when(refundApplicationMapper.selectRegistrationHistoryForUpdate(88L)).thenReturn(java.util.List.of(exact));
        when(refundApplicationMapper.updatePayoutResult(eq(3L), eq(RefundApplication.PAYOUT_SUCCESS), any()))
                .thenReturn(0);

        assertTrue(svc.handleRefundNotify(facts("RF88", "SUCCESS")));
    }

    @Test
    void notify_zeroAffectedRejectsDifferentPersistedTerminal() {
        RefundApplication initial = new RefundApplication();
        initial.setId(3L);
        initial.setRefundNo("RF88"); initial.setStatus(1);
        initial.setSourceType("registration");
        initial.setSourceId(88L);
        initial.setRefundAmount(new BigDecimal("12.50"));
        RefundApplication different = new RefundApplication();
        different.setId(3L);
        different.setRefundNo("RF88");
        org.springframework.beans.BeanUtils.copyProperties(initial, different);
        different.setPayoutStatus(RefundApplication.PAYOUT_MANUAL_HANDLED);
        when(refundApplicationMapper.selectByRefundNo("RF88")).thenReturn(initial, different);
        when(refundApplicationMapper.selectRegistrationHistoryForUpdate(88L)).thenReturn(java.util.List.of(different));
        when(refundApplicationMapper.updatePayoutResult(eq(3L), eq(RefundApplication.PAYOUT_SUCCESS), any()))
                .thenReturn(0);

        assertEquals(false, svc.handleRefundNotify(facts("RF88", "SUCCESS")));
    }

    @Test
    void persistedSuccessPreventsStaleAbnormalFromOpeningManualPayout() {
        assertPersistedSuccessBlocksFailure("ABNORMAL");
    }

    @Test
    void persistedSuccessPreventsStaleClosedFromOpeningManualPayout() {
        assertPersistedSuccessBlocksFailure("CLOSED");
    }

    private void assertPersistedSuccessBlocksFailure(String status) {
        byRefundNo(3L);
        Map<String, String> success = facts("RF88", "SUCCESS");
        assertEquals(false, svc.handleRefundNotify(facts("RF88", status)));
        verify(refundApplicationMapper, never()).updatePayoutResult(any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(notifyService);
        assertTrue(svc.handleRefundNotify(success));
        verify(refundApplicationMapper).updatePayoutResult(eq(3L), eq(RefundApplication.PAYOUT_SUCCESS), any());
    }

    @Test
    void unavailableSuccessReaderCannotOpenManualPayout() {
        byRefundNo(3L);
        WechatVerifiedPaymentFactService reader = mock(WechatVerifiedPaymentFactService.class);
        when(reader.findRefundSuccess("merchant", "RF88", "WX-RF88"))
                .thenThrow(new IllegalStateException("fixture reader unavailable"));
        ReflectionTestUtils.setField(svc, "verifiedPaymentFacts", reader);
        assertEquals(false, svc.handleRefundNotify(facts("RF88", "ABNORMAL")));
        verify(refundApplicationMapper, never()).updatePayoutResult(any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(notifyService);
    }

    @Test
    void missingOriginalRefundProviderIdentityCannotOpenManualPayout() {
        byRefundNo(3L);
        Map<String, String> incomplete = facts("RF88", "CLOSED");
        incomplete.remove("refundId");
        assertEquals(false, svc.handleRefundNotify(incomplete));
        verify(refundApplicationMapper, never()).updatePayoutResult(any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(notifyService);
    }

    @Test
    void notify_ownRefundNoNotFoundYet_returnsFalseForRetry() {
        // "RF"开头=本系统号但查不到(外层批量事务尚未提交)→回 false 让微信重试,防丢失败通知
        when(refundApplicationMapper.selectByRefundNo("RF99")).thenReturn(null);
        assertEquals(false, svc.handleRefundNotify(facts("RF99", "ABNORMAL")));
        verify(refundApplicationMapper, never()).updatePayoutResult(any(), any(), any());
    }

    @Test
    void notify_foreignRefundNo_acks() {
        // 非"RF"号(coop 保证金等)→ ACK,不重试
        when(refundApplicationMapper.selectByRefundNo("DEP5")).thenReturn(null);
        assertTrue(svc.handleRefundNotify(facts("DEP5", "SUCCESS")));
        verify(refundApplicationMapper, never()).updatePayoutResult(any(), any(), any());
    }

    @Test
    void notify_nullRefundNo_acks() {
        assertTrue(svc.handleRefundNotify(facts(null, "SUCCESS")));
        verify(refundApplicationMapper, never()).selectByRefundNo(any());
    }
}
