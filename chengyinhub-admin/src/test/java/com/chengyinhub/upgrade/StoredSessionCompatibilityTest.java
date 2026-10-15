package com.chengyinhub.upgrade;

import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.config.FastJson2JsonRedisSerializer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen synthetic pre-upgrade wire fixtures. No production session or signing key is used. */
class StoredSessionCompatibilityTest {
    private final FastJson2JsonRedisSerializer<Object> serializer = new FastJson2JsonRedisSerializer<>(Object.class);
    // Test-only seed and frozen old-wire segments stay independent of the current JJWT builder.
    private static final String KEY = java.util.Base64.getEncoder().encodeToString(
            "upgrade-test-only-key-never-production".getBytes(StandardCharsets.US_ASCII));
    private static final String TOKEN = String.join(".",
            "eyJhbGciOiJIUzUxMiJ9",
            "eyJsb2dpbl91c2VyX2tleSI6ImZpeHR1cmUtdG9rZW4iLCJzdWIiOiJmaXh0dXJlLWFkbWluIn0",
            "fwOFV3C29oJtt8MTDDOGb0ghUQAoAhOEe0E9wtiR5XnkqREz7M6nAfWULQnM9beF0vl5mT3OlcH8_i0PFjFanQ");

    @Test
    void preUpgradeHs512TokenStillParsesWithTheSameBase64KeySemantics() {
        Claims claims = Jwts.parser().setSigningKey(KEY).parseClaimsJws(TOKEN).getBody();
        assertEquals("fixture-token", claims.get(Constants.LOGIN_USER_KEY));
        assertEquals("fixture-admin", claims.getSubject());
        assertEquals(TOKEN, Jwts.builder().setClaims(claims).signWith(SignatureAlgorithm.HS512, KEY).compact());
        assertThrows(io.jsonwebtoken.SignatureException.class,
                () -> Jwts.parser().setSigningKey("ZGlmZmVyZW50LXRlc3Qta2V5").parseClaimsJws(TOKEN));
    }

    @Test
    void preUpgradeAppRedisSessionRetainsEpochAndExpiry() {
        String json = "{\"@type\":\"com.chengyinhub.common.core.domain.model.LoginAppUser\","
                + "\"userId\":42,\"token\":\"fixture-app\",\"sessionEpoch\":7,"
                + "\"loginTime\":1700000000000,\"expireTime\":1700000060000,\"absoluteExpireTime\":1700000120000,"
                + "\"appUser\":{\"userId\":42,\"userName\":\"fixture-member\"}}";
        Object stored = serializer.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertTrue(stored instanceof LoginAppUser);
        LoginAppUser session = (LoginAppUser) stored;
        assertEquals(Long.valueOf(42), session.getUserId());
        assertEquals(Long.valueOf(7), session.getSessionEpoch());
        assertEquals(Long.valueOf(1700000060000L), session.getExpireTime());
        assertEquals(Long.valueOf(1700000120000L), session.getAbsoluteExpireTime());
        assertEquals("fixture-member", session.getUsername());
        LoginAppUser roundTrip = (LoginAppUser) serializer.deserialize(serializer.serialize(session));
        assertEquals(session.getSessionEpoch(), roundTrip.getSessionEpoch());
        assertEquals(session.getAbsoluteExpireTime(), roundTrip.getAbsoluteExpireTime());
    }

    @Test
    void preUpgradeAdminRedisSessionRetainsPermissions() {
        String json = "{\"@type\":\"com.chengyinhub.common.core.domain.model.LoginUser\","
                + "\"userId\":19,\"deptId\":3,\"token\":\"fixture-admin\","
                + "\"permissions\":[\"system:user:list\"],\"user\":{\"userId\":19,\"userName\":\"fixture-admin\"}}";
        LoginUser user = (LoginUser) serializer.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(Long.valueOf(19), user.getUserId());
        assertEquals(Long.valueOf(3), user.getDeptId());
        assertEquals("fixture-admin", user.getUsername());
        assertEquals(java.util.Collections.singleton("system:user:list"), user.getPermissions());
        LoginUser roundTrip = (LoginUser) serializer.deserialize(serializer.serialize(user));
        assertEquals(user.getPermissions(), roundTrip.getPermissions());
    }

    @Test
    void emptyRedisValuesKeepNullSemantics() {
        assertArrayEquals(new byte[0], serializer.serialize(null));
        assertNull(serializer.deserialize(null));
        assertNull(serializer.deserialize(new byte[0]));
    }
}
