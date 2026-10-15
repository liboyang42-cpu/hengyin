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
    void appUserCredentialsAreExcludedFromHttpJsonButRemainReadableFromOldSessions() throws Exception {
        String json = "{\"@type\":\"com.chengyinhub.common.core.domain.model.LoginAppUser\",\"userId\":42,"
                + "\"appUser\":{\"userId\":42,\"userName\":\"fixture-member\","
                + "\"password\":\"fixture-password\",\"salt\":\"fixture-salt\"}}";
        LoginAppUser session = (LoginAppUser) serializer.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertEquals("fixture-password", session.getAppUser().getPassword());
        assertEquals("fixture-salt", session.getAppUser().getSalt());
        LoginAppUser roundTrip = (LoginAppUser) serializer.deserialize(serializer.serialize(session));
        assertEquals("fixture-password", roundTrip.getAppUser().getPassword());
        assertEquals("fixture-salt", roundTrip.getAppUser().getSalt());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode response = mapper.valueToTree(
                java.util.Collections.singletonMap("appUser", session.getAppUser())).get("appUser");
        assertEquals(42, response.get("userId").asLong());
        assertFalse(response.has("password"));
        assertFalse(response.has("salt"));
        com.chengyinhub.common.core.domain.entity.AppUser restored = mapper.readValue(
                "{\"password\":\"fixture-password\",\"salt\":\"fixture-salt\"}",
                com.chengyinhub.common.core.domain.entity.AppUser.class);
        assertEquals("fixture-password", restored.getPassword());
        assertEquals("fixture-salt", restored.getSalt());
    }

    @Test
    void adminPasswordIsHiddenFromHttpAndLogsButOldRedisAndPasswordWritesRemainReadable() throws Exception {
        String old = "{\"@type\":\"com.chengyinhub.common.core.domain.model.LoginUser\",\"userId\":19,"
                + "\"permissions\":[\"system:user:list\"],\"user\":{\"userId\":19,\"userName\":\"fixture-admin\",\"password\":\"fixture-admin-hash\"}}";
        LoginUser cached = (LoginUser) serializer.deserialize(old.getBytes(StandardCharsets.UTF_8));
        assertEquals("fixture-admin-hash", cached.getUser().getPassword());
        LoginUser roundTrip = (LoginUser) serializer.deserialize(serializer.serialize(cached));
        assertEquals("fixture-admin-hash", roundTrip.getUser().getPassword());
        assertEquals(cached.getPermissions(), roundTrip.getPermissions());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode response = mapper.valueToTree(
                java.util.Collections.singletonMap("user", cached.getUser())).get("user");
        assertEquals("fixture-admin", response.get("userName").asText());
        assertFalse(response.has("password"));
        assertFalse(cached.getUser().toString().contains("fixture-admin-hash"));
        com.chengyinhub.common.core.domain.entity.SysUser writable = mapper.readValue(
                "{\"userName\":\"new-admin\",\"password\":\"fixture-new-password\"}",
                com.chengyinhub.common.core.domain.entity.SysUser.class);
        assertEquals("fixture-new-password", writable.getPassword());
        assertFalse(mapper.valueToTree(writable).has("password"));
    }

    @Test
    void actualAdminGetInfoResponseExcludesCachedPasswordWithoutLosingPermissions() {
        com.chengyinhub.common.core.domain.entity.SysUser user = new com.chengyinhub.common.core.domain.entity.SysUser();
        user.setUserId(19L); user.setUserName("fixture-admin"); user.setPassword("fixture-sensitive-admin-hash");
        LoginUser principal = new LoginUser();
        principal.setUserId(19L); principal.setUser(user);
        principal.setPermissions(java.util.Collections.singleton("system:user:list"));
        com.chengyinhub.framework.web.service.SysPermissionService permissions =
                org.mockito.Mockito.mock(com.chengyinhub.framework.web.service.SysPermissionService.class);
        org.mockito.Mockito.when(permissions.getRolePermission(user)).thenReturn(java.util.Collections.singleton("operator"));
        org.mockito.Mockito.when(permissions.getMenuPermission(user)).thenReturn(principal.getPermissions());
        com.chengyinhub.web.controller.system.SysLoginController controller =
                new com.chengyinhub.web.controller.system.SysLoginController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "permissionService", permissions);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "configService",
                org.mockito.Mockito.mock(com.chengyinhub.system.service.ISysConfigService.class));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, "", java.util.Collections.emptyList()));
        try {
            com.fasterxml.jackson.databind.JsonNode response = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(controller.getInfo());
            assertEquals("fixture-admin", response.get("user").get("userName").asText());
            assertFalse(response.get("user").has("password"));
            assertFalse(response.toString().contains("fixture-sensitive-admin-hash"));
            assertEquals("system:user:list", response.get("permissions").get(0).asText());
            assertEquals("operator", response.get("roles").get(0).asText());
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test
    void emptyRedisValuesKeepNullSemantics() {
        assertArrayEquals(new byte[0], serializer.serialize(null));
        assertNull(serializer.deserialize(null));
        assertNull(serializer.deserialize(new byte[0]));
    }
}
