package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.wechat.pay.java.service.refund.model.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Source-boundary unit controls. Explicit typed fixture values are not SQL/provider verification evidence. */
class RestoreRefundObservationSourceTest {
    private static final String REFUND_NO = "RF99";

    @Test
    void lateSuccessUsesTheRetainedPaymentAndProviderOriginalInsteadOfCurrentDefaults() {
        Fixture f = new Fixture();

        var response = RestoreRefundReconciliationFixture.originalRefundResponse(f.payment, f.refund,
                f.retained, REFUND_NO, "SUCCESS");

        assertEquals(Status.SUCCESS, response.getStatus());
        assertEquals("provider-original-71", response.getRefundId());
        assertEquals(REFUND_NO, response.getOutRefundNo());
        assertEquals("WXP-original-99", response.getOutTradeNo());
        assertEquals("transaction-original-99", response.getTransactionId());
        assertEquals(6800L, response.getAmount().getTotal().longValue());
        assertEquals(2000L, response.getAmount().getRefund().longValue());
        assertEquals("CNY", response.getAmount().getCurrency());
        assertEquals(WechatVerifiedFactTestSupport.SUCCESS_TIME, response.getSuccessTime());
        assertEquals("merchant-retained", f.payment.facts().mchid());
        assertEquals("app-retained", f.payment.facts().appid());
    }

    @Test
    void unknownProcessingOrNotFoundCannotBecomeAProviderSuccessReply() {
        Fixture f = new Fixture();
        for (String state : new String[] { "UNKNOWN", "PROCESSING", "NOT_FOUND", "CLOSED" }) {
            assertThrows(IllegalStateException.class, () -> RestoreRefundReconciliationFixture.originalRefundResponse(
                    f.payment, f.refund, f.retained, REFUND_NO, state), state);
        }
    }

    @Test
    void aJournalSuccessWithoutAPersistedFactCannotBeUsedAsCallbackReplay() {
        Fixture f = new Fixture();

        assertThrows(IllegalStateException.class, () -> RestoreRefundReconciliationFixture.retainedRefundCallback(
                f.payment, f.refund, f.retained, null));

        assertNull(f.refund.getPayoutStatus());
        assertEquals(REFUND_NO, f.refund.getRefundNo());
        assertEquals(new BigDecimal("20.00"), f.refund.getRefundAmount());
    }

