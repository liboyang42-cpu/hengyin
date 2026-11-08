package com.chengyinhub.business.service.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 商品报价签名。绑定商品修订、价格、有效期、条款、数量与履约方式，防止客户端改价。
 *
 * <p>报价本身无状态；下单时服务端用同一商品行重新计算，签名不符即 QUOTE_STALE。
 * 这不是支付凭据，也不能把未付款订单变成已付。
 */
@Component
public class MarketingQuoteSigner {

    private static final String HMAC_ALG = "HmacSHA256";

    private final String secret;

    public MarketingQuoteSigner(
            @Value("${marketing.product-secret:${marketing.referral-secret:${verify.dyncode-secret:${token.secret}}}}")
            String secret) {
        this.secret = secret;
    }

    public String sign(String canonicalPayload) {
        if (canonicalPayload == null) throw new IllegalArgumentException("QUOTE_PAYLOAD_REQUIRED");
        if (secret == null || secret.trim().isEmpty()) throw new IllegalStateException("QUOTE_SECRET_MISSING");
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            return HexFormat.of().formatHex(
                    mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("QUOTE_SIGN_UNAVAILABLE", impossible);
        }
    }

    public boolean matches(String canonicalPayload, String expectedSign) {
        if (canonicalPayload == null || expectedSign == null) return false;
        try {
            return MessageDigest.isEqual(sign(canonicalPayload).getBytes(StandardCharsets.UTF_8),
                    expectedSign.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** 长度前缀拼接，避免字段边界被拼接串伪造。 */
    public static String canonical(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "\u0000" : part;
            out.append(value.length()).append(':').append(value).append('|');
        }
        return out.toString();
    }

    public static String canonical(Object... parts) {
        StringBuilder out = new StringBuilder();
        for (Object part : parts) {
            String value = part == null ? "\u0000" : String.valueOf(part);
            out.append(value.length()).append(':').append(value).append('|');
        }
        return out.toString();
    }
}
