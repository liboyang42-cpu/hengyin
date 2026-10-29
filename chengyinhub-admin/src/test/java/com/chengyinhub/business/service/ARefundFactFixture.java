package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Explicit test facts only. Memory mode is not SQL, SDK verification or a provider-success proof. */
public final class ARefundFactFixture {
    private ARefundFactFixture() { }

    public static WechatVerifiedPaymentFactService memoryFacts() {
        return WechatVerifiedFactTestSupport.memoryHistoricalFacts();
    }

    /** Caller explicitly supplies a SUCCESS fixture; never upgrades UNKNOWN or repairs malformed inputs. */
    public static Map<String, String> bindSuccess(WechatVerifiedPaymentFactService reader,
                                                Map<String, String> original, String transactionId) {
        if (!"SUCCESS".equals(original.get("status")) || !"CNY".equals(original.get("currency")))
            throw new IllegalArgumentException("Explicit CNY SUCCESS fixture required");
        WechatVerifiedPaymentFact fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
                "merchant", "wx-original", original.get("outTradeNo"), original.get("outTradeNo"),
                original.get("outRefundNo"), "WX-" + original.get("outRefundNo"), transactionId,
                "JSAPI", "CNY", Long.parseLong(original.get("total")), Long.parseLong(original.get("refund")),
                Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME), null, "SUCCESS");
        var reference = reader.recordVerified(fact);
        reader.requireVerified(reference); // Real canonical/provenance/hash validation, not a constant true reader.
        Map<String, String> result = new HashMap<>(original);
        result.put("verified_fact_id", Long.toString(reference.id())); result.put("verified_fact_hash", reference.hash());
        result.put("mchid", fact.mchid()); result.put("appid", fact.appid()); result.put("tradeType", fact.tradeType());
        result.put("transactionId", fact.transactionId()); result.put("wxOutTradeNo", fact.wxOutTradeNo());
        result.put("refundId", fact.providerId());
        return result;
    }
}
