package com.chengyinhub.business.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 渠道兑换码编解码与摘要。
 *
 * <p><b>明文只返回一次</b>：{@link #issue(String)} 生成 {@code cv1.<publicId>.<secret>}，
 * 库内只存 {@link #sha256Hex(String)}。公开标识用于按码定位行，secret 才参与摘要比对；
 * 因此只拿到 publicId 无法兑换。
 *
 * <p><b>域分离</b>：{@code cv1} 与推广凭据 {@code rf1}、旧核销码 {@code v1/poi1/cq1}
 * 互不认可，避免跨域重放。
 */
public final class ChannelVoucherCodec {

    private static final String PREFIX = "cv1";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PUBLIC_ID_BYTES = 12;
    private static final int SECRET_BYTES = 32;

    private ChannelVoucherCodec() { }

    public static String newPublicId() {
        byte[] bytes = new byte[PUBLIC_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    public static String issue(String publicId) {
        if (publicId == null || !publicId.matches("[a-f0-9]{16,32}")) {
            throw new IllegalArgumentException("CHANNEL_PUBLIC_ID_INVALID");
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return PREFIX + "." + publicId + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** 从明文码取出 publicId；格式不合法抛 IllegalArgumentException，由服务层映射为 INVALID_CODE。 */
    public static String publicIdOf(String token) {
        if (token == null) {
            throw new IllegalArgumentException("CHANNEL_CODE_REQUIRED");
        }
        String trimmed = token.trim();
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0]) || parts[1].isEmpty() || !parts[2].matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("CHANNEL_CODE_INVALID");
        }
        if (!parts[1].matches("[a-f0-9]{16,32}")) {
            throw new IllegalArgumentException("CHANNEL_CODE_INVALID");
        }
        return parts[1];
    }

    public static String sha256Hex(String value) {
        if (value == null) {
            throw new IllegalArgumentException("CHANNEL_CODE_REQUIRED");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** 常量时间比较，避免按摘要前缀猜码。 */
    public static boolean matches(String token, String storedHash) {
        if (token == null || storedHash == null) return false;
        byte[] actual = sha256Hex(token).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = storedHash.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }
}
