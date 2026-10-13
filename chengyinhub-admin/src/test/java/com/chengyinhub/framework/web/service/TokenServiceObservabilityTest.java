package com.chengyinhub.framework.web.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TokenServiceObservabilityTest {

    @AfterEach
    void resetRequestContext() {
    }

    @Test
    void malformedTokenLogsStructuredErrorAndReturnsNull() {
        RedisCache redisCache = mock(RedisCache.class);
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "redisCache", redisCache);
        ReflectionTestUtils.setField(tokenService, "secret", "unit-test-secret-unit-test-secret-unit-test-secret");
        ReflectionTestUtils.setField(tokenService, "header", "Authorization");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");
        request.addHeader("Authorization", "Bearer this-is-not-a-valid-jwt-token-at-all");

        Logger logger = (Logger) LoggerFactory.getLogger(TokenService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            LoginUser result = tokenService.getLoginUser(request);

            assertNull(result);

            List<ILoggingEvent> logs = appender.list;
            assertEquals(1, logs.size());
            ILoggingEvent event = logs.get(0);
            // 2026-08-01 契约修订:ERROR → WARN。客户端送来无法解析的 token 是**客户端**错误,
            // 不是服务端故障;记 ERROR 会让它进 file_error appender 并挤占真正的故障信号。
            assertEquals(Level.WARN, event.getLevel());

            String msg = event.getFormattedMessage();
            assertTrue(msg.contains("uri=/api/test"), "should contain uri");
            assertFalse(msg.contains("tokenPrefix="), "must not log token fragments");
            assertFalse(msg.contains("this-is-not"), "must not log a malformed token prefix");
            assertFalse(msg.contains("error="), "raw parser messages may contain submitted token data");
            assertTrue(msg.contains("errorType="), "should contain errorType");

            assertFalse(msg.contains("this-is-not-a-valid-jwt-token-at-all"),
                    "must not leak full token");

            // 契约修订:不再附堆栈。诊断所需的异常类型由上面的 errorType= 字段承载(下方断言),
            // 而 jjwt 解析失败的堆栈全落在 jjwt 内部与 servlet 调用链上,对定位零信息量,
            // 却是日志刷屏的主因(2026-08-01 生产 20 万行样本里 1757 条这样的堆栈,
            // 来源是开发版小程序把 dev-token 打到生产)。
            assertNull(event.getThrowableProxy(),
                    "不应附堆栈——异常类型已由 errorType 字段承载,堆栈只会刷屏");
            assertTrue(msg.contains("MalformedJwtException")
                    || msg.contains("UnsupportedJwtException")
                    || msg.contains("SignatureException"),
                    "errorType 必须点明是哪种 JWT 解析异常,否则等于没有可观测性");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
