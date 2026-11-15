package com.chengyinhub.business.account.apple;

import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.system.service.ISysConfigService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FLOW-03: Apple token revocation on account deletion, with and without the Sign in with Apple key. */
class AppleTokenRevocationServiceTest {
    static final String BUNDLE = "com.example.chengyin";
    JdbcTemplate jdbc;
    ISysConfigService config;
    PiiCryptoService crypto;
    KeyPair signing;
    final List<String> urls = new ArrayList<>();
    final List<Map<String, String>> forms = new ArrayList<>();
    int tokenStatus = 200;
    int revokeStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:apple_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ums_apple_token_revocation (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL UNIQUE,"
                + " status VARCHAR(32) NOT NULL, refresh_token_cipher VARCHAR(2048), last_error VARCHAR(64), revoked_at TIMESTAMP,"
                + " create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("apple.clientId")).thenReturn(BUNDLE);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        crypto = new PiiCryptoService(Base64.getEncoder().encodeToString(key), "");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        signing = generator.generateKeyPair();
    }

    AppleTokenRevocationService service(boolean configured) {
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()) + "\\n-----END PRIVATE KEY-----";
        return new AppleTokenRevocationService(jdbc, config, crypto, configured ? "KEY123" : "", configured ? "TEAM456" : "",
                configured ? pem : "") {
            @Override protected Response post(String url, Map<String, String> form) {
                urls.add(url);
                forms.add(form);
                if (url.endsWith("/auth/token")) return new Response(tokenStatus, tokenStatus == 200 ? "{\"refresh_token\":\"rt-secret\"}" : "{}");
                return new Response(revokeStatus, "");
            }
        };
    }

    Map<String, Object> row(long memberId) {
        return jdbc.queryForMap("SELECT * FROM ums_apple_token_revocation WHERE member_id = ?", memberId);
    }

    @Test void configuredKeyExchangesCodeStoresCiphertextAndRevokesOnDeletion() {
        AppleTokenRevocationService service = service(true);
        assertEquals(AppleTokenRevocationService.TOKEN_READY, service.prepare(7L, "auth-code"));
        Map<String, String> exchange = forms.get(0);
        assertEquals("auth-code", exchange.get("code"));
        assertEquals(BUNDLE, exchange.get("client_id"));
        String cipher = (String) row(7L).get("refresh_token_cipher");
        assertTrue(cipher.startsWith("v1:"), "refresh token is stored encrypted");
        assertFalse(cipher.contains("rt-secret"));

        assertEquals(AppleTokenRevocationService.REVOKED, service.revokeAfterDeletion(7L));
        assertEquals(AppleTokenRevocationService.REVOKE_URL, urls.get(1));
        Map<String, String> revoke = forms.get(1);
        assertEquals("rt-secret", revoke.get("token"));
        assertEquals("refresh_token", revoke.get("token_type_hint"));
        Jws<Claims> secret = Jwts.parser().setSigningKey(signing.getPublic()).parseClaimsJws(revoke.get("client_secret"));
        assertEquals("TEAM456", secret.getBody().getIssuer());
        assertEquals(BUNDLE, secret.getBody().getSubject());
        assertEquals("https://appleid.apple.com", secret.getBody().getAudience());
        assertEquals("KEY123", secret.getHeader().get("kid"));
        assertEquals("ES256", secret.getHeader().get("alg"));
        assertNull(row(7L).get("refresh_token_cipher"), "token is wiped after revocation");
        assertEquals(AppleTokenRevocationService.REVOKED, row(7L).get("status"));
    }

    @Test void missingKeyRecordsPendingAndNeverCallsApple() {
        AppleTokenRevocationService service = service(false);
        assertEquals(AppleTokenRevocationService.PENDING_CONFIG, service.prepare(8L, "auth-code"));
        assertEquals(AppleTokenRevocationService.PENDING_CONFIG, service.revokeAfterDeletion(8L));
        assertTrue(urls.isEmpty());
        assertEquals("NOT_CONFIGURED", row(8L).get("last_error"));
    }

    @Test void keyRemovedBeforeDeletionKeepsCiphertextAsPending() {
        assertEquals(AppleTokenRevocationService.TOKEN_READY, service(true).prepare(9L, "auth-code"));
        assertEquals(AppleTokenRevocationService.PENDING_CONFIG, service(false).revokeAfterDeletion(9L));
        assertNotNull(row(9L).get("refresh_token_cipher"));
    }

    @Test void appleFailuresAreRecordedNeverThrown() {
        tokenStatus = 400;
        AppleTokenRevocationService service = service(true);
        assertEquals(AppleTokenRevocationService.PENDING_NO_TOKEN, service.prepare(10L, "expired-code"));
        tokenStatus = 200;
        assertEquals(AppleTokenRevocationService.TOKEN_READY, service.prepare(11L, "auth-code"));
        revokeStatus = 503;
        assertEquals(AppleTokenRevocationService.REVOKE_FAILED, service.revokeAfterDeletion(11L));
        assertEquals("HTTP_503", row(11L).get("last_error"));
        jdbc.execute("DROP TABLE ums_apple_token_revocation");
        assertDoesNotThrow(() -> service.revokeAfterDeletion(11L));
        assertDoesNotThrow(() -> service.prepare(12L, "auth-code"));
        assertDoesNotThrow(() -> service.discard(12L));
    }

    @Test void nonAppleDeletionsAreIgnoredAndCancelWipesToken() {
        AppleTokenRevocationService service = service(true);
        assertNull(service.revokeAfterDeletion(99L));
        service.prepare(13L, "auth-code");
        service.discard(13L);
        assertEquals(AppleTokenRevocationService.CANCELLED, row(13L).get("status"));
        assertNull(row(13L).get("refresh_token_cipher"));
    }

    @Test void withoutEncryptionKeyTheTokenIsNotStored() {
        crypto = new PiiCryptoService("", "");
        assertEquals(AppleTokenRevocationService.PENDING_NO_TOKEN, service(true).prepare(14L, "auth-code"));
        assertTrue(urls.isEmpty(), "no exchange when the token could not be stored safely");
    }
}
