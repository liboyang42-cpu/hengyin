package com.chengyinhub.upgrade;

import com.chengyinhub.common.utils.jwt.SessionJwt;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-03: jjwt 0.9.1 → 0.13 must not log anyone out.
 *
 * <p>The reference below re-implements what jjwt 0.9.1 did for {@code signWith(HS512, String secret)}:
 * key bytes = {@code DatatypeConverter.parseBase64Binary(secret)} (lenient, no padding), HMAC-SHA512 over
 * {@code base64url(header) + "." + base64url(payload)}. It does not use jjwt at all, so it is an independent
 * oracle for "a token issued before the upgrade". Secrets are synthetic test strings only.
 */
class SessionJwtCompatibilityTest {

    private static final String SHORT_SECRET = "unit-test-secret-unit-test-secret-unit-test-secret"; // lenient: '-' skipped
    private static final String LONG_SECRET = Base64.getEncoder().encodeToString(
            "a-synthetic-test-only-session-secret-that-is-longer-than-sixty-four-bytes-0123456789".getBytes(StandardCharsets.US_ASCII));

    private static String legacyToken(String secret, String payloadJson) throws Exception {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String signingInput = url.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + url.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(javax.xml.bind.DatatypeConverter.parseBase64Binary(secret), "HmacSHA512"));
        return signingInput + "." + url.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void tokensIssuedBeforeTheUpgradeStillVerifyForShortAndLongSecrets() throws Exception {
        for (String secret : new String[]{SHORT_SECRET, LONG_SECRET}) {
            String old = legacyToken(secret, "{\"login_app_user_key\":\"fixture-uuid\"}");
            Claims claims = SessionJwt.parse(old, SessionJwt.hs512Key(secret));
            assertEquals("fixture-uuid", claims.get("login_app_user_key"));
        }
        assertTrue(SessionJwt.isShortSecret(SHORT_SECRET));
        assertFalse(SessionJwt.isShortSecret(LONG_SECRET));
    }

    @Test
    void tokensIssuedAfterTheUpgradeAreByteIdenticalToTheOldLibrary() throws Exception {
        for (String secret : new String[]{SHORT_SECRET, LONG_SECRET}) {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("login_user_key", "fixture-admin-uuid");
            String issued = SessionJwt.sign(claims, SessionJwt.hs512Key(secret));
            assertEquals(legacyToken(secret, "{\"login_user_key\":\"fixture-admin-uuid\"}"), issued,
                    "a node still on the old build must accept tokens from an upgraded node and vice versa");
            assertEquals("fixture-admin-uuid", SessionJwt.parse(issued, SessionJwt.hs512Key(secret)).get("login_user_key"));
        }
    }

    @Test
    void keyBytesFollowTheLegacyLenientDecoder() {
        // jjwt 0.9.1 skipped characters outside the Base64 alphabet; the strict decoder would refuse this secret.
        assertThrows(IllegalArgumentException.class, () -> Base64.getDecoder().decode(SHORT_SECRET));
        assertArrayEquals(javax.xml.bind.DatatypeConverter.parseBase64Binary(SHORT_SECRET), SessionJwt.legacyKeyBytes(SHORT_SECRET));
        byte[] padded = SessionJwt.hs512Key(SHORT_SECRET).getEncoded();
        assertEquals(SessionJwt.HS512_MIN_KEY_BYTES, padded.length);
        assertThrows(IllegalArgumentException.class, () -> SessionJwt.hs512Key(""));
        assertThrows(IllegalArgumentException.class, () -> SessionJwt.hs512Key(null));
    }

    @Test
    void forgedTamperedAndUnsignedTokensAreRefused() throws Exception {
        String good = legacyToken(LONG_SECRET, "{\"login_app_user_key\":\"fixture-uuid\"}");
        assertThrows(io.jsonwebtoken.security.SignatureException.class,
                () -> SessionJwt.parse(legacyToken(SHORT_SECRET, "{\"login_app_user_key\":\"fixture-uuid\"}"),
                        SessionJwt.hs512Key(LONG_SECRET)));
        String[] parts = good.split("\\.");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"login_app_user_key\":\"someone-else\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThrows(io.jsonwebtoken.security.SignatureException.class, () -> SessionJwt.parse(tampered, SessionJwt.hs512Key(LONG_SECRET)));
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + parts[1] + ".";
        assertThrows(io.jsonwebtoken.JwtException.class, () -> SessionJwt.parse(unsigned, SessionJwt.hs512Key(LONG_SECRET)));
        String expired = Jwts.builder().claim("login_app_user_key", "x")
                .expiration(new java.util.Date(System.currentTimeMillis() - 60_000))
                .signWith(SessionJwt.hs512Key(LONG_SECRET), Jwts.SIG.HS512).compact();
        assertThrows(io.jsonwebtoken.ExpiredJwtException.class, () -> SessionJwt.parse(expired, SessionJwt.hs512Key(LONG_SECRET)));
    }

    @Test
    void theRuntimeUsesTheMaintainedJjwtLineOnly() throws Exception {
        String root = Files.readString(Path.of("..", "pom.xml"), StandardCharsets.UTF_8);
        assertTrue(root.contains("<jwt.version>0.13.0</jwt.version>"));
        assertFalse(root.contains("<artifactId>jjwt</artifactId>"), "the 0.9.x monolithic artifact must not come back");
        // 0.9.x returned a JwtParser from Jwts.parser(); the 0.12+ line returns a builder.
        assertEquals(io.jsonwebtoken.JwtParserBuilder.class, Jwts.class.getMethod("parser").getReturnType());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.jsonwebtoken.impl.crypto.MacSigner"));
    }

    @Test
    void theCodeGeneratorIsOutOfTheProductionBuild() throws Exception {
        String admin = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(admin.contains("<chengyin.generator.scope>test</chengyin.generator.scope>"), "default scope keeps it out of the jar");
        assertTrue(admin.contains("<scope>${chengyin.generator.scope}</scope>"));
        int profile = admin.indexOf("<id>code-generator</id>");
        assertTrue(profile > 0, "developers opt in with -Pcode-generator");
        assertTrue(admin.indexOf("<chengyin.generator.scope>compile</chengyin.generator.scope>") > profile);
    }
}
