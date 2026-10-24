package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.web.service.AppTokenService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class H5SessionPrimitiveLandingTest {

    private static final long APP_USER_ID = 7L;
    private static final String MAIN_TOKEN = "main-session-token-value";
    private static final String VERSION = "frozen-release-1";

    private HttpServletRequest request;
    private HttpServletResponse response;
    private AppTokenService tokens;
    private IAppSessionRegistryService epochs;
    private RedisCache redis;
    private H5SessionPrimitiveLanding landing;
    private LoginAppUser user;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/workshop/h5/session");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        tokens = mock(AppTokenService.class);
        epochs = mock(IAppSessionRegistryService.class);
        redis = mock(RedisCache.class);
        JwtAuthenticationTokenFilter filter = new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(filter, "appTokenService", tokens);
        landing = new H5SessionPrimitiveLanding(filter, tokens, new BaseController(), epochs, redis);
        AppUser appUser = new AppUser();
        appUser.setUserId(APP_USER_ID);
        user = new LoginAppUser(APP_USER_ID, appUser);
        user.setToken(MAIN_TOKEN);
        user.setSessionEpoch(4L);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAuthDoesNotReturnTicket() {
        when(tokens.getLoginAppUser(request)).thenReturn(null);

        H5SessionPrimitiveLanding.Decision decision = landing.createSession(
                request, response, "run-1", "objective-1", VERSION);

        assertClosedWithoutTicket(decision);
        assertEquals("AppTokenService result missing", decision.reason());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(redis, never()).getExpire(anyString());
        verify(redis, never()).getCacheObject(anyString());
        verifyNoIssuance();
    }

    @Test
    void missingLicenseDoesNotReturnTicket() {
        admitActor();
        when(redis.getExpire(H5SessionPrimitiveLanding.redisReadKey(APP_USER_ID, VERSION))).thenReturn(30L);
        when(redis.getCacheObject(anyString())).thenReturn("https://shell.example/h5?ticket=secret");

        H5SessionPrimitiveLanding.Decision decision = landing.createSession(
                request, response, "run-1", "objective-1", VERSION);

        assertClosedWithoutTicket(decision);
        assertEquals("license missing", decision.reason());
        assertFalse(String.valueOf(decision.shellUrl()).contains("ticket"));
        assertFalse(String.valueOf(decision.parentOrigin()).contains("http"));
        verify(epochs).isCurrentEpoch(APP_USER_ID, 4L);
        verify(redis).getExpire(H5SessionPrimitiveLanding.redisReadKey(APP_USER_ID, VERSION));
        verify(redis).getCacheObject(H5SessionPrimitiveLanding.redisReadKey(APP_USER_ID, VERSION));
        verifyNoIssuance();
    }

    @Test
    void missingRedisResultDoesNotReturnTicket() {
        admitActor();
        when(redis.getExpire(anyString())).thenReturn(-2L);
        when(redis.getCacheObject(anyString())).thenThrow(new IllegalStateException("redis down"));

        H5SessionPrimitiveLanding.Decision decision = landing.createSession(
                request, response, "run-1", "objective-1", VERSION);

        assertClosedWithoutTicket(decision);
        assertEquals("limited ttl missing", decision.reason());
        verify(redis).getExpire(H5SessionPrimitiveLanding.redisReadKey(APP_USER_ID, VERSION));
        verify(redis, never()).getCacheObject(anyString());
        verifyNoIssuance();
    }

    @Test
    void mainSessionTokenIsNotTheTicketAndIsNotPlacedInAUrl() {
        admitActor();
        when(redis.getExpire(anyString())).thenReturn(30L);

        H5SessionPrimitiveLanding.Decision decision = landing.createSession(
                request, response, MAIN_TOKEN, "https://shell.example/?ticket=" + MAIN_TOKEN, VERSION);

        assertClosedWithoutTicket(decision);
        assertEquals("main jwt is not the h5 ticket", decision.reason());
        assertFalse(H5SessionPrimitiveLanding.redisReadKey(APP_USER_ID, VERSION).contains(MAIN_TOKEN));
        assertFalse(H5SessionPrimitiveLanding.redisReadKey(APP_USER_ID, VERSION).contains("://"));
        verify(redis, never()).getExpire(anyString());
        verifyNoIssuance();
    }

    private void admitActor() {
        when(tokens.getLoginAppUser(request)).thenReturn(user);
        when(tokens.verifyToken(user)).thenReturn(true);
        when(epochs.isCurrentEpoch(APP_USER_ID, 4L)).thenReturn(true);
    }

    private void verifyNoIssuance() {
        verify(tokens, never()).createAppToken(any());
        verify(redis, never()).setIfAbsent(any(), any(), any(), any());
        verify(redis, never()).compareAndSet(any(), any(), any(), any(), any());
        verify(redis, never()).compareAndDelete(any(), any());
    }

    private static void assertClosedWithoutTicket(H5SessionPrimitiveLanding.Decision decision) {
        assertEquals(H5SessionPrimitiveLanding.Outcome.CLOSED, decision.outcome());
        assertNull(decision.launchTicket());
        assertNull(decision.shellUrl());
        assertNull(decision.parentOrigin());
        assertNull(decision.returnedTtlSeconds());
        assertTrue(decision.liveEnablementBlocked());
    }
}
