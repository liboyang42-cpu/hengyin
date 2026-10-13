package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.PlatformSettlementAccountRequest;
import com.chengyinhub.business.domain.vo.PlatformSettlementAccountVO;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实方法安全代理负控：结算账户读取和修改都不得绕过结算规则权限。 */
class SettlementRulePlatformAccountAuthorizationTest {

    private AnnotationConfigApplicationContext context;
    private ISettlementRuleService settlementRuleService;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        settlementRuleService = context.getBean(ISettlementRuleService.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void missingEditPermissionIsRejectedBeforeChangingTheSettlementAccount() {
        loginWith("business:settlementrule:list");
        SettlementRuleController controller = context.getBean(SettlementRuleController.class);

        assertThrows(AccessDeniedException.class, () -> controller.updatePlatformAccount(request(42L)));

        verify(settlementRuleService, never()).updatePlatformSettlementAccount(42L);
    }

    @Test
    void missingListPermissionIsRejectedBeforeReadingTheSettlementAccount() {
        loginWith("business:settlementrule:edit");
        SettlementRuleController controller = context.getBean(SettlementRuleController.class);

        assertThrows(AccessDeniedException.class, controller::platformAccount);

        verify(settlementRuleService, never()).getPlatformSettlementAccount();
    }

    @Test
    void settlementRulePermissionsAllowTheirMatchingActions() {
        loginWith("business:settlementrule:list", "business:settlementrule:edit");
        when(settlementRuleService.updatePlatformSettlementAccount(42L)).thenReturn(1);
        PlatformSettlementAccountVO account = new PlatformSettlementAccountVO();
        account.setMemberId(42L);
        account.setConfigured(true);
        account.setUsable(true);
        when(settlementRuleService.getPlatformSettlementAccount()).thenReturn(account);
        SettlementRuleController controller = context.getBean(SettlementRuleController.class);

        AjaxResult readResult = controller.platformAccount();
        AjaxResult writeResult = controller.updatePlatformAccount(request(42L));

        assertEquals(200, readResult.get(AjaxResult.CODE_TAG));
        assertEquals(200, writeResult.get(AjaxResult.CODE_TAG));
        verify(settlementRuleService).getPlatformSettlementAccount();
        verify(settlementRuleService).updatePlatformSettlementAccount(42L);
    }

    private static PlatformSettlementAccountRequest request(Long memberId) {
        PlatformSettlementAccountRequest request = new PlatformSettlementAccountRequest();
        request.setMemberId(memberId);
        return request;
    }

    private static void loginWith(String... permissions) {
        SysUser user = new SysUser();
        user.setUserName("settlement-test");
        user.setPassword("ignored");
        LoginUser loginUser = new LoginUser(99L, 1L, user, new HashSet<>(java.util.Arrays.asList(permissions)));
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        SecurityContextHolder.setContext(securityContext);
    }

    @Configuration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {
        @Bean("ss")
        PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        ISettlementRuleService settlementRuleService() {
            return mock(ISettlementRuleService.class);
        }

        @Bean
        SettlementRuleController settlementRuleController(ISettlementRuleService settlementRuleService) {
            SettlementRuleController controller = new SettlementRuleController();
            ReflectionTestUtils.setField(controller, "settlementRuleService", settlementRuleService);
            return controller;
        }
    }
}
