package com.chengyinhub.framework.web.service;

import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class AppTokenServiceSessionTest {

    private static final int IDLE_MINUTES = 24 * 60;
    private static final int ABSOLUTE_MINUTES = 7 * 24 * 60;
    private static final long MINUTE_MILLIS = 60_000L;

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void refreshTokenUsesTwentyFourHourIdleWindowWithinSevenDayAbsoluteLifetime() {
        RedisCache redisCache = mock(RedisCache.class);
        AppTokenService tokenService = tokenService(redisCache);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setToken("session-id");
        loginAppUser.setAbsoluteExpireTime(System.currentTimeMillis() + ABSOLUTE_MINUTES * MINUTE_MILLIS);

        tokenService.refreshToken(loginAppUser);

        assertEquals(IDLE_MINUTES * MINUTE_MILLIS,
                loginAppUser.getExpireTime() - loginAppUser.getLoginTime());
        ArgumentCaptor<Integer> ttlCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(redisCache).setCacheObject(anyString(), same(loginAppUser), ttlCaptor.capture(),
                same(TimeUnit.MILLISECONDS));
        assertTrue(ttlCaptor.getValue() <= IDLE_MINUTES * MINUTE_MILLIS);
        assertTrue(ttlCaptor.getValue() >= IDLE_MINUTES * MINUTE_MILLIS - 1_000L);
    }

    @Test
    void refreshTokenNeverExtendsCachePastAbsoluteLifetime() {
        RedisCache redisCache = mock(RedisCache.class);
        AppTokenService tokenService = tokenService(redisCache);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setToken("session-id");
        long absoluteExpireTime = System.currentTimeMillis() + 60 * MINUTE_MILLIS;
        loginAppUser.setAbsoluteExpireTime(absoluteExpireTime);

        tokenService.refreshToken(loginAppUser);

        assertEquals(absoluteExpireTime, loginAppUser.getExpireTime());
        ArgumentCaptor<Integer> ttlCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(redisCache).setCacheObject(anyString(), same(loginAppUser), ttlCaptor.capture(),
                same(TimeUnit.MILLISECONDS));
        assertTrue(ttlCaptor.getValue() <= 60 * MINUTE_MILLIS);
        assertTrue(ttlCaptor.getValue() >= 60 * MINUTE_MILLIS - 1_000L);
    }

    @Test
    void refreshTokenRevokesSessionAfterAbsoluteLifetime() {
        RedisCache redisCache = mock(RedisCache.class);
        AppTokenService tokenService = tokenService(redisCache);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setToken("session-id");
        loginAppUser.setAbsoluteExpireTime(System.currentTimeMillis() - 1);

        tokenService.refreshToken(loginAppUser);

        verify(redisCache).deleteObject(anyString());
        verify(redisCache, never()).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
    }

    @Test
    void verifyTokenRevokesAbsoluteExpiredSessionWithoutSlidingIdleWindow() {
        RedisCache redisCache = mock(RedisCache.class);
        AppTokenService tokenService = tokenService(redisCache);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setToken("session-id");
        loginAppUser.setExpireTime(System.currentTimeMillis() + 12 * 60 * MINUTE_MILLIS);
        loginAppUser.setAbsoluteExpireTime(System.currentTimeMillis() - 1);

        boolean valid = tokenService.verifyToken(loginAppUser);

        assertFalse(valid);
        verify(redisCache).deleteObject(anyString());
        verify(redisCache, never()).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
    }

    @Test
    void verifyTokenRevokesExistingSessionWithoutAbsoluteExpiry() {
        RedisCache redisCache = mock(RedisCache.class);
        AppTokenService tokenService = tokenService(redisCache);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setToken("legacy-session-id");
        loginAppUser.setLoginTime(System.currentTimeMillis() - 6 * 24 * 60 * MINUTE_MILLIS);
        loginAppUser.setExpireTime(System.currentTimeMillis() + 9 * 24 * 60 * MINUTE_MILLIS);

        boolean valid = tokenService.verifyToken(loginAppUser);

        assertFalse(valid);
        assertNull(loginAppUser.getAbsoluteExpireTime());
        verify(redisCache).deleteObject(anyString());
        verify(redisCache, never()).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
    }

    @Test
    void verifyTokenSlidesNearIdleExpiryWithoutChangingAbsoluteExpiry() {
        RedisCache redisCache = mock(RedisCache.class);
        AppTokenService tokenService = tokenService(redisCache);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setToken("session-id");
        loginAppUser.setUserId(42L);
        loginAppUser.setSessionEpoch(1L);
        long absoluteExpireTime = System.currentTimeMillis() + 3 * 24 * 60 * MINUTE_MILLIS;
        loginAppUser.setAbsoluteExpireTime(absoluteExpireTime);
        loginAppUser.setExpireTime(System.currentTimeMillis() + 10 * MINUTE_MILLIS);

        boolean valid = tokenService.verifyToken(loginAppUser);

        assertTrue(valid);
        assertEquals(absoluteExpireTime, loginAppUser.getAbsoluteExpireTime());
        assertEquals(IDLE_MINUTES * MINUTE_MILLIS,
                loginAppUser.getExpireTime() - loginAppUser.getLoginTime());
        verify(redisCache).setCacheObject(anyString(), same(loginAppUser), anyInt(),
                same(TimeUnit.MILLISECONDS));
    }

    @Test
    void createAppTokenStartsTwentyFourHourIdleAndSevenDayAbsoluteWindows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RedisCache redisCache = mock(RedisCache.class);
        AppTokenService tokenService = tokenService(redisCache);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setUserId(42L);
        loginAppUser.setSessionEpoch(1L);
        long before = System.currentTimeMillis();

        String jwt = tokenService.createAppToken(loginAppUser);

        long after = System.currentTimeMillis();
        assertFalse(jwt.isEmpty());
        assertEquals(1L, loginAppUser.getSessionEpoch());
        assertEquals(IDLE_MINUTES * MINUTE_MILLIS,
                loginAppUser.getExpireTime() - loginAppUser.getLoginTime());
        assertTrue(loginAppUser.getAbsoluteExpireTime() >= before + ABSOLUTE_MINUTES * MINUTE_MILLIS);
        assertTrue(loginAppUser.getAbsoluteExpireTime() <= after + ABSOLUTE_MINUTES * MINUTE_MILLIS);
    }

    @Test
    void verifyTokenRejectsSessionMissingFromCurrentMemberEpochEvenWhenTokenEntryRemains() {
        RedisCache redisCache = mock(RedisCache.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.isCurrentEpoch(42L, 1L)).thenReturn(false);
        AppTokenService tokenService = tokenService(redisCache, registry);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setUserId(42L);
        loginAppUser.setSessionEpoch(1L);
        loginAppUser.setToken("unindexed-session-id");
        loginAppUser.setSessionEpoch(1L);
        loginAppUser.setAbsoluteExpireTime(System.currentTimeMillis() + MINUTE_MILLIS);
        loginAppUser.setExpireTime(System.currentTimeMillis() + MINUTE_MILLIS);

        assertFalse(tokenService.verifyToken(loginAppUser));

        verify(redisCache).deleteObject(anyString());
    }

    @Test
    void revokeAllMemberSessionsDelegatesToRaceSafeRegistry() {
        RedisCache redisCache = mock(RedisCache.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        AppTokenService tokenService = tokenService(redisCache, registry);

        tokenService.revokeAllMemberSessions(42L);

        verify(registry).revokeAll(42L);
    }

    @Test
    void concurrentRevokePreventsInFlightTokenCreationFromReturningUsableSession() throws Exception {
        RedisCache redisCache = mock(RedisCache.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        AtomicBoolean currentEpoch = new AtomicBoolean(true);
        CountDownLatch tokenRegistered = new CountDownLatch(1);
        CountDownLatch memberRevoked = new CountDownLatch(1);
        when(registry.currentOrCreateEpoch(42L)).thenReturn(1L);
        doAnswer(invocation -> {
            tokenRegistered.countDown();
            assertTrue(memberRevoked.await(5, TimeUnit.SECONDS));
            return null;
        }).when(registry).registerToken(anyLong(), anyString(), anyLong());
        when(registry.isCurrentEpoch(42L, 1L)).thenAnswer(invocation -> currentEpoch.get());
        doAnswer(invocation -> {
            assertTrue(tokenRegistered.await(5, TimeUnit.SECONDS));
            currentEpoch.set(false);
            memberRevoked.countDown();
            return null;
        }).when(registry).revokeAll(42L);
        AppTokenService tokenService = tokenService(redisCache, registry);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setUserId(42L);
        loginAppUser.setSessionEpoch(1L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> create = executor.submit(() -> {
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.setRemoteAddr("127.0.0.1");
                request.addHeader("User-Agent", "JUnit");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                try {
                    return tokenService.createAppToken(loginAppUser);
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            });
            Future<?> revoke = executor.submit(() -> tokenService.revokeAllMemberSessions(42L));
            revoke.get(5, TimeUnit.SECONDS);

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> create.get(5, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof ServiceException);
            verify(redisCache).deleteObject(anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void memberMutationGuardPreventsNewTokenCreation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RedisCache redisCache = mock(RedisCache.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.currentOrCreateEpoch(42L))
                .thenThrow(new ServiceException("会员认证状态正在变更，请稍后重试"));
        AppTokenService tokenService = tokenService(redisCache, registry);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setUserId(42L);

        assertThrows(ServiceException.class, () -> tokenService.createAppToken(loginAppUser));

        verify(redisCache, never()).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
    }

    @Test
    void tokenCreationRejectsCredentialSnapshotFromOlderMemberEpoch() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RedisCache redisCache = mock(RedisCache.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.currentOrCreateEpoch(42L)).thenReturn(3L);
        AppTokenService tokenService = tokenService(redisCache, registry);
        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setUserId(42L);
        loginAppUser.setSessionEpoch(2L);

        assertThrows(ServiceException.class, () -> tokenService.createAppToken(loginAppUser));

        verify(redisCache, never()).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
    }

    private AppTokenService tokenService(RedisCache redisCache) {
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
        when(registry.isCurrentEpoch(anyLong(), anyLong())).thenReturn(true);
        return tokenService(redisCache, registry);
    }

    private AppTokenService tokenService(RedisCache redisCache, IAppSessionRegistryService registry) {
        AppTokenService tokenService = new AppTokenService();
        ReflectionTestUtils.setField(tokenService, "redisCache", redisCache);
        ReflectionTestUtils.setField(tokenService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(tokenService, "idleTime", IDLE_MINUTES);
        ReflectionTestUtils.setField(tokenService, "absoluteTime", ABSOLUTE_MINUTES);
        ReflectionTestUtils.setField(tokenService, "secret", "unit-test-secret-unit-test-secret-unit-test-secret");
        return tokenService;
    }
}
