package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.http.HttpRequestException;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.system.service.ISysConfigService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖 ApiLoginController 里直接调用 HttpUtils 的分支：网络层抛出 HttpRequestException 时
 * 必须显式降级(留日志/给用户明确提示),不能静默吞掉、也不能把 URL/token/body 带进日志或响应。
 */
class ApiLoginControllerHttpContractTest {

    private static final String SECRET_APP_SECRET = "super-secret-app-secret-must-not-leak";
    private static final String SECRET_ACCESS_TOKEN = "leaked-access-token-must-not-appear";

    @Test
    void getWxTokenNetworkFailureReturnsEmptyTokenWithoutThrowing() throws Exception {
        FailingWxController controller = new FailingWxController();
        ReflectionTestUtils.setField(controller, "configService", configService(SECRET_APP_SECRET));
        ReflectionTestUtils.setField(controller, "redisCache", emptyRedisCache());

        Logger logger = (Logger) LoggerFactory.getLogger(controller.getClass());
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        try {
            String token = controller.getWxToken(false);
            assertEquals("", token, "network failure fetching wx token should return empty string, not propagate");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test
    void getPhoneNumberReturnsExplicitErrorWithoutLeakingUrlOrTokenWhenNetworkFails() throws Exception {
        BindPhoneFailingController controller = new BindPhoneFailingController();
        ReflectionTestUtils.setField(controller, "configService", configService(SECRET_APP_SECRET));
        ReflectionTestUtils.setField(controller, "redisCache", emptyRedisCache());

        AjaxResult result = controller.getPhoneNumber("wx-code");

        assertEquals(500, result.get("code"));
        String message = String.valueOf(result.get("msg"));
        assertFalse(message.contains(SECRET_ACCESS_TOKEN), "error message must not leak access_token");
        assertFalse(message.contains("getuserphonenumber"), "error message must not leak the provider URL");
        assertFalse(message.contains(SECRET_APP_SECRET), "error message must not leak appsecret");
    }

    @Test
    void getPhoneNumberReturnsExplicitErrorWhenTokenFetchFails() throws Exception {
        FailingWxController controller = new FailingWxController();
        ReflectionTestUtils.setField(controller, "configService", configService(SECRET_APP_SECRET));
        ReflectionTestUtils.setField(controller, "redisCache", emptyRedisCache());

        AjaxResult result = controller.getPhoneNumber("wx-code");

        assertEquals(500, result.get("code"));
        assertEquals("微信服务暂时不可用，请稍后重试", result.get("msg"));
    }

    @Test
    void getPhoneNumberPutsBoundPhoneInDataNotMsg() throws Exception {
        BindPhoneSuccessController controller = new BindPhoneSuccessController();
        ReflectionTestUtils.setField(controller, "configService", configService(SECRET_APP_SECRET));
        ReflectionTestUtils.setField(controller, "redisCache", emptyRedisCache());
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        when(umsMemberService.selectUmsMemberList(any())).thenReturn(Collections.emptyList());
        when(umsMemberService.updateUmsMember(any())).thenReturn(1);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);

        AjaxResult result = controller.getPhoneNumber("wx-code");

        assertEquals(200, result.get("code"));
        assertEquals("13800001111", result.get("data"));
        assertNotEquals("13800001111", result.get("msg"));
        assertFalse(String.valueOf(result.get("msg")).matches("1[3-9]\\d{9}"));
    }

    @Test
    void loginWechatAppReturnsExplicitErrorWithoutLeakingUrlOrSecretWhenNetworkFails() {
        FailingWxController controller = new FailingWxController();
        ReflectionTestUtils.setField(controller, "configService", configService(SECRET_APP_SECRET));

        AjaxResult result = controller.loginWechatApp("app-code");

        assertEquals(500, result.get("code"));
        String message = String.valueOf(result.get("msg"));
        assertFalse(message.contains(SECRET_APP_SECRET), "error message must not leak appsecret");
        assertFalse(message.contains("oauth2/access_token"), "error message must not leak the provider URL");
    }

    @Test
    void logincodeReturnsGenericErrorWhenGetOpenIdNetworkFails() {
        FailingWxController controller = new FailingWxController();
        ReflectionTestUtils.setField(controller, "configService", configService(SECRET_APP_SECRET));

        AjaxResult result = controller.logincode("wx-code");

        assertEquals(500, result.get("code"));
        String message = String.valueOf(result.get("msg"));
        assertFalse(message.contains(SECRET_APP_SECRET), "error message must not leak appsecret");
        assertFalse(message.contains("jscode2session"), "error message must not leak the provider URL");
    }

    @Test
    void productionAlwaysUsesOfficialWechatButExplicitTestProfileCanUseConfiguredFixture() {
        ApiLoginController controller = new ApiLoginController();
        org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
        environment.setActiveProfiles("prod");
        ReflectionTestUtils.setField(controller, "environment", environment);
        ReflectionTestUtils.setField(controller, "wechatApiBaseUrl", "http://127.0.0.1:9999");
        assertEquals("https://api.weixin.qq.com", controller.wechatSessionBaseUrl());
        environment.setActiveProfiles("prod", "test");
        assertEquals("https://api.weixin.qq.com", controller.wechatSessionBaseUrl());
        environment.setActiveProfiles("test");
        assertEquals("http://127.0.0.1:9999", controller.wechatSessionBaseUrl());
        ReflectionTestUtils.setField(controller, "wechatApiBaseUrl", "http://user:pass@127.0.0.1");
        assertThrows(IllegalArgumentException.class, controller::wechatSessionBaseUrl);
    }

    private static ISysConfigService configService(String appSecret) {
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("app.wechat.appid")).thenReturn("wx-appid");
        when(configService.selectConfigByKey("app.wechat.appsecret")).thenReturn(appSecret);
        when(configService.selectConfigByKey("app.wechat.app.appid")).thenReturn("wx-app-appid");
        when(configService.selectConfigByKey("app.wechat.app.appsecret")).thenReturn(appSecret);
        return configService;
    }

