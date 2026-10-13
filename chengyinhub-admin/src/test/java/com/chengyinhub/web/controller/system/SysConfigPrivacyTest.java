package com.chengyinhub.web.controller.system;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysConfig;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.chengyinhub.framework.web.service.PermissionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.domain.entity.SysUser;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SysConfigPrivacyTest.MethodSecurityFixture.class)
class SysConfigPrivacyTest {
    @Autowired private SysConfigController securedController;
    @Autowired private ISysConfigService securedConfigs;

    @Test void rawKeyEndpointCannotExposeSecretsIncludingInitialPassword() throws Exception {
        ISysConfigService configs = mock(ISysConfigService.class);
        SysConfigController controller = new SysConfigController();
        ReflectionTestUtils.setField(controller, "configService", configs);
        assertEquals(500, controller.getConfigKey("app.wechat.appsecret").get("code"));
        assertEquals(500, controller.getConfigKey("wx.msgpush.token").get("code"));
        assertEquals(500, controller.getConfigKey("sys.user.initPassword").get("code"));
        verifyNoInteractions(configs);
        String rule = SysConfigController.class.getMethod("getConfigKey", String.class)
                .getAnnotation(PreAuthorize.class).value();
        assertTrue(rule.contains("system:config:query"));
        assertEquals("@ss.hasPermi('system:config:query')", rule);
    }

    @ParameterizedTest
    @ValueSource(strings = {"system:user:add", "system:user:edit", "system:config:query"})
    void actualMethodSecurityNeverReturnsTheInitialPassword(String permission) {
        reset(securedConfigs);
        when(securedConfigs.selectConfigByKey("sys.user.initPassword")).thenReturn("fixture-initial-password");
        LoginUser principal = new LoginUser();
        principal.setUser(new SysUser());
        principal.setPermissions(Collections.singleton(permission));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/config/configKey/sys.user.initPassword");
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        try {
            if ("system:config:query".equals(permission)) {
                AjaxResult response = securedController.getConfigKey("sys.user.initPassword");
                assertEquals(500, response.get("code"));
                assertFalse(response.toString().contains("fixture-initial-password"));
            } else {
                assertThrows(AccessDeniedException.class, () -> securedController.getConfigKey("sys.user.initPassword"));
            }
            verify(securedConfigs, never()).selectConfigByKey(anyString());
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            attributes.requestCompleted();
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityFixture {
        @Bean SysConfigController sysConfigController() { return new SysConfigController(); }
        @Bean ISysConfigService configs() { return mock(ISysConfigService.class); }
        @Bean(name = "ss") PermissionService permissions() { return new PermissionService(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.wechat.appsecret", "sys.user.initPassword"})
    void detailMaskDoesNotReturnOriginalSecret(String key) {
        ISysConfigService configs = mock(ISysConfigService.class);
        SysConfig row = new SysConfig(); row.setConfigKey(key); row.setConfigValue("fixture-secret");
        when(configs.selectConfigById(5L)).thenReturn(row);
        SysConfigController controller = new SysConfigController();
        ReflectionTestUtils.setField(controller, "configService", configs);
        SysConfig response = (SysConfig) controller.getInfo(5L).get(AjaxResult.DATA_TAG);
        assertEquals("******", response.getConfigValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.wechat.appsecret", "sys.user.initPassword"})
    void editingCannotRenameExistingSecretIntoAnUnmaskedKey(String key) throws Exception {
        ISysConfigService configs = mock(ISysConfigService.class);
        SysConfig existing = config(5L, key, "fixture-original-secret");
        when(configs.selectConfigById(5L)).thenReturn(existing);
        when(configs.checkConfigKeyUnique(any())).thenReturn(true);
        SysConfigController controller = controller(configs);
        assertEquals(500, controller.edit(config(5L, "ordinary.setting", "******")).get("code"));
        verify(configs, never()).updateConfig(any());
        assertEquals("fixture-original-secret", existing.getConfigValue());
        assertEquals("@ss.hasPermi('system:config:edit')", SysConfigController.class.getMethod("edit", SysConfig.class)
                .getAnnotation(PreAuthorize.class).value(), "the existing edit permission must not expand");
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.wechat.appsecret", "sys.user.initPassword"})
    void sameSecretPlaceholderPreservesTheStoredValueAndResponseDoesNotExposeIt(String key) {
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigById(5L)).thenReturn(config(5L, key, "fixture-original-secret"));
        when(configs.checkConfigKeyUnique(any())).thenReturn(true);
        when(configs.updateConfig(any())).thenReturn(1);
        SysConfig incoming = config(5L, key, "******");
        AjaxResult response = editAsAuthorizedUser(controller(configs), incoming);
        assertEquals(200, response.get("code"));
        assertEquals("fixture-original-secret", incoming.getConfigValue());
        verify(configs).updateConfig(incoming);
        assertFalse(response.toString().contains("fixture-original-secret"));
    }

    @Test void ordinaryRenameAndRealSecretUpdateKeepExistingWriteBehavior() {
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigById(6L)).thenReturn(config(6L, "ordinary.old", "old-value"));
        when(configs.selectConfigById(5L)).thenReturn(config(5L, "app.wechat.appsecret", "fixture-original-secret"));
        when(configs.checkConfigKeyUnique(any())).thenReturn(true);
        when(configs.updateConfig(any())).thenReturn(1);
        SysConfigController controller = controller(configs);
        SysConfig renamed = config(6L, "ordinary.new", "new-value");
        assertEquals(200, editAsAuthorizedUser(controller, renamed).get("code"));
        assertEquals("new-value", renamed.getConfigValue());
        verify(configs).updateConfig(renamed);
        SysConfig secretUpdate = config(5L, "app.wechat.appsecret", "fixture-new-secret");
        AjaxResult response = editAsAuthorizedUser(controller, secretUpdate);
        assertEquals(200, response.get("code"));
        verify(configs).updateConfig(secretUpdate);
        assertEquals("fixture-new-secret", secretUpdate.getConfigValue());
        assertFalse(response.toString().contains("fixture-new-secret"));
    }

    private SysConfig config(Long id, String key, String value) {
        SysConfig config = new SysConfig(); config.setConfigId(id); config.setConfigKey(key); config.setConfigValue(value);
        return config;
    }

    private SysConfigController controller(ISysConfigService configs) {
        SysConfigController controller = new SysConfigController();
        ReflectionTestUtils.setField(controller, "configService", configs);
        return controller;
    }

    private AjaxResult editAsAuthorizedUser(SysConfigController controller, SysConfig input) {
        LoginUser principal = new LoginUser();
        SysUser user = new SysUser(); user.setUserName("fixture-editor"); principal.setUser(user);
        principal.setPermissions(Collections.singleton("system:config:edit"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
        try { return controller.edit(input); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
