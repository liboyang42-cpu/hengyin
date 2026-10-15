package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiLoginControllerLoginResponseTest {

    private static final Set<String> LOGIN_DATA_KEYS = new HashSet<>(
            Arrays.asList("id", "userType", "avatar", "nickname", "role"));

    private static final Set<String> USER_INFO_KEYS = new HashSet<>(Arrays.asList(
            "createBy", "createTime", "updateBy", "updateTime", "remark", "userId", "userName",
            "nickName", "email", "mobile", "sex", "avatar", "status", "delFlag", "loginIp",
            "loginDate", "userType"));

    private MockMvc mockMvc;
    private LoginAppUser loginAppUser;

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
        loginAppUser = new LoginAppUser(42L, appUserWithSensitiveFields());
        ApiLoginController controller = new StubOpenIdLoginController(loginAppUser);
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

    @Test
    void userInfoReturnsOnlyApprovedFieldsWithoutChangingSession() throws Exception {
        AppUser sessionUser = loginAppUser.getAppUser();
        String body = mockMvc.perform(post("/api/userInfo"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JSONObject response = JSON.parseObject(body);
        assertEquals(200, response.getIntValue("code"));
        JSONObject appUser = response.getJSONObject("appUser");
        assertEquals(USER_INFO_KEYS, appUser.keySet());
        assertEquals(42L, appUser.getLongValue("userId"));
        assertEquals("13800000000", appUser.getString("userName"));
        assertEquals(1, appUser.getIntValue("userType"));
        assertFalse(body.contains("fixture-password-hash"));
        assertFalse(body.contains("fixture-password-salt"));
        assertFalse(body.contains("fixture-search-value"));
        // 会话里的对象不被裁剪：口令仍在服务端会话中供既有逻辑使用。
        assertSame(sessionUser, loginAppUser.getAppUser());
        assertEquals("fixture-password-hash", sessionUser.getPassword());
    }

    private AppUser appUserWithSensitiveFields() {
        AppUser appUser = new AppUser();
        appUser.setUserId(42L);
        appUser.setUserName("13800000000");
        appUser.setNickName("城瘾用户");
        appUser.setMobile("13800000000");
        appUser.setAvatar("avatar.png");
        appUser.setStatus("0");
        appUser.setDelFlag("0");
        appUser.setUserType(1);
        appUser.setLoginDate(new Date());
        appUser.setPassword("fixture-password-hash");
        appUser.setSalt("fixture-password-salt");
        appUser.setSearchValue("fixture-search-value");
        return appUser;
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
        private final LoginAppUser session;

        StubOpenIdLoginController(LoginAppUser session) {
            this.session = session;
        }

        @Override
        public LoginAppUser getLoginAppUser() {
            return session;
        }

        @Override
        protected String getOpenID(String code) {
            return "existing-openid";
        }
    }
}
