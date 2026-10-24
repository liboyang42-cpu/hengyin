package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.Amount;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Both stable WeChat entry points exercise the real registration-refund handler; no provider request is sent. */
class RefundVerifiedFactsTest {
    private WechatPayServiceImpl wechat;
    private RefundServiceImpl business;
    private RefundApplicationMapper refunds;
    private CmsRegistrationMapper registrations;
    private IWxPaymentCallbackInboxService inbox;
    private INotifyService notifications;
    private NotificationParser parser;
    private RefundService sdk;
    private RefundApplication refund;
    private CmsRegistration registration;

    @BeforeEach void setup() {
        refunds = mock(RefundApplicationMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        inbox = mock(IWxPaymentCallbackInboxService.class);
        notifications = mock(INotifyService.class);
        parser = mock(NotificationParser.class);
        sdk = mock(RefundService.class);
        refund = new RefundApplication();
        refund.setId(7L); refund.setStatus(1);
        refund.setRefundNo("RF11");
        refund.setSourceType("registration");
        refund.setSourceId(11L);
        refund.setRefundAmount(new BigDecimal("12.50"));
        refund.setPaidAmount(new BigDecimal("99.00"));
        refund.setMemberId(91L);
        refund.setPayoutStatus(RefundApplication.PAYOUT_PROCESSING);
        registration = new CmsRegistration();
        registration.setId(11L);
        registration.setRegistrationNo("REG11"); registration.setTransactionId("TX-original");
        registration.setOwnerType(1L); registration.setOwnerId(7L); registration.setMemberId(91L);
        registration.setPaymentStatus(4); registration.setRegistrationStatus(3);
        registration.setWechatPaymentAmount(new BigDecimal("30.00"));
        when(refunds.selectByRefundNo("RF11")).thenReturn(refund);
        when(refunds.updatePayoutResult(eq(7L), any(), any())).thenReturn(1);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(registration);
        when(registrations.selectCmsRegistrationByIdForUpdate(11L)).thenReturn(registration);
        when(refunds.selectRegistrationHistoryForUpdate(11L)).thenAnswer(call -> java.util.List.of(refund));
        business = new RefundServiceImpl();
        WechatVerifiedFactTestSupport.setField(business, "refundApplicationMapper", refunds);
        WechatVerifiedFactTestSupport.setField(business, "cmsRegistrationMapper", registrations);
        WechatVerifiedFactTestSupport.setField(business, "wxPaymentCallbackInboxService", inbox);
        WechatVerifiedFactTestSupport.setField(business, "notifyService", notifications);
        WechatVerifiedFactTestSupport.setField(business, "alarmService", mock(AlarmService.class));
        wechat = WechatVerifiedFactTestSupport.gateway();
        WechatPaymentIntentTestSupport.install(wechat, Map.of());
        var config = mock(com.chengyinhub.common.config.WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        WechatVerifiedFactTestSupport.setField(wechat, "wxPayConfig", config);
        WechatVerifiedFactTestSupport.setField(wechat, "refundBizService", business);
        WechatVerifiedFactTestSupport.setField(wechat, "notificationParser", parser);
        WechatVerifiedFactTestSupport.setField(wechat, "refundService", sdk);
        var paymentSdk = mock(com.wechat.pay.java.service.payments.jsapi.JsapiService.class);
        var original = new com.wechat.pay.java.service.payments.model.Transaction();
        original.setOutTradeNo("REG11"); original.setMchid("merchant"); original.setAppid("wx-original");
        original.setTransactionId("TX-original"); original.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
        original.setTradeType(com.wechat.pay.java.service.payments.model.Transaction.TradeTypeEnum.JSAPI);
        original.setTradeState(com.wechat.pay.java.service.payments.model.Transaction.TradeStateEnum.SUCCESS);
        var paid = new com.wechat.pay.java.service.payments.model.TransactionAmount(); paid.setTotal(3000); paid.setCurrency("CNY");
        original.setAmount(paid);
        when(paymentSdk.queryOrderByOutTradeNo(any())).thenReturn(original);
        WechatVerifiedFactTestSupport.setField(wechat,"jsapiService",paymentSdk);
        WechatVerifiedFactTestSupport.setField(wechat, "alarmService", mock(AlarmService.class));
        var reader = (WechatVerifiedPaymentFactService) WechatVerifiedFactTestSupport.getField(wechat, "verifiedFacts");
        WechatVerifiedFactTestSupport.setField(business, "verifiedPaymentFacts", reader);
        inbox = com.chengyinhub.business.service.PaymentCallbackInboxStubs.passThrough(null, reader);
        WechatVerifiedFactTestSupport.setField(business, "wxPaymentCallbackInboxService", inbox);
        var topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        var topic = new com.chengyinhub.business.domain.CmsTopic(); topic.setId(7L); topic.setMemberId(3L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        WechatVerifiedFactTestSupport.setField(business, "cmsTopicMapper", topics);
        var originalOwner = mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class);
        when(originalOwner.hasFrozenOriginalRefundComponents(anyLong())).thenReturn(false);
        WechatVerifiedFactTestSupport.setField(business, "coopSettlementClaimService", originalOwner);
    }

    @Test void verifiedCallbackWrongRefundAmountIsRejectedBeforeAnyBusinessWrite() {
        Map<String, String> facts = facts("SUCCESS");
        facts.put("refund", "1251");
        assertRejected("callback", facts);
    }

    static Stream<Arguments> invalidFacts() {
        return Stream.of(
                Arguments.of("outTradeNo", "OTHER"), Arguments.of("outTradeNo", ""),
                Arguments.of("outTradeNo", null), Arguments.of("refund", "1251"),
                Arguments.of("refund", "0"), Arguments.of("refund", "-1"),
                Arguments.of("refund", null), Arguments.of("total", "3001"),
                Arguments.of("total", "0"), Arguments.of("total", null),
                Arguments.of("currency", "USD"), Arguments.of("currency", ""),
                Arguments.of("currency", null));
    }

    @ParameterizedTest @MethodSource("invalidFacts")
    void verifiedCallbackRejectsInconsistentOrMissingFacts(String key, String value) {
        Map<String, String> facts = facts("SUCCESS");
        facts.put(key, value);
        assertRejected("callback", facts);
    }

    @ParameterizedTest @MethodSource("invalidFacts")
    void activeQueryRejectsInconsistentOrMissingFacts(String key, String value) {
        Map<String, String> facts = facts("SUCCESS");
        facts.put(key, value);
        assertRejected("query", facts);
    }

    @Test void missingEntireAmountIsRejectedByBothEntryPoints() {
        Map<String, String> facts = facts("SUCCESS");
        facts.remove("refund"); facts.remove("total"); facts.remove("currency");
        assertRejected("callback", facts);
        assertRejected("query", facts);
    }

    @Test void missingOrBlankLocalOrderNumberCannotMatchAnEmptyProviderOrder() {
        for (String local : new String[]{null, "", "  "}) {
            registration.setRegistrationNo(local);
            Map<String, String> facts = facts("SUCCESS");
            facts.put("outTradeNo", local);
            assertRejected("callback", facts);
            assertRejected("query", facts);
        }
    }

    @Test void localMoneyCannotBeRoundedOverflowedOrExceedCashPaid() {
        for (String amount : new String[]{"12.501", "92233720368547758.08", "0", "-1", "30.01"}) {
            refund.setRefundAmount(new BigDecimal(amount));
            Map<String, String> facts = facts("SUCCESS");
            if ("30.01".equals(amount)) facts.put("refund", "3001");
            if ("0".equals(amount)) facts.put("refund", "0");
            if ("-1".equals(amount)) facts.put("refund", "-100");
            assertRejected("callback", facts);
            assertRejected("query", facts);
        }
        refund.setRefundAmount(null);
        assertRejected("callback", facts("SUCCESS"));
        assertRejected("query", facts("SUCCESS"));
    }

    @Test void missingInvalidCashPaymentOrRegistrationCannotAdvanceEitherEntryPoint() {
        for (BigDecimal paid : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("30.001"),
                new BigDecimal("92233720368547758.08")}) {
            registration.setWechatPaymentAmount(paid);
            assertRejected("callback", facts("SUCCESS"));
            assertRejected("query", facts("SUCCESS"));
        }
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(null);
        assertRejected("callback", facts("SUCCESS"));
        assertRejected("query", facts("SUCCESS"));
    }

    @Test void queryRejectsAResponseForAnotherRefundEvenWhenOtherFactsMatch() {
        Map<String, String> facts = facts("SUCCESS");
        facts.put("outRefundNo", "OTHER");
        assertRejected("query", facts);
        verify(refunds, never()).selectByRefundNo(anyString());
    }

    @Test void partialRefundWithExactCashFactsCanSucceedThroughBothEntryPoints() {
        assertTrue(callback(facts("SUCCESS")).contains("SUCCESS"));
        assertEquals("SUCCESS", query(facts("SUCCESS")));
        verify(inbox, times(2)).register("REFUND:RF11:SUCCESS", 2, 7L, "SUCCESS");
        verify(inbox, times(2)).registerVerified(eq("REFUND:RF11:SUCCESS"), eq(2), eq(7L), eq("SUCCESS"), any());
        verify(refunds, times(2)).updatePayoutResult(eq(7L), eq(RefundApplication.PAYOUT_SUCCESS), anyString());
        verify(notifications, times(2)).notifyRefund(eq(91L), eq("报名退款"), eq("12.50"));
    }

    @Test void processingAndFailureStatesNeverBecomeSuccessfulCashRefunds() {
        for (String status : new String[]{"PROCESSING", "ABNORMAL", "CLOSED"}) {
            assertTrue(callback(facts(status)).contains("SUCCESS"));
            assertEquals(status, query(facts(status)));
        }
        verify(refunds, never()).updatePayoutResult(anyLong(), eq(RefundApplication.PAYOUT_SUCCESS), anyString());
        verify(refunds, times(4)).updatePayoutResult(eq(7L), eq(RefundApplication.PAYOUT_PENDING_MANUAL), anyString());
        verifyNoInteractions(notifications);
    }

    @Test void alreadyPersistedSuccessAcknowledgesWithoutRepeatingCashNotice() {
        refund.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
        when(refunds.updatePayoutResult(eq(7L), any(), any())).thenReturn(0);
        assertTrue(callback(facts("SUCCESS")).contains("SUCCESS"));
        assertEquals("SUCCESS", query(facts("SUCCESS")));
        verifyNoInteractions(notifications);
    }

    @Test void manualTerminalCannotBeOverwrittenOrFalselyReportedAsSuccessfulQuery() {
        for (int terminal : new int[]{RefundApplication.PAYOUT_MANUAL_HANDLED, RefundApplication.PAYOUT_MANUAL_VERIFIED}) {
            refund.setPayoutStatus(terminal);
            when(refunds.updatePayoutResult(eq(7L), any(), any())).thenReturn(0);
            assertTrue(callback(facts("SUCCESS")).contains("FAIL"));
            assertNull(query(facts("SUCCESS")));
        }
        verifyNoInteractions(notifications);
    }

    @Test void unknownOwnedRefundRemainsRetryableButUnrelatedRefundStillAcknowledges() {
        Map<String, String> owned = facts("SUCCESS");
        owned.put("outRefundNo", "RF99");
        assertRejected("callback", owned);
        assertRejected("query", owned);
        Map<String, String> unrelated = facts("SUCCESS");
        unrelated.put("outRefundNo", "OTHER");
        assertTrue(callback(unrelated).contains("SUCCESS"));
        assertEquals("SUCCESS", queryFor("OTHER", unrelated));
        verifyNoInteractions(inbox, notifications);
    }

    private void assertRejected(String entry, Map<String, String> facts) {
        if ("callback".equals(entry)) assertTrue(callback(facts).contains("FAIL"));
        else assertNull(query(facts));
        verifyNoInteractions(inbox, notifications);
        verify(refunds, never()).updatePayoutResult(anyLong(), any(), any());
    }

    private String callback(Map<String, String> facts) {
        RefundNotification notification = mock(RefundNotification.class);
        when(notification.getOutRefundNo()).thenReturn(facts.get("outRefundNo"));
        when(notification.getOutTradeNo()).thenReturn(facts.get("outTradeNo"));
        when(notification.getRefundStatus()).thenReturn(Status.valueOf(facts.get("status")));
        Amount amount = amount(facts);
        when(notification.getAmount()).thenReturn(amount);
        when(notification.getRefundId()).thenReturn("WX-"+facts.get("outRefundNo"));
        when(notification.getTransactionId()).thenReturn("TX-original");
        when(notification.getSuccessTime()).thenReturn(WechatVerifiedFactTestSupport.SUCCESS_TIME);
        providerRefund(facts);
        when(parser.parse(any(RequestParam.class), eq(RefundNotification.class))).thenReturn(notification);
        return wechat.handleRefundCallback("verified-sdk-fixture", Map.of("wechatpay-serial", "SERIAL",
                "wechatpay-nonce", "NONCE", "wechatpay-signature", "SIGNATURE", "wechatpay-timestamp", "1"));
    }

    private String query(Map<String, String> facts) { return queryFor("RF11", facts); }

    private String queryFor(String requestedRefund, Map<String, String> facts) {
        providerRefund(facts);
        return wechat.refreshPayoutFromWx(requestedRefund);
    }
    private void providerRefund(Map<String,String> facts) {
        Refund result = mock(Refund.class);
        when(result.getOutRefundNo()).thenReturn(facts.get("outRefundNo"));
        when(result.getOutTradeNo()).thenReturn(facts.get("outTradeNo"));
        when(result.getStatus()).thenReturn(Status.valueOf(facts.get("status")));
        Amount refundAmount = amount(facts);
        when(result.getAmount()).thenReturn(refundAmount);
        when(result.getRefundId()).thenReturn("WX-"+facts.get("outRefundNo"));
        when(result.getTransactionId()).thenReturn("TX-original");
        when(result.getSuccessTime()).thenReturn(WechatVerifiedFactTestSupport.SUCCESS_TIME);
        when(sdk.queryByOutRefundNo(any(QueryByOutRefundNoRequest.class))).thenReturn(result);
    }

    private static Amount amount(Map<String, String> facts) {
        if (!facts.containsKey("refund") && !facts.containsKey("total") && !facts.containsKey("currency")) return null;
        Amount amount = mock(Amount.class);
        when(amount.getRefund()).thenReturn(facts.get("refund") == null ? null : Long.valueOf(facts.get("refund")));
        when(amount.getTotal()).thenReturn(facts.get("total") == null ? null : Long.valueOf(facts.get("total")));
        when(amount.getCurrency()).thenReturn(facts.get("currency"));
        return amount;
    }

    private static Map<String, String> facts(String status) {
        return new HashMap<>(Map.of("outRefundNo", "RF11", "outTradeNo", "REG11", "status", status,
                "refund", "1250", "total", "3000", "currency", "CNY"));
    }
}
