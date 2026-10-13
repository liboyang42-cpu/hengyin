package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.framework.web.service.AppLoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiLoginControllerLoginResponseTest {

    private static final Set<String> LOGIN_DATA_KEYS = new HashSet<>(
            Arrays.asList("id", "userType", "avatar", "nickname", "role"));

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UmsMember member = memberWithSensitiveFields();
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        AppLoginService loginService = mock(AppLoginService.class);
        when(loginService.login("13800000000", "password", "existing-openid"))
                .thenReturn("password-token");
        when(loginService.login("existing-openid")).thenReturn("code-token");
        ApiLoginController controller = new StubOpenIdLoginController();
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
        ReflectionTestUtils.setField(controller, "appLoginService", loginService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void passwordLoginReturnsOnlyApprovedClientFields() throws Exception {
        String body = mockMvc.perform(post("/api/login")
                        .param("username", "13800000000")
                        .param("password", "password")
                        .param("code", "wx-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("password-token"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(LOGIN_DATA_KEYS, loginDataKeys(body));
    }

    @Test
    void codeLoginReturnsOnlyApprovedClientFields() throws Exception {
        String body = mockMvc.perform(post("/api/login/code").param("code", "wx-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("code-token"))
                .andExpect(jsonPath("$.data.id").value(42))
                .andReturn().getResponse().getContentAsString();

        assertEquals(LOGIN_DATA_KEYS, loginDataKeys(body));
    }

    private Set<String> loginDataKeys(String body) {
        JSONObject data = JSON.parseObject(body).getJSONObject("data");
        return data == null ? Collections.emptySet() : data.keySet();
    }

    private UmsMember memberWithSensitiveFields() {
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setUserType(1L);
        member.setAvatar("avatar.png");
        member.setNickname("城瘾用户");
        member.setRole("player");
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPassword("password-hash");
        member.setWxOpenId("existing-openid");
        member.setUnionId("union-id");
        member.setAppleId("apple-id");
        member.setLoginIp("127.0.0.1");
        member.setLoginTime(new Date());
        return member;
    }

    private static class StubOpenIdLoginController extends ApiLoginController {
        @Override
        protected String getOpenID(String code) {
            return "existing-openid";
        }
    }
}
