package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.service.ICmsSettingService;
import com.chengyinhub.business.service.impl.CmsSettingServiceImpl;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.web.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 旧 setting 编辑权限不能绕开结算规则的收款账户校验。 */
class CmsSettingPlatformAccountAuthorizationGuardTest {

    private AnnotationConfigApplicationContext context;
    private CmsSettingMapper mapper;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        mapper = context.getBean(CmsSettingMapper.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void legacySettingEditPermissionCannotMutatePlatformSettlementAccount() {
        loginWith("business:setting:edit");
        CmsSetting payload = new CmsSetting();
        payload.setId(1L);
        payload.setPlatformMemberId(42L);
        CmsSettingController controller = context.getBean(CmsSettingController.class);

        assertThrows(ServiceException.class, () -> controller.edit(payload));

        verify(mapper, never()).updateCmsSetting(any());
    }

    private static void loginWith(String permission) {
        SysUser user = new SysUser();
        user.setUserName("legacy-setting-test");
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
        CmsSettingMapper cmsSettingMapper() {
            return mock(CmsSettingMapper.class);
        }

        @Bean
        ICmsSettingService cmsSettingService(CmsSettingMapper cmsSettingMapper) {
            CmsSettingServiceImpl service = new CmsSettingServiceImpl();
            ReflectionTestUtils.setField(service, "cmsSettingMapper", cmsSettingMapper);
            return service;
        }

        @Bean
        CmsSettingController cmsSettingController(ICmsSettingService cmsSettingService) {
            CmsSettingController controller = new CmsSettingController();
            ReflectionTestUtils.setField(controller, "cmsSettingService", cmsSettingService);
            return controller;
        }
    }
}
