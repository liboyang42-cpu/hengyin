package com.chengyinhub.business.service.impl;

import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F-R2B-05：jscode2session 基址只在显式非生产 profile 下可切换，生产与未声明 profile 固定官方地址。 */
class WeChatMiniSessionServiceImplTest {

    private static final String CONFIGURED = "http://127.0.0.1:9999";

    private static class Capturing extends WeChatMiniSessionServiceImpl {
        String url;
        @Override protected String sendHttpGet(String url) {
            this.url = url;
            return "{\"openid\":\"fixture-openid\",\"session_key\":\"fixture-session\"}";
        }
    }

    private static Capturing service(MockEnvironment environment) {
        Capturing service = new Capturing();
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("app.wechat.appid")).thenReturn("fixture-appid");
        when(config.selectConfigByKey("app.wechat.appsecret")).thenReturn("fixture-secret");
        ReflectionTestUtils.setField(service, "configService", config);
        ReflectionTestUtils.setField(service, "environment", environment);
        return service;
    }

    @Test
    void productionAndUndeclaredProfilesAlwaysUseOfficialBase() {
        MockEnvironment none = new MockEnvironment().withProperty("chengyin.test.wechat-api-base-url", CONFIGURED);
        Capturing service = service(none);
        assertEquals("fixture-openid", service.exchange("code").getOpenId());
        assertTrue(service.url.startsWith("https://api.weixin.qq.com/sns/jscode2session?"), service.url);

        MockEnvironment prod = new MockEnvironment().withProperty("chengyin.test.wechat-api-base-url", CONFIGURED);
        prod.setActiveProfiles("prod", "test");
        assertEquals("https://api.weixin.qq.com", service(prod).sessionBaseUrl());

        assertEquals("https://api.weixin.qq.com", service(null).sessionBaseUrl());
    }

    @Test
    void explicitNonProductionProfileUsesConfiguredBase() {
        MockEnvironment test = new MockEnvironment().withProperty("chengyin.test.wechat-api-base-url", CONFIGURED + "/");
        test.setActiveProfiles("test");
        Capturing service = service(test);
        service.exchange("code");
        assertTrue(service.url.startsWith(CONFIGURED + "/sns/jscode2session?appid=fixture-appid&"), service.url);
    }

    @Test
    void malformedNonProductionBaseIsRejected() {
        for (String bad : new String[] {"ftp://127.0.0.1", "http://user:pass@127.0.0.1", "http://127.0.0.1/x",
                "http://127.0.0.1?a=1", "http://127.0.0.1#f"}) {
            MockEnvironment test = new MockEnvironment().withProperty("chengyin.test.wechat-api-base-url", bad);
            test.setActiveProfiles("dev");
            assertThrows(IllegalArgumentException.class, service(test)::sessionBaseUrl, bad);
        }
    }
}
