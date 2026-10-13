package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiLoginControllerHttpFailureTest {

    private static final String LEAKED_DETAIL =
            "https://api.weixin.qq.com/failure?secret=do-not-leak&code=credential";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("app.wechat.appid")).thenReturn("mini-appid");
        when(configService.selectConfigByKey("app.wechat.appsecret")).thenReturn("mini-secret");
        when(configService.selectConfigByKey("app.wechat.app.appid")).thenReturn("native-appid");
        when(configService.selectConfigByKey("app.wechat.app.appsecret")).thenReturn("native-secret");
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheMap("wx_access_map")).thenReturn(Collections.emptyMap());

        ApiLoginController controller = new FailingHttpLoginController();
        ReflectionTestUtils.setField(controller, "configService", configService);
        ReflectionTestUtils.setField(controller, "redisCache", redisCache);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void miniProgramCodeLoginMapsNetworkFailureToSafeUserMessage() throws Exception {
        mockMvc.perform(post("/api/login/code").param("code", "temporary-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("微信服务暂时不可用，请稍后重试"))
                .andExpect(content().string(not(containsString(LEAKED_DETAIL))));
    }

    @Test
    void passwordLoginMapsOpenIdNetworkFailureWithoutInvokingLoginService() throws Exception {
        mockMvc.perform(post("/api/login")
                        .param("username", "13800000000")
                        .param("password", "password")
                        .param("code", "temporary-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("微信服务暂时不可用，请稍后重试"))
                .andExpect(content().string(not(containsString(LEAKED_DETAIL))));
    }

    @Test
    void phoneBindingMapsPostNetworkFailureToSafeUserMessage() throws Exception {
        mockMvc.perform(post("/api/getwxbindphone").param("code", "temporary-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("微信服务暂时不可用，请稍后重试"))
                .andExpect(content().string(not(containsString(LEAKED_DETAIL))));
    }

    @Test
    void nativeWechatLoginMapsNetworkFailureToSafeUserMessage() throws Exception {
        mockMvc.perform(post("/api/login/wechat/app").param("code", "temporary-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("微信服务暂时不可用，请稍后重试"))
                .andExpect(content().string(not(containsString(LEAKED_DETAIL))));
    }

    private static class FailingHttpLoginController extends ApiLoginController {
        @Override
        protected String sendHttpGet(String url) {
            throw new RuntimeException(LEAKED_DETAIL);
        }

        @Override
        protected String sendHttpPost(String url, String body) {
            return "";
        }

        @Override
        public Long getAppUserId() {
            return 42L;
        }

        @Override
        protected String getWxToken() {
            return "access-token";
        }
    }
}
