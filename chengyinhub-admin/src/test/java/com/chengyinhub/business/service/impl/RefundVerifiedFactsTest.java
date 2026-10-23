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
import org.springframework.test.util.ReflectionTestUtils;

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
        refund.setId(7L);
        refund.setRefundNo("RF11");
        refund.setSourceType("registration");
        refund.setSourceId(11L);
        refund.setRefundAmount(new BigDecimal("12.50"));
        refund.setPaidAmount(new BigDecimal("99.00"));
        refund.setMemberId(91L);
        refund.setPayoutStatus(RefundApplication.PAYOUT_PROCESSING);
        registration = new CmsRegistration();
        registration.setId(11L);
        registration.setRegistrationNo("REG11");
        registration.setWechatPaymentAmount(new BigDecimal("30.00"));
        when(refunds.selectByRefundNo("RF11")).thenReturn(refund);
        when(refunds.updatePayoutResult(eq(7L), any(), any())).thenReturn(1);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(registration);
        business = new RefundServiceImpl();
        ReflectionTestUtils.setField(business, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(business, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(business, "wxPaymentCallbackInboxService", inbox);
        ReflectionTestUtils.setField(business, "notifyService", notifications);
        ReflectionTestUtils.setField(business, "alarmService", mock(AlarmService.class));
        wechat = new WechatPayServiceImpl();
        WechatPaymentIntentTestSupport.install(wechat, Map.of());
        var config = mock(com.chengyinhub.common.config.WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        ReflectionTestUtils.setField(wechat, "wxPayConfig", config);
        ReflectionTestUtils.setField(wechat, "refundBizService", business);
        ReflectionTestUtils.setField(wechat, "notificationParser", parser);
        ReflectionTestUtils.setField(wechat, "refundService", sdk);
        ReflectionTestUtils.setField(wechat, "alarmService", mock(AlarmService.class));
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
        when(parser.parse(any(RequestParam.class), eq(RefundNotification.class))).thenReturn(notification);
        return wechat.handleRefundCallback("verified-sdk-fixture", Map.of("wechatpay-serial", "SERIAL",
                "wechatpay-nonce", "NONCE", "wechatpay-signature", "SIGNATURE", "wechatpay-timestamp", "1"));
    }

    private String query(Map<String, String> facts) { return queryFor("RF11", facts); }

    private String queryFor(String requestedRefund, Map<String, String> facts) {
        Refund result = mock(Refund.class);
        when(result.getOutRefundNo()).thenReturn(facts.get("outRefundNo"));
        when(result.getOutTradeNo()).thenReturn(facts.get("outTradeNo"));
        when(result.getStatus()).thenReturn(Status.valueOf(facts.get("status")));
        Amount amount = amount(facts);
        when(result.getAmount()).thenReturn(amount);
        when(sdk.queryByOutRefundNo(any(QueryByOutRefundNoRequest.class))).thenReturn(result);
        return wechat.refreshPayoutFromWx(requestedRefund);
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
