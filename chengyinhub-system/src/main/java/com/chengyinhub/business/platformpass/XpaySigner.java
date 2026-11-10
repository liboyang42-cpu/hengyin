package com.chengyinhub.business.platformpass;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 小程序虚拟支付签名（官方算法）：
 * <ul>
 *   <li>支付签名 paySig = hex(HMAC-SHA256(appKey, uri + "&amp;" + body))；
 *       前端 wx.requestVirtualPayment 时 uri 固定为 {@code requestVirtualPayment}，body 为 signData 原串；
 *       服务端 /xpay/* 接口时 uri 为接口路径（如 {@code /xpay/query_order}），body 为 POST 原串。</li>
 *   <li>用户态签名 signature = hex(HMAC-SHA256(session_key, signData))。</li>
 *   <li>消息推送 URL 签名 = sha1(字典序拼接 token, timestamp, nonce)。</li>
 * </ul>
 * 签名用的字符串必须与实际发送的字节逐字一致，所以调用方先序列化一次，再拿同一个串签名和发送。
 */
public final class XpaySigner {
    public static final String CLIENT_URI = "requestVirtualPayment";

    private XpaySigner() { }

    public static String paySig(String appKey, String uri, String body) {
        return hmacSha256Hex(appKey, uri + "&" + body);
    }

    public static String userSignature(String sessionKey, String body) {
        return hmacSha256Hex(sessionKey, body);
    }

    public static boolean pushSignatureMatches(String token, String signature, String timestamp, String nonce) {
        if (token == null || token.isEmpty() || signature == null || timestamp == null || nonce == null) return false;
        String[] parts = { token, timestamp, nonce };
        Arrays.sort(parts);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(hex(digest).getBytes(StandardCharsets.US_ASCII),
                    signature.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    static String hmacSha256Hex(String key, String data) {
        if (key == null || key.isEmpty()) throw new IllegalStateException("签名密钥未配置");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
