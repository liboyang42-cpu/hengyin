package com.chengyinhub.framework.config;

import com.chengyinhub.business.domain.map.MapPublicResult;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.MapPublicService;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.security.handle.AuthenticationEntryPointImpl;
import com.chengyinhub.framework.security.handle.LogoutSuccessHandlerImpl;
import com.chengyinhub.framework.web.service.AppTokenService;
import com.chengyinhub.framework.web.service.TokenService;
import com.chengyinhub.web.controller.api.MapPublicController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 候选入口走现有 authenticated 链，不新增匿名权限。 */
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
        MapPublicSecurityChainTest.TestConfiguration.class,
        SecurityConfig.class,
        PermitAllUrlProperties.class,
        JwtAuthenticationTokenFilter.class,
        AuthenticationEntryPointImpl.class
})
class MapPublicSecurityChainTest {

    @Autowired
    private WebApplicationContext applicationContext;
    @Autowired
    private AppTokenService appTokenService;
    @Autowired
    private RedisCache redisCache;
    @Autowired
    private MapPublicService mapPublicService;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters((Filter) applicationContext.getBean("springSecurityFilterChain"))
                .build();
        clearInvocations(mapPublicService);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCallerCannotReadPublicLayersOrLegacyNearby() throws Exception {
        mockMvc.perform(post("/api/map/public-layers").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/map/nearby"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("登录状态已失效，请重新登录"));
        verify(mapPublicService, never()).query(anyLong(), any());
        Path current = Path.of(System.getProperty("user.dir"));
        Path root = Files.exists(current.resolve("chengyinhub-framework")) ? current : current.getParent();
        String security = Files.readString(root.resolve(
                "chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(security.contains("public-layers"));
    }

    @Test
    void appPlayerReachesTheReadHandler() throws Exception {
        MapPublicResult body = new MapPublicResult();
        body.setDatum("GCJ-02");
        when(mapPublicService.query(org.mockito.ArgumentMatchers.eq(42L), any())).thenReturn(body);
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("127.0.0.1");
        loginRequest.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(loginRequest));
        AppUser appUser = new AppUser();
        appUser.setUserId(42L);
        LoginAppUser loginAppUser = new LoginAppUser(42L, appUser);
        loginAppUser.setSessionEpoch(1L);
        String jwt = appTokenService.createAppToken(loginAppUser);

        mockMvc.perform(post("/api/map/public-layers")
                        .header("Authorization", Constants.TOKEN_PREFIX + jwt)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.datum").value("GCJ-02"));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {
        @Bean
        RedisCache redisCache() { return mock(RedisCache.class); }

        @Bean
        RedisTemplate<Object, Object> redisTemplate() { return mock(RedisTemplate.class); }

        @Bean
        AppTokenService appTokenService(RedisCache redisCache) {
            AppTokenService service = new AppTokenService();
            ReflectionTestUtils.setField(service, "redisCache", redisCache);
            ReflectionTestUtils.setField(service, "header", "Authorization");
            ReflectionTestUtils.setField(service, "secret", "unit-test-secret-unit-test-secret-unit-test-secret");
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
        TokenService tokenService() { return mock(TokenService.class); }

        @Bean
        com.chengyinhub.system.mapper.SysUserMapper sysUserMapper() {
            return mock(com.chengyinhub.system.mapper.SysUserMapper.class);
        }

        @Bean
        com.chengyinhub.framework.web.service.SysPermissionService sysPermissionService() {
            return mock(com.chengyinhub.framework.web.service.SysPermissionService.class);
        }

        @Bean
        com.chengyinhub.system.service.ISysRoleService sysRoleService() {
            return mock(com.chengyinhub.system.service.ISysRoleService.class);
        }

        @Bean
        com.chengyinhub.system.service.ISysMenuService sysMenuService() {
            return mock(com.chengyinhub.system.service.ISysMenuService.class);
        }

        @Bean
        UserDetailsService userDetailsService() { return username -> null; }

        @Bean
        LogoutSuccessHandlerImpl logoutSuccessHandler() { return mock(LogoutSuccessHandlerImpl.class); }

        @Bean
        CorsFilter corsFilter() { return new CorsFilter(new UrlBasedCorsConfigurationSource()); }

        @Bean
        MapPublicService mapPublicService() { return mock(MapPublicService.class); }

        @Bean
        MapPublicController mapPublicController() { return new MapPublicController(); }
    }
}
