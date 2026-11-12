package com.chengyinhub.business.service.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 礼品/席位领取码签发与校验。纯静态、无状态。
 *
 * <p>码格式：{@code <prefix>.<publicId>.<expEpochSec>.<sig>}，
 * {@code sig = base64url(HMAC-SHA256("<prefix>.<publicId>.<exp>", secret))}。
 * 前缀域分离：礼品 {@code gt1}、席位 {@code st1}，与核销码/推广码互不误认。
 *
 * <p><b>expEpochSec=0 表示不设码级过期</b>；领取窗口与权益有效期仍由数据库行约束，
 * 码只是身份，不是权益真源。明文码不落库。
 */
public final class MarketingClaimTokenCodec {

    public static final String GIFT_PREFIX = "gt1";
    public static final String SEAT_PREFIX = "st1";
    public static final int MAX_TOKEN_LENGTH = 128;

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String PUBLIC_ID_PATTERN = "[0-9a-z]{16,32}";

    private MarketingClaimTokenCodec() { }

    public static final class Result {
        public final boolean valid;
        public final String publicId;
        public final long expiresAtEpochSec;
        public final String reason;

        private Result(boolean valid, String publicId, long expiresAtEpochSec, String reason) {
            this.valid = valid;
            this.publicId = publicId;
            this.expiresAtEpochSec = expiresAtEpochSec;
            this.reason = reason;
        }

        static Result ok(String publicId, long expiresAtEpochSec) {
            return new Result(true, publicId, expiresAtEpochSec, null);
        }

        static Result fail(String reason) {
            return new Result(false, null, 0L, reason);
        }
    }

    public static String issue(String prefix, String publicId, long expiresAtEpochSec, String secret) {
        if (!GIFT_PREFIX.equals(prefix) && !SEAT_PREFIX.equals(prefix)) {
            throw new IllegalArgumentException("CLAIM_PREFIX_INVALID");
        }
        if (publicId == null || !publicId.matches(PUBLIC_ID_PATTERN)) {
            throw new IllegalArgumentException("CLAIM_PUBLIC_ID_INVALID");
        }
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("CLAIM_SECRET_MISSING");
        }
        if (expiresAtEpochSec < 0) {
            throw new IllegalArgumentException("CLAIM_EXPIRY_INVALID");
        }
        String payload = prefix + "." + publicId + "." + expiresAtEpochSec;
        return payload + "." + hmac(payload, secret);
    }

    public static Result verify(String prefix, String token, long nowEpochSec, String secret) {
        if (token == null || token.trim().isEmpty() || secret == null || secret.trim().isEmpty()) {
            return Result.fail("INVALID_TOKEN");
        }
        String value = token.trim();
        if (value.length() > MAX_TOKEN_LENGTH) return Result.fail("INVALID_TOKEN");
        String[] parts = value.split("\\.");
        if (parts.length != 4 || !prefix.equals(parts[0]) || !parts[1].matches(PUBLIC_ID_PATTERN)) {
            return Result.fail("INVALID_TOKEN");
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!constEquals(hmac(payload, secret), parts[3])) return Result.fail("INVALID_TOKEN");
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException invalid) {
            return Result.fail("INVALID_TOKEN");
        }
        if (exp < 0) return Result.fail("INVALID_TOKEN");
        if (exp > 0 && nowEpochSec > exp) return Result.fail("TOKEN_EXPIRED");
        return Result.ok(parts[1], exp);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static String hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("HMAC 不可用", impossible);
        }
    }

    private static boolean constEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
