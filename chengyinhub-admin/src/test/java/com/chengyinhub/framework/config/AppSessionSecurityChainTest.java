package com.chengyinhub.framework.config;

import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.security.handle.AuthenticationEntryPointImpl;
import com.chengyinhub.framework.security.handle.LogoutSuccessHandlerImpl;
import com.chengyinhub.framework.web.service.AppTokenService;
import com.chengyinhub.framework.web.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.Filter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "token.header=Authorization",
        "token.secret=unit-test-secret-unit-test-secret-unit-test-secret",
        "token.expireTime=21600",
        "token.app.idleTime=1440",
        "token.app.absoluteTime=10080"
})
@ContextConfiguration(classes = {
        AppSessionSecurityChainTest.TestConfiguration.class,
        SecurityConfig.class,
        PermitAllUrlProperties.class,
        JwtAuthenticationTokenFilter.class,
        AuthenticationEntryPointImpl.class
})
class AppSessionSecurityChainTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private AppTokenService appTokenService;

    @Autowired
    private RedisCache redisCache;

    private MockMvc mockMvc;
    private AtomicReference<LoginAppUser> storedSession;

    @BeforeEach
    void setUp() {
        storedSession = new AtomicReference<>();
        doAnswer(invocation -> {
            storedSession.set(invocation.getArgument(1));
            return null;
        }).when(redisCache).setCacheObject(anyString(), any(LoginAppUser.class), anyInt(), any(TimeUnit.class));
        when(redisCache.getCacheObject(anyString())).thenAnswer(invocation -> storedSession.get());
        doAnswer(invocation -> {
            storedSession.set(null);
            return true;
        }).when(redisCache).deleteObject(anyString());
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters((Filter) applicationContext.getBean("springSecurityFilterChain"))
                .build();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void loggedOutTokenReceivesHttp401ThroughConfiguredSecurityChain() throws Exception {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("127.0.0.1");
        loginRequest.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(loginRequest));
        AppUser appUser = new AppUser();
        appUser.setUserId(42L);
        LoginAppUser loginAppUser = new LoginAppUser(42L, appUser);
        loginAppUser.setSessionEpoch(1L);
        String jwt = appTokenService.createAppToken(loginAppUser);

        mockMvc.perform(get("/api/protected")
                        .header("Authorization", Constants.TOKEN_PREFIX + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        appTokenService.delLoginAppUser(loginAppUser.getToken());

        mockMvc.perform(get("/api/protected")
                        .header("Authorization", Constants.TOKEN_PREFIX + jwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unauthenticatedBodyDoesNotLeakRequestUriOrAuthTerms() throws Exception {
        mockMvc.perform(get("/api/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("登录状态已失效，请重新登录"));
    }

    @Test
    void backendUnauthenticatedResponseKeepsExistingHttp200Body401Contract() throws Exception {
        mockMvc.perform(get("/system/protected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("登录状态已失效，请重新登录"));

        mockMvc.perform(get("/system/middle/api/protected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("登录状态已失效，请重新登录"));
    }

    @Test
    void appTokenCannotAuthenticateNonAppUriContainingApiInTheMiddle() throws Exception {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("127.0.0.1");
        loginRequest.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(loginRequest));
        AppUser appUser = new AppUser();
        appUser.setUserId(42L);
        LoginAppUser loginAppUser = new LoginAppUser(42L, appUser);
        loginAppUser.setSessionEpoch(1L);
        String jwt = appTokenService.createAppToken(loginAppUser);

        mockMvc.perform(get("/system/middle/api/protected")
                        .header("Authorization", Constants.TOKEN_PREFIX + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {

        @Bean
        RedisCache redisCache() {
            return mock(RedisCache.class);
        }

        @Bean
        RedisTemplate<Object, Object> redisTemplate() {
            return mock(RedisTemplate.class);
        }

        @Bean
        AppTokenService appTokenService(RedisCache redisCache) {
            AppTokenService service = new AppTokenService();
            ReflectionTestUtils.setField(service, "redisCache", redisCache);
            ReflectionTestUtils.setField(service, "header", "Authorization");
            ReflectionTestUtils.setField(service, "secret",
                    "unit-test-secret-unit-test-secret-unit-test-secret");
            ReflectionTestUtils.setField(service, "idleTime", 24 * 60);
            ReflectionTestUtils.setField(service, "absoluteTime", 7 * 24 * 60);
            return service;
        }

        @Bean
        IAppSessionRegistryService appSessionRegistryService() {
            IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
            when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
            when(registry.isCurrentEpoch(anyLong(), anyLong())).thenReturn(true);
            return registry;
        }

        @Bean
        TokenService tokenService() {
            return mock(TokenService.class);
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> null;
        }

        @Bean
        LogoutSuccessHandlerImpl logoutSuccessHandler() {
            return mock(LogoutSuccessHandlerImpl.class);
        }

        @Bean
        CorsFilter corsFilter() {
            return new CorsFilter(new UrlBasedCorsConfigurationSource());
        }

        @Bean
        ProtectedController protectedController() {
            return new ProtectedController();
        }
    }

    @RestController
    static class ProtectedController {
        @GetMapping({"/api/protected", "/system/protected", "/system/middle/api/protected"})
        AjaxResult protectedResource() {
            return AjaxResult.success();
        }
    }
}
