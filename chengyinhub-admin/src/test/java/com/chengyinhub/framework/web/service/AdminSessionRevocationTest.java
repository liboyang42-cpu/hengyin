package com.chengyinhub.framework.web.service;

import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.mapper.SysUserMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminSessionRevocationTest {
    private final RedisCache cache = mock(RedisCache.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final SysPermissionService permissions = mock(SysPermissionService.class);
    private final TokenService service = new TokenService();
    // Synthetic signing material, independent of all environment credentials.
    private final String key = Base64.getEncoder().encodeToString(
            "test-only-admin-session-signing-material-abcdefgh-abcdefgh-abcdefgh".getBytes(StandardCharsets.UTF_8));

    AdminSessionRevocationTest() {
        ReflectionTestUtils.setField(service, "redisCache", cache);
        ReflectionTestUtils.setField(service, "sysUserMapper", users);
        ReflectionTestUtils.setField(service, "sysPermissionService", permissions);
        ReflectionTestUtils.setField(service, "header", "Authorization");
        ReflectionTestUtils.setField(service, "secret", key);
        ReflectionTestUtils.setField(service, "expireTime", 1440);
    }

    private LoginUser login() {
        SysUser user = new SysUser();
        user.setUserId(42L); user.setUserName("fixture-admin"); user.setStatus("0"); user.setDelFlag("0");
        LoginUser login = new LoginUser(42L, 3L, user, Collections.singleton("old:permission"));
        login.setToken("fixture-admin-session"); login.setLoginTime(System.currentTimeMillis());
        login.setExpireTime(System.currentTimeMillis() + 60000);
        login.setAbsoluteExpireTime(System.currentTimeMillis() + 3600000);
        when(cache.getCacheObject(anyString())).thenReturn(login);
        when(users.selectUserById(42L)).thenReturn(user);
        when(permissions.getMenuPermission(user)).thenReturn(Collections.singleton("current:permission"));
        return login;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String token = Jwts.builder().setClaims(Collections.singletonMap(Constants.LOGIN_USER_KEY, "fixture-admin-session"))
                .signWith(SignatureAlgorithm.HS512, key).compact();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test void currentDatabaseStateAndPermissionsReplaceStaleSessionOnEveryRequest() {
        LoginUser login = login();
        assertSame(login, service.getLoginUser(request()));
        assertEquals(Collections.singleton("current:permission"), login.getPermissions());
        login.getUser().setStatus("1");
        assertNull(service.getLoginUser(request()));
        verify(cache).deleteObject(anyString());
    }

    @Test void deletedOrMissingAccountsAndAbsoluteExpiryCannotAuthenticate() {
        LoginUser login = login();
        login.getUser().setDelFlag("2");
        assertNull(service.getLoginUser(request()));
        login.getUser().setDelFlag("0"); login.setAbsoluteExpireTime(System.currentTimeMillis() - 1);
        assertNull(service.getLoginUser(request()));
        login.setAbsoluteExpireTime(System.currentTimeMillis() + 60000);
        when(users.selectUserById(42L)).thenReturn(null);
        assertNull(service.getLoginUser(request()));
    }

    @Test void refreshPreservesLoginAndAbsoluteDeadlineAndNeverExtendsPast24Hours() {
        LoginUser login = login();
        long firstLogin = login.getLoginTime(); long absolute = login.getAbsoluteExpireTime();
        service.refreshToken(login);
        assertEquals(firstLogin, login.getLoginTime()); assertEquals(absolute, login.getAbsoluteExpireTime());
        assertTrue(login.getExpireTime() <= absolute);
        verify(cache).setCacheObject(anyString(), eq(login), intThat(ttl -> ttl > 0 && ttl <= 3600), eq(TimeUnit.SECONDS));
        clearInvocations(cache); login.setAbsoluteExpireTime(System.currentTimeMillis() - 1);
        service.refreshToken(login);
        verify(cache, never()).setCacheObject(anyString(), any(), anyInt(), any());
    }
}
