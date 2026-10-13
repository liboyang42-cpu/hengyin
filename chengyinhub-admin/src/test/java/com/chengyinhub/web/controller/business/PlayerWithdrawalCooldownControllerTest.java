package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A3 冷静期时长的<b>专用</b>改写入口：审计 2 明写「冷却配置不是普通编辑接口」。
 *
 * <p>这一层要钉住三件事：①它挂的是专用权限，通用参数页的 {@code system:config:edit} 换不来；
 * ②理由与操作人真的传下去（本仓 LogAspect 不写请求体，不落库就查不到）；
 * ③非正整数在进 service 之前就被挡下。
 */
class PlayerWithdrawalCooldownControllerTest {

    private AnnotationConfigApplicationContext context;
    private IPlayerWithdrawalCooldownService cooldownService;
    private PlayerWithdrawalCooldownController controller;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        cooldownService = context.getBean(IPlayerWithdrawalCooldownService.class);
        controller = context.getBean(PlayerWithdrawalCooldownController.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    /** 通用参数页那把钥匙开不了这道门：这是资金政策参数，不是普通配置项。 */
    @Test
    void theGenericConfigPermissionCannotRewriteTheCooldown() {
        loginWith("system:config:edit");

        assertThrows(AccessDeniedException.class, () -> controller.rewrite(rewriteBody(24, "理由")));
        assertThrows(AccessDeniedException.class, () -> controller.currentConfig());

        verify(cooldownService, never()).rewriteCooldownHours(anyInt(), anyString(), anyLong());
    }

    /** 持专用权限时改得动，且理由与操作人一路传到 service（它负责落审计）。 */
    @Test
    void theDedicatedPermissionRewritesItAndCarriesTheReasonAndOperator() {
        loginWith("business:withdrawal:cooldown-config");
        when(cooldownService.rewriteCooldownHours(24, "投诉争议期按法务意见收窄", 99L)).thenReturn(48);

        AjaxResult result = controller.rewrite(rewriteBody(24, "投诉争议期按法务意见收窄"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cooldownService).rewriteCooldownHours(24, "投诉争议期按法务意见收窄", 99L);
    }

    /** hours 缺失时不进 service：那一步再报错就已经绕过了「必填」这个语义。 */
    @Test
    void aMissingHourCountIsRefusedBeforeItReachesTheService() {
        loginWith("business:withdrawal:cooldown-config");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("reason", "理由");

        assertEquals(500, controller.rewrite(body).get(AjaxResult.CODE_TAG));
        verify(cooldownService, never()).rewriteCooldownHours(anyInt(), anyString(), anyLong());
    }

    private static Map<String, Object> rewriteBody(int hours, String reason) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("hours", hours);
        body.put("reason", reason);
        return body;
    }

    private static void loginWith(String permission) {
        SysUser user = new SysUser();
        user.setUserName("cooldown-config-security-test");
        user.setPassword("ignored");
        LoginUser loginUser = new LoginUser(99L, 1L, user, new HashSet<>(Collections.singleton(permission)));
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
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
        IPlayerWithdrawalCooldownService cooldownService() {
            return mock(IPlayerWithdrawalCooldownService.class);
        }

        @Bean
        PlayerWithdrawalCooldownController cooldownController(IPlayerWithdrawalCooldownService service) {
            return new PlayerWithdrawalCooldownController(service);
        }
    }
}
