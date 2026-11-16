package com.chengyinhub.common.utils.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Map;

/**
 * 会话令牌(后台 TokenService 与 App AppTokenService)的 HS512 签发与校验。
 *
 * <p>SEC-03:jjwt 从 0.9.1 升到 0.13 时,已发出的会话必须继续有效。两处兼容点:
 * <ol>
 *   <li><b>密钥字节</b>:0.9.1 的 {@code signWith(HS512, String)} / {@code setSigningKey(String)} 把
 *       {@code token.secret} 当 Base64 文本,用 {@code javax.xml.bind.DatatypeConverter.parseBase64Binary}
 *       宽松解码(非 Base64 字符直接跳过)。这里用同一个解码器,字节完全一致。0.13 的
 *       {@code Decoders.BASE64} 是严格解码,会拒绝或解出不同字节,所以不用它。</li>
 *   <li><b>密钥长度</b>:0.13 对 HS512 要求密钥 ≥ 512 位,否则抛 {@code WeakKeyException}。
 *       HMAC(RFC 2104)先把短于块长(SHA-512 为 128 字节)的密钥右侧补零到块长,所以把短密钥
 *       右补零到 64 字节得到的 MAC 与原密钥逐位相同:旧令牌照样验过,新令牌与旧库签出的一样。
 *       补零不增加强度:短密钥的实际强度仍是原字节数,见 {@link #isShortSecret(String)},
 *       运维应换一个解码后 ≥ 64 字节的随机密钥(换密钥会让所有会话下线,属于 owner 决定)。</li>
 * </ol>
 */
public final class SessionJwt {

    /** HS512 的最小密钥长度(字节)。 */
    public static final int HS512_MIN_KEY_BYTES = 64;

    private SessionJwt() {
    }

    /** 与 jjwt 0.9.1 相同的密钥字节(宽松 Base64 解码),不补零。 */
    public static byte[] legacyKeyBytes(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("token.secret must not be empty");
        }
        byte[] bytes = javax.xml.bind.DatatypeConverter.parseBase64Binary(secret);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("token.secret does not decode to any key bytes");
        }
        return bytes;
    }

    /** HS512 签名密钥:旧字节,短于 64 字节时右补零(HMAC 结果不变)。 */
    public static SecretKey hs512Key(String secret) {
        byte[] bytes = legacyKeyBytes(secret);
        if (bytes.length < HS512_MIN_KEY_BYTES) {
            bytes = Arrays.copyOf(bytes, HS512_MIN_KEY_BYTES);
        }
        return new SecretKeySpec(bytes, "HmacSHA512");
    }

    /** 解码后短于 64 字节:能用(兼容),但强度不足,启动时应告警。 */
    public static boolean isShortSecret(String secret) {
        return legacyKeyBytes(secret).length < HS512_MIN_KEY_BYTES;
    }

    public static String sign(Map<String, Object> claims, SecretKey key) {
        return Jwts.builder().claims(claims).signWith(key, Jwts.SIG.HS512).compact();
    }

    /** 只接受签名有效的 JWS;不接受未签名令牌或其它算法族。 */
    public static Claims parse(String token, SecretKey key) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
