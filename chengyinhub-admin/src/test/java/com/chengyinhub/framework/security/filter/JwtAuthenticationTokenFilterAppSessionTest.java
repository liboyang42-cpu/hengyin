package com.chengyinhub.framework.security.filter;

import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.framework.web.service.AppTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

class JwtAuthenticationTokenFilterAppSessionTest {

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void absoluteExpiredAppSessionIsNotAuthenticatedForCurrentRequest() throws Exception {
        RedisCache redisCache = mock(RedisCache.class);
        AppTokenService tokenService = tokenService(redisCache);
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("127.0.0.1");
        loginRequest.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(loginRequest));
        AppUser appUser = new AppUser();
        appUser.setUserId(42L);
        LoginAppUser loginAppUser = new LoginAppUser(42L, appUser);
        loginAppUser.setSessionEpoch(1L);
        String jwt = tokenService.createAppToken(loginAppUser);
        loginAppUser.setAbsoluteExpireTime(System.currentTimeMillis() - 1);
        loginAppUser.setExpireTime(System.currentTimeMillis() + 12 * 60 * 60 * 1000L);
        String redisKey = CacheConstants.LOGIN_APP_TOKEN_KEY + loginAppUser.getToken();
        when(redisCache.getCacheObject(redisKey)).thenReturn(loginAppUser);

        JwtAuthenticationTokenFilter filter = new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(filter, "appTokenService", tokenService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/protected");
        request.addHeader("Authorization", Constants.TOKEN_PREFIX + jwt);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(redisCache).deleteObject(redisKey);
    }

    private AppTokenService tokenService(RedisCache redisCache) {
        AppTokenService tokenService = new AppTokenService();
        ReflectionTestUtils.setField(tokenService, "redisCache", redisCache);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
        when(registry.isCurrentEpoch(anyLong(), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(tokenService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(tokenService, "header", "Authorization");
        ReflectionTestUtils.setField(tokenService, "secret",
                "unit-test-secret-unit-test-secret-unit-test-secret");
        ReflectionTestUtils.setField(tokenService, "idleTime", 24 * 60);
        ReflectionTestUtils.setField(tokenService, "absoluteTime", 7 * 24 * 60);
        return tokenService;
    }
}