    private static RedisCache emptyRedisCache() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheMap("wx_access_map")).thenReturn(null);
        return redisCache;
    }

    /** 所有 sendHttp* 调用一律模拟网络失败。 */
    private static class FailingWxController extends ApiLoginController {
        @Override
        public Long getAppUserId() {
            return 1L;
        }

        @Override
        protected String sendHttpGet(String url) {
            throw new HttpRequestException("third-party HTTP network failure", 0, false);
        }

        @Override
        protected String sendHttpPost(String url, String body) {
            throw new HttpRequestException("third-party HTTP network failure", 0, false);
        }
    }

    /** stable_token 换取成功,但换手机号那一段网络失败,用于 getPhoneNumber 场景。 */
    private static class BindPhoneFailingController extends ApiLoginController {
        @Override
        public Long getAppUserId() {
            return 1L;
        }

        @Override
        protected String sendHttpPost(String url, String body) {
            if (url.contains("stable_token")) {
                return "{\"access_token\":\"" + SECRET_ACCESS_TOKEN + "\",\"expires_in\":7200}";
            }
            throw new HttpRequestException("third-party HTTP network failure", 0, false);
        }
    }

    /** 微信换号成功,用于断言绑定手机写进 data 而不是 msg。 */
    private static class BindPhoneSuccessController extends ApiLoginController {
        @Override
        public Long getAppUserId() {
            return 1L;
        }

        @Override
        protected String sendHttpPost(String url, String body) {
            if (url.contains("stable_token")) {
                return "{\"access_token\":\"wx-access-token\",\"expires_in\":7200}";
            }
            if (url.contains("getuserphonenumber")) {
                return "{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"13800001111\"}}";
            }
            throw new IllegalStateException("unexpected url");
        }
    }
}
