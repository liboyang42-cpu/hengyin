package com.chengyinhub.business.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 内含席位邀请码：复用既有的可前缀领取码实现（{@link MarketingClaimTokenCodec} 的
 * {@code st1} 域），不再复制一套 HMAC。
 *
 * <p>码格式沿用现有合同：{@code st1.<publicId>.<expEpochSec>.<sig>}。{@code publicId}
 * 是随行持久化的公开随机标识（不是秘密），同一原始邀请重放时可重算出同一枚码；
 * 原报名单、席号、代次与状态由该行在库内绑定，明文码只存 SHA-256 摘要并做常量时间比较。
 * 客户端改 publicId/exp 会验签失败；换一行则 token_hash 不匹配。
 */
public final class SeatInvitationTokenCodec {

    public static final String PREFIX = MarketingClaimTokenCodec.SEAT_PREFIX;
    public static final int PUBLIC_ID_LENGTH = 16;

    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SeatInvitationTokenCodec() { }

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
    }

    /** 公开随机标识；落库后用于幂等重放重算同一枚码，不是秘密。 */
    public static String nonce() {
        char[] out = new char[PUBLIC_ID_LENGTH];
        for (int i = 0; i < out.length; i++) out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new String(out);
    }

    public static String issue(String publicId, long expiresAtEpochSec, String secret) {
        if (publicId == null || !publicId.matches("[0-9a-z]{" + PUBLIC_ID_LENGTH + ",32}")) {
            throw new IllegalArgumentException("SEAT_INVITATION_PUBLIC_ID_INVALID");
        }
        return MarketingClaimTokenCodec.issue(PREFIX, publicId, expiresAtEpochSec, secret);
    }

    public static Result verify(String token, long nowEpochSec, String secret) {
        MarketingClaimTokenCodec.Result result =
                MarketingClaimTokenCodec.verify(PREFIX, token, nowEpochSec, secret);
        return new Result(result.valid, result.publicId, result.expiresAtEpochSec, result.reason);
    }

    public static String sha256Hex(String value) {
        return MarketingClaimTokenCodec.sha256Hex(value == null ? "" : value);
    }

    /** 常量时间比较，防时序侧信道。 */
    public static boolean constEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
