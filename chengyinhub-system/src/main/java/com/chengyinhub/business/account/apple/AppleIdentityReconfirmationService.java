package com.chengyinhub.business.account.apple;

import com.chengyinhub.business.begapmisc.usapple.UsAppleKeySource;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * FLOW-03: a fresh Sign in with Apple identity token re-confirms the already signed-in owner of an Apple-only account
 * before account deletion (App Store guideline 5.1.1(v)). It never signs a session.
 *
 * <p>Checks: RS256 signature against Apple's published key for the token's {@code kid}; {@code iss} is Apple;
 * {@code aud} equals the app's bundle id ({@code sys_config apple.clientId}, the same value the Apple login uses);
 * the token is not expired and was issued within {@link #MAX_AGE_MS}; {@code sub} equals the member's stored Apple
 * subject. Each token is accepted once, and attempts are rate limited per member and per IP.
 */
@Service
public class AppleIdentityReconfirmationService {
    private static final Logger log = LoggerFactory.getLogger(AppleIdentityReconfirmationService.class);
    static final String ISSUER = "https://appleid.apple.com";
    static final String CLIENT_ID_CONFIG = "apple.clientId";
    /** A re-confirmation must be fresh: Apple issues identity tokens for 10 minutes. */
    static final long MAX_AGE_MS = 10L * 60_000L;
    static final long CLOCK_SKEW_MS = 60_000L;
    static final int MAX_TOKEN_LENGTH = 8192;

    public enum Result { CONFIRMED, INVALID, UNAVAILABLE }

    private final UsAppleKeySource keys;
    private final ISysConfigService config;
    private final RedisCache redis;
    Clock clock = Clock.systemUTC();

    @Autowired
    public AppleIdentityReconfirmationService(UsAppleKeySource keys, ISysConfigService config, RedisCache redis) {
        this.keys = keys;
        this.config = config;
        this.redis = redis;
    }

    public Result verify(Long memberId, String expectedSubject, String identityToken, String ip) {
        if (memberId == null || memberId <= 0 || expectedSubject == null || expectedSubject.isBlank()
                || identityToken == null || identityToken.isBlank() || identityToken.length() > MAX_TOKEN_LENGTH) {
            return Result.INVALID;
        }
        String clientId = clientId();
        if (clientId == null) {
            log.warn("[Apple re-confirmation] apple.clientId is not configured; Apple accounts cannot confirm deletion");
            return Result.UNAVAILABLE;
        }
        try {
            Long ipCount = redis.incrementWithinWindow("deregister_apple_ip_" + digest(ip == null ? "unknown" : ip), 1, TimeUnit.MINUTES);
            Long ownerCount = redis.incrementWithinWindow("deregister_apple_owner_" + memberId, 1, TimeUnit.MINUTES);
            if (ipCount == null || ipCount > 10 || ownerCount == null || ownerCount > 10) return Result.INVALID;
        } catch (RuntimeException unavailable) {
            log.warn("[Apple re-confirmation] rate limiter unavailable: {}", unavailable.getClass().getSimpleName());
            return Result.UNAVAILABLE;
        }
        String subject;
        try {
            subject = verifiedSubject(identityToken, clientId);
        } catch (UsAppleKeySource.Unavailable jwks) {
            log.warn("[Apple re-confirmation] Apple signing keys could not be read");
            return Result.UNAVAILABLE;
        }
        if (subject == null || !MessageDigest.isEqual(subject.getBytes(StandardCharsets.UTF_8),
                expectedSubject.getBytes(StandardCharsets.UTF_8))) {
            return Result.INVALID;
        }
        try {
            // Single use: a captured token cannot confirm a second application.
            if (!Boolean.TRUE.equals(redis.setIfAbsent("deregister_apple_token_" + digest(identityToken),
                    memberId.toString(), 15, TimeUnit.MINUTES))) return Result.INVALID;
        } catch (RuntimeException unavailable) {
            return Result.UNAVAILABLE;
        }
        return Result.CONFIRMED;
    }

    /** The verified {@code sub}, or null when the token is not a valid, fresh Apple identity for this app. */
    String verifiedSubject(String token, String clientId) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return null;
        String kid;
        String alg;
        try {
            Map<?, ?> header = new ObjectMapper().readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
            kid = header.get("kid") instanceof String ? (String) header.get("kid") : null;
            alg = header.get("alg") instanceof String ? (String) header.get("alg") : null;
        } catch (Exception malformed) {
            return null;
        }
        if (kid == null || !"RS256".equals(alg)) return null;
        PublicKey key = keys.key(kid);
        if (key == null) return null;
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception invalid) {
            return null; // bad signature, expired, malformed
        }
        if (!ISSUER.equals(claims.getIssuer()) || !java.util.Set.of(clientId).equals(claims.getAudience())) return null;
        long now = clock.millis();
        if (claims.getExpiration() == null || claims.getExpiration().getTime() <= now) return null;
        if (claims.getIssuedAt() == null || claims.getIssuedAt().getTime() > now + CLOCK_SKEW_MS
                || now - claims.getIssuedAt().getTime() > MAX_AGE_MS) return null;
        String sub = claims.getSubject();
        return sub == null || sub.isEmpty() || sub.length() > 255 ? null : sub;
    }

    String clientId() {
        String value = config.selectConfigByKey(CLIENT_ID_CONFIG);
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
