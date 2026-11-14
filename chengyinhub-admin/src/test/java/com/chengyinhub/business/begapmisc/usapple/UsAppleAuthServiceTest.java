package com.chengyinhub.business.begapmisc.usapple;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.chengyinhub.web.controller.api.ApiUsAppleAuthController;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** be-gap-misc US Apple routes ({@code Core/USAppleService}) with a locally signed RS256 identity token and H2 storage. */
class UsAppleAuthServiceTest {
    static final String CLIENT = "com.example.questify.us";
    JdbcTemplate jdbc;
    IUmsMemberService members;
    KeyPair apple;
    UsAppleAuthService service;
    final Map<Long, UmsMember> rows = new HashMap<>();
    final AtomicLong ids = new AtomicLong(20000);

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:us_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE us_apple_challenge (challenge_id CHAR(43) PRIMARY KEY, realm VARCHAR(128), nonce_digest CHAR(64),"
                + " state CHAR(43), issued_at BIGINT, expires_at BIGINT, consumed_at BIGINT)");
        jdbc.execute("CREATE TABLE us_apple_account (member_id BIGINT PRIMARY KEY, realm VARCHAR(128), apple_sub VARCHAR(255),"
                + " created_at BIGINT, UNIQUE (realm, apple_sub))");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        apple = generator.generateKeyPair();
        members = mock(IUmsMemberService.class);
        when(members.insertUmsMember(any())).thenAnswer(i -> {
            UmsMember m = i.getArgument(0); m.setId(ids.incrementAndGet()); rows.put(m.getId(), m); return 1;
        });
        when(members.selectUmsMemberById(any())).thenAnswer(i -> rows.get((Long) i.getArgument(0)));
        service = service(true);
    }

    UsAppleAuthService service(boolean enabled) {
        UsAppleKeySource keys = new UsAppleKeySource("https://invalid.example/keys") {
            @Override public PublicKey key(String kid) { return "k1".equals(kid) ? apple.getPublic() : null; }
        };
        return new UsAppleAuthService(jdbc, members, keys, enabled, "us-test", CLIENT);
    }

    String token(String sub, String nonceDigest, String audience, KeyPair signer, long expiresInMs) {
        return Jwts.builder().setHeaderParam("kid", "k1").setIssuer("https://appleid.apple.com").setAudience(audience)
                .setSubject(sub).claim("nonce", nonceDigest).setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiresInMs))
                .signWith(SignatureAlgorithm.RS256, signer.getPrivate()).compact();
    }

    static Map<String, Object> body(Map<String, Object> challenge, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", challenge.get("challengeId"));
        body.put("state", challenge.get("state"));
        body.put("identityToken", token);
        return body;
    }

    @Test
    void challengeIsThreeDistinctBase64UrlValuesAndStoresOnlyTheNonceDigest() {
        UsAppleAuthService.Outcome c = service.challenge();
        assertEquals(200, c.status);
        Map<String, Object> d = c.data;
        for (String key : new String[] {"challengeId", "rawNonce", "state"}) assertTrue(UsAppleAuthService.isRandomValue((String) d.get(key)), key);
        assertNotEquals(d.get("challengeId"), d.get("rawNonce"));
        assertEquals(300L, d.get("expiresIn"));
        assertEquals("US", d.get("market"));
        assertEquals("apple", d.get("provider"));
        String stored = jdbc.queryForObject("SELECT nonce_digest FROM us_apple_challenge", String.class);
        assertEquals(UsAppleAuthService.sha256Hex((String) d.get("rawNonce")), stored);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM us_apple_challenge WHERE nonce_digest = ?", Integer.class, d.get("rawNonce")));
    }

    @Test
    void exchangeLinksOneUsAccountPerSubAndTheSessionProvesIt() {
        Map<String, Object> c = service.challenge().data;
        String digest = UsAppleAuthService.sha256Hex((String) c.get("rawNonce"));
        UsAppleAuthService.Outcome first = service.exchange(body(c, token("apple-sub-1", digest, CLIENT, apple, 60_000)));
        assertEquals(200, first.status, String.valueOf(first.errorCode));
        assertEquals(first.memberId, first.data.get("id"));
        assertNull(first.data.get("avatar"));
        assertEquals("player", first.data.get("role"));
        assertNull(rows.get(first.memberId).getAppleId(), "the CN apple_id column is never set");
        assertEquals(401, service.exchange(body(c, token("apple-sub-1", digest, CLIENT, apple, 60_000))).status, "single use");
        Map<String, Object> c2 = service.challenge().data;
        UsAppleAuthService.Outcome again = service.exchange(body(c2, token("apple-sub-1",
                UsAppleAuthService.sha256Hex((String) c2.get("rawNonce")), CLIENT, apple, 60_000)));
        assertEquals(first.memberId, again.memberId, "same sub, same account");
        UsAppleAuthService.Outcome session = service.session(first.memberId);
        assertEquals(200, session.status);
        assertEquals("US", session.data.get("market"));
        assertEquals("us-test", session.data.get("realm"));
        assertEquals(first.memberId, ((Map<?, ?>) session.data.get("account")).get("id"));
        UsAppleAuthService.Outcome cn = service.session(9009L);
        assertEquals(401, cn.status);
        assertEquals(UsAppleAuthService.SESSION_INVALID, cn.errorCode);
    }

    @Test
    void identityFailuresAreInvalidIdentityAndBurnTheChallenge() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair forger = generator.generateKeyPair();
        String[][] cases = {{"wrong-nonce"}, {"wrong-aud"}, {"forged"}, {"expired"}};
        for (String[] kind : cases) {
            Map<String, Object> c = service.challenge().data;
            String digest = UsAppleAuthService.sha256Hex((String) c.get("rawNonce"));
            String t;
            switch (kind[0]) {
                case "wrong-nonce": t = token("s", UsAppleAuthService.sha256Hex("other"), CLIENT, apple, 60_000); break;
                case "wrong-aud": t = token("s", digest, "com.example.cn", apple, 60_000); break;
                case "forged": t = token("s", digest, CLIENT, forger, 60_000); break;
                default: t = token("s", digest, CLIENT, apple, -60_000);
            }
            UsAppleAuthService.Outcome o = service.exchange(body(c, t));
            assertEquals(401, o.status, kind[0]);
            assertEquals(UsAppleAuthService.INVALID_IDENTITY, o.errorCode, kind[0]);
            assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM us_apple_challenge WHERE challenge_id = ?", Long.class, c.get("challengeId")));
        }
        assertTrue(rows.isEmpty());
    }

    @Test
    void requestAndChallengeShapeErrorsUseTheDocumentedPairs() {
        Map<String, Object> c = service.challenge().data;
        Map<String, Object> extra = body(c, "a.b.c");
        extra.put("email", "x@example.com");
        assertEquals(400, service.exchange(extra).status);
        Map<String, Object> wrongState = body(c, "a.b.c");
        wrongState.put("state", c.get("rawNonce"));
        UsAppleAuthService.Outcome o = service.exchange(wrongState);
        assertEquals(401, o.status);
        assertEquals(UsAppleAuthService.INVALID_CHALLENGE, o.errorCode);
        Map<String, Object> unknown = body(c, "a.b.c");
        unknown.put("challengeId", c.get("state"));
        assertEquals(UsAppleAuthService.INVALID_CHALLENGE, service.exchange(unknown).errorCode);
        UsAppleAuthService off = service(false);
        assertEquals(503, off.challenge().status);
        assertEquals(UsAppleAuthService.UNAVAILABLE, off.exchange(body(c, "a.b.c")).errorCode);
        assertEquals(UsAppleAuthService.UNAVAILABLE, off.session(1L).errorCode);
    }

    @Test
    void controllerHttpStatusEqualsCodeAndIssuesTheTokenOnlyOnSuccess() {
        AppLoginService login = mock(AppLoginService.class);
        when(login.loginByUsAppleLink(any())).thenReturn("us-session-token");
        ApiUsAppleAuthController controller = new ApiUsAppleAuthController(service, login, members) {
            @Override public Long getAppUserId() { return 9009L; }
        };
        assertEquals(400, controller.challenges(Map.of("x", 1)).getStatusCode().value());
        ResponseEntity<Map<String, Object>> challenge = controller.challenges(new HashMap<>());
        assertEquals(200, challenge.getStatusCode().value());
        assertEquals(200, challenge.getBody().get("code"));
        assertFalse(challenge.getBody().containsKey("errorCode"));
        @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) challenge.getBody().get("data");
        ResponseEntity<Map<String, Object>> bad = controller.exchange(body(c, "a.b.c"));
        assertEquals(401, bad.getStatusCode().value());
        assertEquals(401, bad.getBody().get("code"));
        assertEquals(UsAppleAuthService.INVALID_IDENTITY, bad.getBody().get("errorCode"));
        verify(login, never()).loginByUsAppleLink(any());
        @SuppressWarnings("unchecked") Map<String, Object> fresh = (Map<String, Object>) controller.challenges(new HashMap<>()).getBody().get("data");
        ResponseEntity<Map<String, Object>> ok = controller.exchange(body(fresh,
                token("apple-sub-9", UsAppleAuthService.sha256Hex((String) fresh.get("rawNonce")), CLIENT, apple, 60_000)));
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("us-session-token", ok.getBody().get("token"));
        assertEquals("US", ok.getBody().get("market"));
        ResponseEntity<Map<String, Object>> session = controller.session();
        assertEquals(401, session.getStatusCode().value(), "9009 is not a US account");
        assertEquals(UsAppleAuthService.SESSION_INVALID, session.getBody().get("errorCode"));
    }
}
