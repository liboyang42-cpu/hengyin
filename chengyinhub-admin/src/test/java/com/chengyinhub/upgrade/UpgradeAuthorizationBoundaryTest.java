package com.chengyinhub.upgrade;

import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.config.SecurityConfig;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.security.handle.AuthenticationEntryPointImpl;
import com.chengyinhub.framework.security.handle.LogoutSuccessHandlerImpl;
import com.chengyinhub.framework.web.service.AppTokenService;
import com.chengyinhub.framework.web.service.TokenService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real production authorization chain; token lookups are deterministic in-memory test seams. */
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
        UpgradeAuthorizationBoundaryTest.TestConfiguration.class,
        SecurityConfig.class,
        PermitAllUrlProperties.class,
        JwtAuthenticationTokenFilter.class,
        AuthenticationEntryPointImpl.class
})
class UpgradeAuthorizationBoundaryTest {

    private static final String AUTHENTICATED_REQUEST = "Bearer upgrade-test-fixture";

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters((Filter) applicationContext.getBean("springSecurityFilterChain"))
                .build();
    }

    @AfterEach
    void clearRequestContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/template/homeData",
            "POST, /api/template/list",
            "POST, /api/template/info",
            "POST, /api/template/topic-template/list",
            "POST, /api/template/topic-template/info",
            "POST, /api/category/list",
            "GET, /api/official/events",
            "GET, /api/official/events/42",
            "GET, /api/roam/share/snapshot",
            "POST, /api/merchant/list",
            "POST, /api/merchant/public-detail",
            "POST, /api/merchant/public-home",
            "POST, /api/user/public-info",
            "POST, /api/login",
            "POST, /api/login/code",
            "POST, /api/login/phone",
            "POST, /api/login/wechat/app",
            "POST, /api/login/apple",
            "GET, /api/common/config",
            "POST, /api/common/config",
            "GET, /index.html",
            "GET, /assets/docs/index.html",
            "GET, /assets/nested/theme.css",
            "GET, /assets/nested/app.js",
            "GET, /profile/images/avatar.png",
            "GET, /api/upgrade/anonymous/items/42",
            "GET, /api/upgrade/class/items/42"
    })
    void exactExistingPublicRulesRemainAnonymous(String method, String path) throws Exception {
        mockMvc.perform(at(method, path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/template/homeData",
            "GET, /api/template/list",
            "GET, /api/template/info",
            "GET, /api/template/topic-template/list",
            "GET, /api/template/topic-template/info",
            "GET, /api/category/list",
            "POST, /api/official/events",
            "POST, /api/official/events/42",
            "POST, /api/official/events/42/signup",
            "GET, /api/official/events/42/signup",
            "POST, /api/official/events/42/complete",
            "GET, /api/official/events/42/complete",
            "GET, /api/official/events/42/private/details",
            "POST, /api/template/publish",
            "POST, /api/template/draft",
            "POST, /api/template/my-list",
            "POST, /api/template/myinfo",
            "POST, /api/template/groupByCategory",
            "POST, /api/template/topic-template/use",
            "POST, /api/template/info/extra",
            "POST, /api/template/List",
            "POST, /api/category/update",
            "POST, /api/roam/share/snapshot",
            "GET, /api/roam/share/snapshot/extra",
            "POST, /api/roam/share/revoke",
            "GET, /api/merchant/list",
            "POST, /api/merchant/list/extra",
            "POST, /api/merchant/decor/save",
            "POST, /api/merchant/public-home/extra",
            "POST, /api/user/info",
            "POST, /api/user/update",
            "POST, /api/user/public-info/extra",
            "POST, /api/login/withopenid",
            "POST, /api/login/future-entry",
            "POST, /api/login/code/extra",
            "GET, /api/upgrade/anonymous/items/42/private",
            "GET, /api/upgrade/anonymous/manage",
            "GET, /api/upgrade/class/items/42/private",
            "GET, /api/upgrade/class/manage",
            "GET, /api/unlisted/protected"
    })
    void privateSiblingsNestedPathsAndWrongMethodsStillRequireAuthentication(String method, String path)
            throws Exception {
        mockMvc.perform(at(method, path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/common/upload",
            "POST, /api/common/uploads",
            "POST, /api/common/uploadOSS",
            "GET, /api/common/upload",
            "GET, /api/common/uploads",
            "GET, /api/common/uploadOSS"
    })
    void uploadAuthenticationRulesTakePrecedenceOverAnonymousCommonPrefix(String method, String path)
            throws Exception {
        mockMvc.perform(at(method, path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(at(method, path).header("Authorization", AUTHENTICATED_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/template/publish", "/api/template/myinfo", "/api/official/events/42/signup",
            "/api/official/events/42/complete", "/api/roam/share/snapshot",
            "/api/merchant/decor/save", "/api/user/update", "/api/unlisted/protected"
    })
    void privateRoutesAreAuthenticatedRatherThanUnconditionallyDenied(String path) throws Exception {
        mockMvc.perform(at("POST", path).header("Authorization", AUTHENTICATED_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /assets/nested/index.html",
            "POST, /assets/nested/theme.css",
            "POST, /assets/nested/app.js",
            "POST, /profile/images/avatar.png",
            "GET, /assets/nested/index.HTML",
            "GET, /assets/nested/index.html/extra",
            "GET, /assets/nested/app.js.map",
            "GET, /profiles/images/avatar.png"
    })
    void staticResourcesKeepGetSuffixCaseAndPrefixBoundaries(String method, String path) throws Exception {
        // The legacy admin entry point deliberately uses HTTP 200 with an AjaxResult 401 body.
        mockMvc.perform(at(method, path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void contextPathDoesNotChangeAuthorizationMatching() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/chengyin/api/template/list")
                        .contextPath("/chengyin").servletPath("/api/template/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(request(HttpMethod.GET, "/chengyin/api/official/events/42")
                        .contextPath("/chengyin").servletPath("/api/official/events/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(request(HttpMethod.POST, "/chengyin/api/template/publish")
                        .contextPath("/chengyin").servletPath("/api/template/publish"))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(request(HttpMethod.POST, "/chengyin/api/common/upload")
                        .contextPath("/chengyin").servletPath("/api/common/upload"))
                .andExpect(jsonPath("$.code").value(401));
    }

    private static MockHttpServletRequestBuilder at(String method, String path) {
        // MockMvc does not otherwise populate the servletPath consumed by the legacy matcher.
        return request(HttpMethod.valueOf(method), path).servletPath(path);
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
        IAppSessionRegistryService appSessionRegistryService() {
            return mock(IAppSessionRegistryService.class);
        }

        @Bean
        TokenService tokenService() {
            return mock(TokenService.class);
        }

        @Bean
        AppTokenService appTokenService() {
            AppTokenService service = mock(AppTokenService.class);
            AppUser appUser = new AppUser();
            appUser.setUserId(42L);
            appUser.setUserName("upgrade-fixture");
            LoginAppUser principal = new LoginAppUser(42L, appUser);
            when(service.getLoginAppUser(any(HttpServletRequest.class)))
                    .thenAnswer(invocation -> AUTHENTICATED_REQUEST.equals(
                            ((HttpServletRequest) invocation.getArgument(0)).getHeader("Authorization"))
                            ? principal : null);
            when(service.verifyToken(principal)).thenReturn(true);
            return service;
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
        RouteProbeController routeProbeController() {
            return new RouteProbeController();
        }

        @Bean
        ClassAnonymousController classAnonymousController() {
            return new ClassAnonymousController();
        }
    }

    @RestController
    static class RouteProbeController {
        // This handler is not anonymous. Any authorized route succeeds, so 404/405 cannot hide a bypass.
        @RequestMapping("/**")
        AjaxResult probe() {
            return AjaxResult.success();
        }

        @Anonymous
        @GetMapping("/api/upgrade/anonymous/items/{id}")
        AjaxResult annotatedProbe() {
            return AjaxResult.success();
        }
    }

    @Anonymous
    @RestController
    @RequestMapping("/api/upgrade/class")
    static class ClassAnonymousController {
        @GetMapping("/items/{id}")
        AjaxResult annotatedProbe() {
            return AjaxResult.success();
        }
    }
}
