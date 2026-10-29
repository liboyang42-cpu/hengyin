package com.chengyinhub.business.domain.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Immutable provider success facts, produced after SDK verification and original-order checks. No business policy. */
public record WechatVerifiedPaymentFact(Kind kind, String mchid, String appid,
        String businessOutTradeNo, String wxOutTradeNo, String merchantEventNo, String providerId,
        String transactionId, String tradeType, String currency, long totalFen, long amountFen,
        Instant successAt, String intentRequestHash, String originalPaymentState) {
    public enum Kind { PAYMENT, REFUND }
    public static final int VERIFICATION_VERSION = 1;
    public static final String VERIFICATION_METHOD = "SDK_QUERY_ORIGINAL_ORDER";

    public record Reference(long id, String hash) {
        public Reference {
            if (id <= 0 || hash == null || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid verified payment fact reference");
            }
        }
    }

    public void requireValid() {
        if (kind == null || !id(mchid,32) || !id(appid,32) || !id(businessOutTradeNo,64)
                || !id(wxOutTradeNo,64) || !id(merchantEventNo,64) || !id(providerId,64)
                || !id(transactionId,64) || !("APP".equals(tradeType) || "JSAPI".equals(tradeType))
                || !"CNY".equals(currency) || totalFen <= 0 || amountFen <= 0 || amountFen > totalFen
                || successAt == null || successAt.toString().length() > 40
                || (intentRequestHash != null && !intentRequestHash.matches("[0-9a-f]{64}"))
                || !("SUCCESS".equals(originalPaymentState) || "REFUND".equals(originalPaymentState))) {
            throw new IllegalArgumentException("Incomplete verified payment facts");
        }
        if (kind == Kind.PAYMENT && (!wxOutTradeNo.equals(merchantEventNo)
                || !providerId.equals(transactionId) || amountFen != totalFen
                || !"SUCCESS".equals(originalPaymentState))) {
            throw new IllegalArgumentException("Conflicting original payment success identity");
        }
    }

    /** Canonical economic identity. Query-vs-notification delivery and the later REFUND state do not change it. */
    public String canonicalFacts() {
        requireValid();
        StringBuilder out = new StringBuilder();
        for (String value : new String[]{"wx-success-fact-v1",kind.name(),mchid,appid,businessOutTradeNo,
                wxOutTradeNo,merchantEventNo,providerId,transactionId,tradeType,currency,
                Long.toString(totalFen),Long.toString(amountFen),successAt.toString(),intentRequestHash}) {
            if (value == null) out.append("-1:");
            else out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
        return out.toString();
    }
    public String factHash() { return sha256(canonicalFacts()); }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
    private static boolean id(String text, int max) {
        return text != null && text.matches("[A-Za-z0-9_-]{1," + max + "}");
    }
}