    @Test
    void foreignPaymentReferenceChannelTransactionOrAmountCannotBeAdopted() {
        Fixture f = new Fixture();
        Map<String, Object> wrong = Map.of("paymentFactId", 72L, "paymentFactHash", "f".repeat(64),
                "mchid", "merchant-current", "appid", "app-current", "paymentTransactionId", "transaction-other",
                "totalFen", 6801L, "businessNo", "REG100", "registrationId", 100L);
        for (var change : wrong.entrySet()) {
            var conflicting = new LinkedHashMap<>(f.retained);
            conflicting.put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> RestoreRefundReconciliationFixture.originalRefundResponse(
                    f.payment, f.refund, conflicting, REFUND_NO, "SUCCESS"), change.getKey());
        }
        var foreignReference = new WechatVerifiedPaymentFactService.Stored(
                new WechatVerifiedPaymentFact.Reference(72L, f.payment.reference().hash()), f.payment.facts());
        assertThrows(IllegalStateException.class, () -> RestoreRefundReconciliationFixture.originalRefundResponse(
                foreignReference, f.refund, f.retained, REFUND_NO, "SUCCESS"));
    }

    @Test
    void wrongRefundNumberOriginalRowOrMoneyCannotBeRepairedForObservation() {
        Fixture f = new Fixture();
        assertThrows(IllegalStateException.class, () -> RestoreRefundReconciliationFixture.originalRefundResponse(
                f.payment, f.refund, f.retained, "RF100", "SUCCESS"));
        for (var change : Map.<String, Object>of("refundId", 1000L, "refundNo", "RF100", "refundFen", 1999L).entrySet()) {
            var conflicting = new LinkedHashMap<>(f.retained);
            conflicting.put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> RestoreRefundReconciliationFixture.originalRefundResponse(
                    f.payment, f.refund, conflicting, REFUND_NO, "SUCCESS"), change.getKey());
        }
        assertEquals(99L, f.refund.getSourceId());
        assertEquals(new BigDecimal("20.00"), f.refund.getRefundAmount());
    }

    @Test
    void retainedCallbackUsesTheExactPersistedReferenceAndOriginalChannelFields() {
        Fixture f = new Fixture();
        var refund = f.persistedRefund("merchant-retained", "app-retained", "provider-original-71", 2000L);

        var callback = RestoreRefundReconciliationFixture.retainedRefundCallback(f.payment, f.refund, f.retained, refund);

        assertEquals("73", callback.get("verified_fact_id"));
        assertEquals(refund.reference().hash(), callback.get("verified_fact_hash"));
        assertEquals("merchant-retained", callback.get("mchid"));
        assertEquals("app-retained", callback.get("appid"));
        assertEquals("REG99", callback.get("outTradeNo"));
        assertEquals("WXP-original-99", callback.get("wxOutTradeNo"));
        assertEquals(REFUND_NO, callback.get("outRefundNo"));
        assertEquals("provider-original-71", callback.get("refundId"));
        assertEquals("transaction-original-99", callback.get("transactionId"));
        assertEquals("SUCCESS", callback.get("status"));
        assertEquals("6800", callback.get("total"));
        assertEquals("2000", callback.get("refund"));
        assertEquals("JSAPI", callback.get("tradeType"));
        assertEquals("CNY", callback.get("currency"));
    }

    @Test
    void conflictingPersistedRefundCannotBeReplacedWithJournalFields() {
        Fixture f = new Fixture();
        var alternatives = java.util.List.of(
                f.persistedRefund("merchant-current", "app-retained", "provider-original-71", 2000L),
                f.persistedRefund("merchant-retained", "app-current", "provider-original-71", 2000L),
                f.persistedRefund("merchant-retained", "app-retained", "provider-other", 2000L),
                f.persistedRefund("merchant-retained", "app-retained", "provider-original-71", 1999L));
        String originalHash = f.payment.reference().hash();
        for (var stored : alternatives) {
            assertThrows(IllegalStateException.class, () -> RestoreRefundReconciliationFixture.retainedRefundCallback(
                    f.payment, f.refund, f.retained, stored));
        }
        assertEquals(originalHash, f.payment.reference().hash());
        assertEquals(REFUND_NO, f.refund.getRefundNo());
    }

    private static final class Fixture {
        final RefundApplication refund = new RefundApplication();
        final WechatVerifiedPaymentFactService.Stored payment;
        final Map<String, Object> retained = new LinkedHashMap<>();

        Fixture() {
            var fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                    "merchant-retained", "app-retained", "REG99", "WXP-original-99", "WXP-original-99",
                    "transaction-original-99", "transaction-original-99", "JSAPI", "CNY", 6800L, 6800L,
                    Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME), "a".repeat(64), "SUCCESS");
            payment = new WechatVerifiedPaymentFactService.Stored(new WechatVerifiedPaymentFact.Reference(71L, fact.factHash()), fact);
            refund.setId(7L); refund.setSourceType("registration"); refund.setSourceId(99L);
            refund.setRefundNo(REFUND_NO); refund.setRefundAmount(new BigDecimal("20.00"));
            retained.put("refundId", 7L); retained.put("registrationId", 99L); retained.put("refundNo", REFUND_NO);
            retained.put("businessNo", "REG99"); retained.put("refundFen", 2000L);
            retained.put("paymentFactId", payment.reference().id()); retained.put("paymentFactHash", payment.reference().hash());
            retained.put("paymentTransactionId", fact.transactionId()); retained.put("providerRefundId", "provider-original-71");
            retained.put("mchid", fact.mchid()); retained.put("appid", fact.appid()); retained.put("currency", fact.currency()); retained.put("totalFen", fact.totalFen());
        }

        WechatVerifiedPaymentFactService.Stored persistedRefund(String mchid, String appid, String provider, long amount) {
            var fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
                    mchid, appid, payment.facts().businessOutTradeNo(), payment.facts().wxOutTradeNo(), REFUND_NO,
                    provider, payment.facts().transactionId(), "JSAPI", "CNY", 6800L, amount,
                    Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME), payment.facts().intentRequestHash(), "REFUND");
            return new WechatVerifiedPaymentFactService.Stored(new WechatVerifiedPaymentFact.Reference(73L, fact.factHash()), fact);
        }
    }
}
