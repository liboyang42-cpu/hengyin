package com.chengyinhub.business.account.apple;

import com.chengyinhub.business.account.apple.AppleIdentityReconfirmationService.Result;
import com.chengyinhub.business.begapmisc.usapple.UsAppleKeySource;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FLOW-03: Apple identity token re-confirmation for deleting an Apple-only account. */
class AppleIdentityReconfirmationServiceTest {
    static final String BUNDLE = "com.example.chengyin";
    static final String SUB = "001234.apple-subject.0001";
    static KeyPair apple;
    static KeyPair attacker;
    ISysConfigService config;
    RedisCache redis;
    boolean jwksDown;
    final Set<String> usedKeys = new HashSet<>();
    AppleIdentityReconfirmationService service;

    @BeforeEach
    void setUp() throws Exception {
        if (apple == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            apple = generator.generateKeyPair();
            attacker = generator.generateKeyPair();
        }
        config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("apple.clientId")).thenReturn(BUNDLE);
        redis = mock(RedisCache.class);
        when(redis.incrementWithinWindow(anyString(), anyInt(), any())).thenReturn(1L);
        when(redis.setIfAbsent(anyString(), any(), anyInt(), any())).thenAnswer(i -> usedKeys.add(i.getArgument(0)));
        UsAppleKeySource keys = new UsAppleKeySource("https://invalid.example/keys") {
            @Override public PublicKey key(String kid) {
                if (jwksDown) throw new UsAppleKeySource.Unavailable();
                return "k1".equals(kid) ? apple.getPublic() : null;
            }
        };
        service = new AppleIdentityReconfirmationService(keys, config, redis);
    }

    JwtBuilder token() {
        long now = System.currentTimeMillis();
        return Jwts.builder().header().keyId("k1").and().issuer("https://appleid.apple.com").audience().single(BUNDLE)
                .subject(SUB).issuedAt(new Date(now - 5_000)).expiration(new Date(now + 600_000));
    }

    Result verify(String token) {
        return service.verify(1001L, SUB, token, "198.51.100.20");
    }

    String signed(JwtBuilder builder) {
        return builder.signWith(apple.getPrivate(), Jwts.SIG.RS256).compact();
    }

    @Test void freshTokenForTheStoredSubjectConfirms() {
        assertEquals(Result.CONFIRMED, verify(signed(token())));
    }

    @Test void wrongAudienceIsRejected() {
        assertEquals(Result.INVALID, verify(signed(token().audience().single("com.other.app"))));
    }

    @Test void wrongIssuerIsRejected() {
        assertEquals(Result.INVALID, verify(signed(token().issuer("https://evil.example"))));
    }

    @Test void expiredTokenIsRejected() {
        long now = System.currentTimeMillis();
        assertEquals(Result.INVALID, verify(signed(token().issuedAt(new Date(now - 700_000)).expiration(new Date(now - 1_000)))));
    }

    @Test void staleIssuedAtIsRejectedEvenIfNotExpired() {
        long now = System.currentTimeMillis();
        assertEquals(Result.INVALID, verify(signed(token().issuedAt(new Date(now - 11 * 60_000)))));
    }

    @Test void anotherAppleAccountCannotDeleteThisOne() {
        assertEquals(Result.INVALID, verify(signed(token().subject("009999.someone-else"))));
    }

    @Test void forgedSignatureIsRejected() {
        assertEquals(Result.INVALID, verify(token().signWith(attacker.getPrivate(), Jwts.SIG.RS256).compact()));
    }

    @Test void unknownKeyIdAndMalformedTokensAreRejected() {
        assertEquals(Result.INVALID, verify(signed(token().header().keyId("unknown").and())));
        assertEquals(Result.INVALID, verify("not-a-jwt"));
        assertEquals(Result.INVALID, verify(""));
        assertEquals(Result.INVALID, service.verify(1001L, "", signed(token()), "198.51.100.20"));
    }

    @Test void tokenIsSingleUse() {
        String token = signed(token());
        assertEquals(Result.CONFIRMED, verify(token));
        assertEquals(Result.INVALID, verify(token));
    }

    @Test void rateLimitedAttemptsAreRejected() {
        when(redis.incrementWithinWindow(anyString(), anyInt(), any())).thenReturn(11L);
        assertEquals(Result.INVALID, verify(signed(token())));
    }

    @Test void missingBundleIdOrUnreadableAppleKeysAreUnavailableNotInvalid() {
        jwksDown = true;
        assertEquals(Result.UNAVAILABLE, verify(signed(token())));
        jwksDown = false;
        when(config.selectConfigByKey("apple.clientId")).thenReturn(" ");
        assertEquals(Result.UNAVAILABLE, verify(signed(token())));
    }
}
