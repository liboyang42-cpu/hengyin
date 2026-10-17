package com.chengyinhub.framework.web.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthenticationLogPrivacyTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedShortTokensAreNeverEchoedEvenWhenTheyFitTheOldPrefixLimit(boolean admin) {
        RedisCache cache = mock(RedisCache.class);
        Object service = service(admin, cache);
        captureFailure(admin, service, "private-short-token", null, true, () -> verifyNoInteractions(cache));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingCachedSessionDoesNotExposeItsUuidOrRedisKey(boolean admin) {
        RedisCache cache = mock(RedisCache.class);
        Object service = service(admin, cache);
        String sessionId = "private-session-uuid";
        captureFailure(admin, service, signedToken(service, sessionId), sessionId, false,
                () -> verify(cache).getCacheObject(anyString()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void caughtBackendErrorDoesNotEchoItsRawMessageOrSessionIdentifier(boolean admin) {
        RedisCache cache = mock(RedisCache.class);
        when(cache.getCacheObject(anyString())).thenThrow(new IllegalStateException("private-exception-detail"));
        Object service = service(admin, cache);
        captureFailure(admin, service, signedToken(service, "private-session-uuid"), "private-session-uuid", true, () -> {});
    }

    private Object service(boolean admin, RedisCache cache) {
        Object service = admin ? new TokenService() : new AppTokenService();
        ReflectionTestUtils.setField(service, "redisCache", cache);
        ReflectionTestUtils.setField(service, "secret", Base64.getEncoder().encodeToString(new byte[64]));
        ReflectionTestUtils.setField(service, "header", "Authorization");
        return service;
    }

    private String signedToken(Object service, String sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.LOGIN_USER_KEY, sessionId);
        claims.put(Constants.LOGIN_APP_USER_KEY, sessionId);
        return ReflectionTestUtils.invokeMethod(service, "createToken", claims);
    }

    private void captureFailure(boolean admin, Object service, String token, String sessionId, boolean error, Runnable verifyCache) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth-log-test");
        request.addHeader("Authorization", "Bearer " + token);
        Logger logger = (Logger) LoggerFactory.getLogger(service.getClass());
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            assertNull(admin ? ((TokenService) service).getLoginUser(request)
                    : ((AppTokenService) service).getLoginAppUser(request));
            verifyCache.run();
            for (ILoggingEvent event : appender.list) {
                String text = event.getFormattedMessage();
                assertEquals(Level.WARN, event.getLevel());
                assertTrue(text.contains("uri=/api/auth-log-test"));
                assertFalse(text.contains(token));
                assertFalse(text.contains("private-"));
                assertFalse(text.contains("tokenPrefix="));
                assertFalse(text.contains("userKey="));
                assertFalse(text.contains("uuid="));
                assertFalse(text.contains("error="));
                if (sessionId != null) assertFalse(text.contains(sessionId));
                assertNull(event.getThrowableProxy());
            }
            if (error) {
                assertEquals(1, appender.list.size(), "rejection must retain one diagnostic event");
                assertTrue(appender.list.get(0).getFormattedMessage().contains("errorType="));
            } else if (!admin) {
                assertEquals(1, appender.list.size(), "missing app session retains a route-only diagnostic");
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
