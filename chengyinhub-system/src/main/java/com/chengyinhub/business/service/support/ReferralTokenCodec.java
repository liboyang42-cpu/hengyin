package com.chengyinhub.business.service.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 推广来源凭据(referral token)签发/校验。纯静态、无状态。
 *
 * <p><b>它只做一件事:证明「这次分享来自哪个已登录会员」</b>。凭据本身不携带价格、
 * 折扣、佣金比例或任何权益 —— 归因是记账外事实,不能自授佣金、不能改锁价、不能把
 * 未支付订单变成已支付。{@code source_club_id} 那一列仍恒为 null,本凭据不改写它。
 *
 * <p>码格式:{@code rf1.{publicId}.{expEpochSec}.{sig}},
 * {@code sig = base64url(HMAC-SHA256("rf1.{publicId}.{expEpochSec}", secret))}。
 * 版本前缀独立于核销码 {@code v1/poi1/cq1},域分离:任一格式永不被另一用途误认。
 *
 * <p>可信边界:客户端可以随意改分享链接里的 token,但改不动签名;而 token 能证明的
 * 只有「分享者身份 + 目标 + 有效期」,不含任何金额结论。真正发钱/发权益的路径
 * (结算、支付、核销)不读本凭据。
 */
public final class ReferralTokenCodec {

    public static final String PREFIX = "rf1";
    /** rf1.(4) + publicId(<=32) + .(1) + exp(<=11) + .(1) + sig(43) -> 上限 96。 */
    public static final int MAX_TOKEN_LENGTH = 96;

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String PUBLIC_ID_PATTERN = "[0-9a-z]{16,32}";

    private ReferralTokenCodec() {
    }

    /** 校验结果。valid=true 时 publicId/expiresAtEpochSec 可信;valid=false 时 reason 说明原因。 */
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

    /**
     * 签发。调用方负责分配 publicId(不入 key 的意义:publicId 是公开标识,不是秘密)。
     *
     * @param publicId          16-32 位 {@code [0-9a-z]}
     * @param expiresAtEpochSec 过期时刻(秒)
     * @param secret            服务端密钥
     */
    public static String issue(String publicId, long expiresAtEpochSec, String secret) {
        if (publicId == null || !publicId.matches(PUBLIC_ID_PATTERN)) {
            throw new IllegalArgumentException("REFERRAL_PUBLIC_ID_INVALID");
        }
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("REFERRAL_SECRET_MISSING");
        }
        long exp = expiresAtEpochSec;
        if (exp <= 0) {
            throw new IllegalArgumentException("REFERRAL_EXPIRY_INVALID");
        }
        String payload = PREFIX + "." + publicId + "." + exp;
        return payload + "." + hmac(payload, secret);
    }

    /**
     * 校验签名与有效期。签名不符/格式错/过期一律 fail,不区分到"码存在但过期"以外的细节。
     *
     * @param token     客户端带来的凭据
     * @param nowEpochSec 当前秒
     * @param secret    服务端密钥
     */
    public static Result verify(String token, long nowEpochSec, String secret) {
        if (token == null || token.trim().isEmpty()) {
            return Result.fail("INVALID_TOKEN");
        }
        String value = token.trim();
        if (value.length() > MAX_TOKEN_LENGTH) {
            return Result.fail("INVALID_TOKEN");
        }
        if (secret == null || secret.trim().isEmpty()) {
            return Result.fail("INVALID_TOKEN");
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4 || !PREFIX.equals(parts[0]) || !parts[1].matches(PUBLIC_ID_PATTERN)) {
            return Result.fail("INVALID_TOKEN");
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!constEquals(hmac(payload, secret), parts[3])) {
            return Result.fail("INVALID_TOKEN");
        }
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException invalid) {
            return Result.fail("INVALID_TOKEN");
        }
        if (exp <= 0) {
            return Result.fail("INVALID_TOKEN");
        }
        if (nowEpochSec > exp) {
            return Result.fail("TOKEN_EXPIRED");
        }
        return Result.ok(parts[1], exp);
    }

    /** 凭据明文只用于发行时返回一次;库里只存这个摘要。 */
    public static String sha256Hex(String text) {
        // 不能三目混类型:两个分支一个是 byte[]、一个是 String 时没有公共重载可绑。
        return sha256Hex(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("推广凭据签名失败", e);
        }
    }

    /** 常量时间比较,防时序侧信道。 */
    public static boolean constEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
