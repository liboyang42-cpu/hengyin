package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
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

/** 真实方法安全代理负控：缺少用户调证权限时，Controller 方法不得被调用。 */
class GrowthMemberDetailAuthorizationTest {

    private AnnotationConfigApplicationContext context;
    private IGrowthService growthService;
    private IUmsMemberService memberService;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        growthService = context.getBean(IGrowthService.class);
        memberService = context.getBean(IUmsMemberService.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void missingMemberQueryPermissionIsRejectedBeforeAnyRead() {
        loginWith("business:growth:list");
        GrowthController controller = context.getBean(GrowthController.class);

        assertThrows(AccessDeniedException.class, () -> controller.memberCenter(42L));

        verify(memberService, never()).selectUmsMemberById(42L);
        verify(growthService, never()).getCenter(42L);
    }

    @Test
    void memberQueryPermissionCanReadExistingMemberGrowth() {
        loginWith("business:member:query");
        UmsMember member = new UmsMember();
        member.setId(42L);
        when(memberService.selectUmsMemberById(42L)).thenReturn(member);
        GrowthController controller = context.getBean(GrowthController.class);

        AjaxResult result = controller.memberCenter(42L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(memberService).selectUmsMemberById(42L);
        verify(growthService).getCenter(42L);
    }

    private static void loginWith(String permission) {
        SysUser user = new SysUser();
        user.setUserName("growth-test");
        user.setPassword("ignored");
        LoginUser loginUser = new LoginUser(99L, 1L, user, new HashSet<>(Collections.singleton(permission)));
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
        IGrowthService growthService() {
            return mock(IGrowthService.class);
        }

        @Bean
        IUmsMemberService umsMemberService() {
            return mock(IUmsMemberService.class);
        }

        @Bean
        GrowthController growthController(IGrowthService growthService, IUmsMemberService umsMemberService) {
            GrowthController controller = new GrowthController();
            ReflectionTestUtils.setField(controller, "growthService", growthService);
            ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
            return controller;
        }
    }
}
