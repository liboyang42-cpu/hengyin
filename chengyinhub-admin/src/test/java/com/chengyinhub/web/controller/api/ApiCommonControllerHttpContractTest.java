package com.chengyinhub.web.controller.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.http.HttpRequestException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiCommonControllerHttpContractTest {

    @Test
    void tokenRequestUsesStableJsonPostAndNeverPutsSecretInUrl() {
        AppUploadService service = new AppUploadService();
        try (org.mockito.MockedStatic<com.chengyinhub.common.utils.http.HttpUtils> http =
                org.mockito.Mockito.mockStatic(com.chengyinhub.common.utils.http.HttpUtils.class)) {
            http.when(() -> com.chengyinhub.common.utils.http.HttpUtils.sendPost(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq("application/json"))).thenAnswer(invocation -> {
                org.junit.jupiter.api.Assertions.assertEquals("https://api.weixin.qq.com/cgi-bin/stable_token", invocation.getArgument(0));
                com.alibaba.fastjson2.JSONObject request = com.alibaba.fastjson2.JSONObject.parseObject(invocation.getArgument(1));
                org.junit.jupiter.api.Assertions.assertEquals("wx-fixture", request.getString("appid"));
                org.junit.jupiter.api.Assertions.assertEquals("fixture-secret", request.getString("secret"));
                org.junit.jupiter.api.Assertions.assertEquals("client_credential", request.getString("grant_type"));
                org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, request.getBoolean("force_refresh"));
                return "{\"access_token\":\"fixture-token\",\"expires_in\":7200}";
            });
            assertTrue(service.requestWxAccessToken("wx-fixture", "fixture-secret").contains("fixture-token"));
            http.verify(() -> com.chengyinhub.common.utils.http.HttpUtils.sendGet(org.mockito.ArgumentMatchers.anyString()), org.mockito.Mockito.never());
        }
    }

    @Test
    void disabledImageSecurityRefusesBeforeUploadAndTokenFetch() {
        AppUploadService service = new AppUploadService();
        com.chengyinhub.common.config.WxPayConfig config = new com.chengyinhub.common.config.WxPayConfig();
        ReflectionTestUtils.setField(config, "secCheckEnabled", false);
        ReflectionTestUtils.setField(service, "wxConfig", config);
        org.springframework.mock.web.MockMultipartFile image = new org.springframework.mock.web.MockMultipartFile(
                "file", "image.png", "image/png", new byte[] {1});
        org.junit.jupiter.api.Assertions.assertThrows(UploadSecurityUnavailableException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "verifyContentSecurity", image));
    }

    @Test
    void tokenNetworkFailureBlocksUploadWithoutLoggingCredentials() {
        String secret = "app-secret-must-not-log";
        FailingTokenService service = new FailingTokenService(secret);
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject("wx:mini:access_token")).thenReturn(null);
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        Logger logger = (Logger) LoggerFactory.getLogger(service.getClass());
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        try {
            assertNull(service.getWxAccessToken("wx-appid", secret));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertFalse(logs.contains(secret));
            assertFalse(logs.contains("token=provider-token-must-not-log"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    private static final class FailingTokenService extends AppUploadService {
        private final String secret;

        private FailingTokenService(String secret) {
            this.secret = secret;
        }

        @Override
        String requestWxAccessToken(String appId, String secret) {
            throw new HttpRequestException("provider url secret=" + this.secret
                    + " token=provider-token-must-not-log", 0, false);
        }
    }
}
