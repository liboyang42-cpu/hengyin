package com.chengyinhub.common.utils.apple;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.utils.http.HttpUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Sign in with Apple 的 identityToken（JWT，RS256）校验工具。
 *
 * 纯逻辑、无 Spring 依赖；clientId（App bundleId / Service ID）由调用方传入作 aud 校验。
 * 流程：
 *   1. 解析 JWT header 取 kid；
 *   2. 拉取 Apple 公钥集 JWKS（https://appleid.apple.com/auth/keys），按 kid 找到对应 key；
 *   3. 用 key 的 n/e（base64url）构造 RSAPublicKey；
 *   4. jjwt 验签（exp 由 jjwt 自动校验），并校验 iss / aud。
 *
 * jjwt 版本为 0.9.1：用 Jwts.parser().setSigningKey(PublicKey) 风格（非 0.11+ 的 parserBuilder）。
 *
 * @author chengyin
 */
public class AppleIdTokenVerifier {

    private static final String APPLE_ISS = "https://appleid.apple.com";
    private static final String APPLE_KEYS_URL = "https://appleid.apple.com/auth/keys";

    /**
     * 校验 Apple identityToken，校验通过返回 Claims（含 sub=appleId、email 等）。
     * 任意一步失败抛 RuntimeException，调用方捕获后返回友好错误。
     *
     * @param identityToken 客户端 Apple 登录回传的 identityToken（JWT）
     * @param clientId      期望的 aud（App 的 bundleId / Service ID）
     * @return 验签通过的 Claims
     */
    public static Claims verify(String identityToken, String clientId) {
        if (identityToken == null || identityToken.isEmpty()) {
            throw new RuntimeException("identityToken 为空");
        }

        // 1. 取 header.kid（不验签解析 header）
        String kid = parseKid(identityToken);
        if (kid == null || kid.isEmpty()) {
            throw new RuntimeException("identityToken 缺少 kid");
        }

        // 2. 拉 JWKS，按 kid 找 key
        JSONObject jwk = findJwkByKid(kid);
        if (jwk == null) {
            throw new RuntimeException("未在 Apple JWKS 中找到匹配的公钥");
        }

        // 3. n/e 构造 RSAPublicKey
        PublicKey publicKey = buildPublicKey(jwk.getString("n"), jwk.getString("e"));

        // 4. jjwt 验签（exp 自动校验），取 Claims
        Claims claims;
        try {
            Jws<Claims> jws = Jwts.parser()
                    .setSigningKey(publicKey)
                    .parseClaimsJws(identityToken);
            claims = jws.getBody();
        } catch (Exception e) {
            throw new RuntimeException("identityToken 验签失败: " + e.getMessage());
        }

        // 校验 iss / aud
        if (!APPLE_ISS.equals(claims.getIssuer())) {
            throw new RuntimeException("identityToken iss 非法");
        }
        if (clientId == null || !clientId.equals(claims.getAudience())) {
            throw new RuntimeException("identityToken aud 与 clientId 不匹配");
        }

        return claims;
    }

    /**
     * 解析 JWT header（第一段 base64url）取 kid。
     */
    private static String parseKid(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new RuntimeException("identityToken 格式非法");
            }
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            JSONObject header = JSON.parseObject(headerJson);
            return header.getString("kid");
        } catch (Exception e) {
            throw new RuntimeException("解析 identityToken header 失败: " + e.getMessage());
        }
    }

    /**
     * 拉取 Apple JWKS，按 kid 找到对应 JWK。
     */
    private static JSONObject findJwkByKid(String kid) {
        String result = HttpUtils.sendGetWithRetry(APPLE_KEYS_URL);
        if (result == null || result.isEmpty()) {
            throw new RuntimeException("获取 Apple 公钥失败");
        }
        JSONObject root = JSON.parseObject(result);
        JSONArray keys = root.getJSONArray("keys");
        if (keys == null) {
            throw new RuntimeException("Apple JWKS 格式异常");
        }
        for (int i = 0; i < keys.size(); i++) {
            JSONObject key = keys.getJSONObject(i);
            if (kid.equals(key.getString("kid"))) {
                return key;
            }
        }
        return null;
    }

    /**
     * 用 JWK 的 n/e（base64url 编码）构造 RSA 公钥。
     */
    private static PublicKey buildPublicKey(String n, String e) {
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
            RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception ex) {
            throw new RuntimeException("构造 Apple 公钥失败: " + ex.getMessage());
        }
    }
}
