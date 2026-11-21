package com.chengyinhub.business.push;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * APNs 基于令牌的鉴权：ES256 JWT（header alg=ES256、kid=Key ID；claims iss=Team ID、iat）。
 * 签名用 JDK 自带的 P1363 编码 ECDSA（JWS 要求 r||s 64 字节，而不是 DER）。
 */
public final class ApnsJwt
{
    private ApnsJwt() { }

    /** 读 .p8（PKCS#8 PEM）。接受环境变量里的字面量 "\n" 转义。 */
    public static PrivateKey parsePrivateKey(String pem)
    {
        if (pem == null || pem.isBlank()) throw new IllegalArgumentException("APNs private key missing");
        String text = pem.replace("\\n", "\n").replace("\\r", "");
        String base64 = text.replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("-----END [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try
        {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        catch (Exception invalid)
        {
            // 不带原文：异常信息会进日志
            throw new IllegalArgumentException("APNs private key is not a PKCS#8 EC key");
        }
    }

    public static String sign(String keyId, String teamId, long issuedAtEpochSeconds, PrivateKey key)
    {
        String header = "{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}";
        String claims = "{\"iss\":\"" + teamId + "\",\"iat\":" + issuedAtEpochSeconds + "}";
        String signingInput = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.getBytes(StandardCharsets.UTF_8));
        try
        {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(signature.sign());
        }
        catch (Exception failure)
        {
            throw new IllegalStateException("APNs JWT signing failed", failure);
        }
    }

    static String b64(byte[] bytes)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
