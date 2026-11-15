package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证未配置第三方登录或上游不可用时返回 503，而不是伪装成 500 服务端异常。 */
class ApiLoginUnconfiguredProviderStatusTest {

    @Test
    void unconfiguredNativeWechatAndAppleReturn503() throws Exception {
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("app.wechat.app.appid")).thenReturn("");
        when(configService.selectConfigByKey("app.wechat.app.appsecret")).thenReturn("");
        when(configService.selectConfigByKey("apple.clientId")).thenReturn("");

        ApiLoginController controller = new ApiLoginController();
        ReflectionTestUtils.setField(controller, "configService", configService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/login/wechat/app").param("code", "smoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SERVICE_UNAVAILABLE))
                .andExpect(jsonPath("$.msg").value("原生微信未配置"));

        mockMvc.perform(post("/api/login/apple").param("identityToken", "smoke.jwt.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SERVICE_UNAVAILABLE))
                .andExpect(jsonPath("$.msg").value("Apple登录未配置"));
    }

    @Test
    void loginUpstreamFailureReturns503() throws Exception {
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("app.wechat.appid")).thenReturn("mini-appid");
        when(configService.selectConfigByKey("app.wechat.appsecret")).thenReturn("mini-secret");
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheMap("wx_access_map")).thenReturn(Collections.emptyMap());

        ApiLoginController controller = new ApiLoginController() {
            @Override
            protected String sendHttpGet(String url) {
                throw new RuntimeException("connect timed out");
            }
        };
        ReflectionTestUtils.setField(controller, "configService", configService);
        ReflectionTestUtils.setField(controller, "redisCache", redisCache);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/login")
                        .param("username", "13800000000")
                        .param("password", "secret")
                        .param("code", "temp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
