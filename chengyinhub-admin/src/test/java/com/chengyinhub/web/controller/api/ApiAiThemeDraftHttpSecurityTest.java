package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftExecuteReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftPrepareReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRecoveryItem;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.domain.vo.ai.AiThemePlan;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.IMerchantInsightService;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.config.SecurityConfig;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.security.handle.AuthenticationEntryPointImpl;
import com.chengyinhub.framework.security.handle.LogoutSuccessHandlerImpl;
import com.chengyinhub.framework.web.service.AppTokenService;
import com.chengyinhub.framework.web.service.SysPermissionService;
import com.chengyinhub.framework.web.service.TokenService;
import com.chengyinhub.system.mapper.SysUserMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP principal boundary; no SQL-owner, quota, provider, rate-limiter or storage acceptance claim. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "token.header=Authorization",
        "token.secret=" + ApiAiThemeDraftHttpSecurityTest.SIGNING_KEY,
        "token.expireTime=21600",
        "token.app.idleTime=1440",
        "token.app.absoluteTime=10080"
})
@ContextConfiguration(classes = {
        ApiAiThemeDraftHttpSecurityTest.HttpConfiguration.class,
        SecurityConfig.class, PermitAllUrlProperties.class, JwtAuthenticationTokenFilter.class,
        AuthenticationEntryPointImpl.class, LogoutSuccessHandlerImpl.class,
        AppTokenService.class, TokenService.class, ApiAiController.class
})
class ApiAiThemeDraftHttpSecurityTest
{
    // Synthetic 64-zero-byte signing key, scoped to this test context; never a production credential.
    static final String SIGNING_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";
    private static final long A = 11L, B = 22L, ADMIN = 9001L;
    private static final String PREPARE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String RID = "td2_0123456789abcdef0123456789abcdef";
    private static final long CURSOR = 777L;
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z");

    enum Route { PREPARE, EXECUTE, REQUEST, RECOVERY }
    enum InvalidCredential { MALFORMED, BAD_SIGNATURE, EXPIRED_JWT, EXPIRED_APP_SESSION }

    @Autowired private WebApplicationContext context;
    @Autowired private AppTokenService appTokens;
    @Autowired private TokenService adminTokens;
    @Autowired private RedisCache cache;
    @Autowired private IAppSessionRegistryService registry;
    @Autowired private SysUserMapper adminUsers;
    @Autowired private SysPermissionService adminPermissions;
    @Autowired private AiThemeDraftRequestService requests;
    @Autowired private IAiCreatorService creator;
    @Autowired private IMerchantInsightService insights;
    @Autowired private IMerchantAccessService merchants;
    private final Map<String, Object> sessions = new HashMap<>();
    private MockMvc mvc;

    @BeforeEach
    void fixture()
    {
        SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes();
        reset(cache, registry, adminUsers, adminPermissions, requests, creator, insights, merchants);
        sessions.clear();
        doAnswer(inv -> { sessions.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(cache).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
        when(cache.getCacheObject(anyString())).thenAnswer(inv -> sessions.get(inv.getArgument(0)));
        doAnswer(inv -> sessions.remove(inv.getArgument(0)) != null).when(cache).deleteObject(anyString());
        when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
        when(registry.isCurrentEpoch(anyLong(), eq(1L))).thenReturn(true);
        when(requests.prepare(anyLong(), any(AiThemeDraftPrepareReq.class))).thenAnswer(inv -> {
            assertActualAppPrincipal(inv); return view(false);
        });
        when(requests.execute(anyLong(), any(AiThemeDraftExecuteReq.class))).thenAnswer(inv -> {
            assertActualAppPrincipal(inv); return view(true);
        });
        when(requests.readIntent(anyLong(), anyString())).thenAnswer(inv -> {
            assertActualAppPrincipal(inv); return view(false);
        });
        when(requests.readPrepared(anyLong(), anyString())).thenAnswer(inv -> {
            assertActualAppPrincipal(inv); return view(false);
        });
        when(requests.readRecovery(anyLong(), nullable(Long.class))).thenAnswer(inv -> {
            assertActualAppPrincipal(inv);
            return List.of(new AiThemeDraftRecoveryItem(77L, PREPARE, RID,
                    AiThemeDraftIntentState.PREPARED, null, NOW, NOW.plusSeconds(3600), null, 1));
        });
        FilterChainProxy chain = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        JwtAuthenticationTokenFilter jwt = context.getBean(JwtAuthenticationTokenFilter.class);
        assertFalse(mockingDetails(jwt).isMock());
        assertFalse(mockingDetails(appTokens).isMock()); assertFalse(mockingDetails(adminTokens).isMock());
        assertTrue(chain.getFilterChains().stream().anyMatch(c -> c.getFilters().contains(jwt)));
        assertEquals(ApiAiController.class, context.getBean(ApiAiController.class).getClass());
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters((Filter) chain).build();
    }

    @AfterEach
    void clearThreadState()
    {
        SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); sessions.clear();
    }

    @ParameterizedTest @EnumSource(Route.class)
    void anonymousIsRejectedBeforeAnyBusinessCall(Route route) throws Exception
    {
        rejected(route, null);
    }

    static Stream<Arguments> invalidCredentials()
    {
        return Arrays.stream(Route.values()).flatMap(route -> Arrays.stream(InvalidCredential.values())
                .map(credential -> Arguments.of(route, credential)));
    }

    @ParameterizedTest @MethodSource("invalidCredentials")
    void invalidAppCredentialsAreRejectedBeforeAnyBusinessCall(Route route, InvalidCredential credential) throws Exception
    {
        LoginAppUser app = appUser(A);
        String issued = inLoginRequest(() -> appTokens.createAppToken(app));
        String token = switch (credential) {
            case MALFORMED -> "not-an-app-jwt";
            case BAD_SIGNATURE -> signedAppToken(app, Base64.getEncoder().encodeToString(
                    "B".repeat(64).getBytes(StandardCharsets.US_ASCII)), false);
            case EXPIRED_JWT -> signedAppToken(app, SIGNING_KEY, true);
            case EXPIRED_APP_SESSION -> {
                app.setAbsoluteExpireTime(System.currentTimeMillis() - 60_000);
                app.setExpireTime(app.getAbsoluteExpireTime());
                yield issued;
            }
        };
        rejected(route, token);
    }

    @ParameterizedTest @EnumSource(Route.class)
    void validAdminJwtCannotEnterAnAppRoute(Route route) throws Exception
    {
        SysUser user = new SysUser(); user.setUserId(ADMIN); user.setUserName("http-security-admin");
        user.setStatus("0"); user.setDelFlag("0");
        when(adminUsers.selectUserById(ADMIN)).thenReturn(user);
        when(adminPermissions.getMenuPermission(user)).thenReturn(Set.of("*:*:*"));
        LoginUser admin = new LoginUser(ADMIN, 1L, user, Set.of("*:*:*"));
        String token = inLoginRequest(() -> adminTokens.createToken(admin));
        MockHttpServletRequest backend = new MockHttpServletRequest();
        backend.setRequestURI("/system/http-security-proof");
        backend.addHeader("Authorization", Constants.TOKEN_PREFIX + token);
        assertEquals(ADMIN, adminTokens.getLoginUser(backend).getUserId(), "the rejected token really is valid for the admin parser");
        assertTrue(sessions.containsKey(CacheConstants.LOGIN_TOKEN_KEY + admin.getToken()));
        assertFalse(sessions.containsKey(CacheConstants.LOGIN_APP_TOKEN_KEY + admin.getToken()));
        rejected(route, token);
    }

    static Stream<Arguments> validActors()
    {
        return Arrays.stream(Route.values()).flatMap(route -> Stream.of(A, B).map(actor -> Arguments.of(route, actor)));
    }

    @ParameterizedTest @MethodSource("validActors")
    void validTokenPrincipalDeterminesActorDespiteOtherOwnerQueryOrGetBody(Route route, long actor) throws Exception
    {
        String token = inLoginRequest(() -> appTokens.createAppToken(appUser(actor)));
        MockHttpServletRequestBuilder request = at(route, false, actor == A ? B : A)
                .header("Authorization", Constants.TOKEN_PREFIX + token);
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verifyOnlyActorCall(route, actor);
    }

    @ParameterizedTest @EnumSource(value = Route.class, names = {"PREPARE", "EXECUTE"})
    void aBodyMemberIdCannotReplaceTheAuthenticatedActor(Route route) throws Exception
    {
        String token = inLoginRequest(() -> appTokens.createAppToken(appUser(A)));
        ResultActions result = mvc.perform(at(route, true, B).header("Authorization", Constants.TOKEN_PREFIX + token));
        int httpStatus = result.andReturn().getResponse().getStatus();
        if (httpStatus == 400) {
            // memberId is outside both record DTOs: strict JSON rejection and ignoring it are both safe.
            assertInstanceOf(HttpMessageNotReadableException.class, result.andReturn().getResolvedException());
            noBusinessCalls();
        } else {
            result.andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
            verifyOnlyActorCall(route, A);
        }
    }

    @Test
    void prepareKeyRecoveryAlsoDerivesOwnerOnlyFromTheActualAppPrincipal() throws Exception
    {
        String token = inLoginRequest(() -> appTokens.createAppToken(appUser(A)));
        String path = "/api/ai/theme/draft/request";
        mvc.perform(get(path).servletPath(path).param("prepareKey", PREPARE).param("memberId", Long.toString(B))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":" + B + "}")
                        .header("Authorization", Constants.TOKEN_PREFIX + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(requests).readPrepared(A, PREPARE);
        verifyNoMoreInteractions(requests); verifyNoInteractions(creator, insights, merchants);
    }

    private void rejected(Route route, String token) throws Exception
    {
        MockHttpServletRequestBuilder request = at(route, false, B);
        if (token != null) request.header("Authorization", Constants.TOKEN_PREFIX + token);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        noBusinessCalls();
    }

    private void noBusinessCalls()
    {
        // The sole draft service boundary is never entered, so no quota/model/intent write path is invoked.
        verifyNoInteractions(requests, creator, insights, merchants);
    }

    private void verifyOnlyActorCall(Route route, long actor)
    {
        switch (route) {
            case PREPARE -> verify(requests).prepare(eq(actor), eq(new AiThemeDraftPrepareReq(PREPARE, "night walk", 1, null)));
            case EXECUTE -> verify(requests).execute(eq(actor), eq(new AiThemeDraftExecuteReq(RID)));
            case REQUEST -> verify(requests).readIntent(actor, RID);
            case RECOVERY -> verify(requests).readRecovery(actor, CURSOR);
        }
        verifyNoMoreInteractions(requests); verifyNoInteractions(creator, insights, merchants);
    }

    private void assertActualAppPrincipal(InvocationOnMock invocation)
    {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication); assertTrue(authentication.isAuthenticated());
        LoginAppUser principal = assertInstanceOf(LoginAppUser.class, authentication.getPrincipal());
        Long actor = invocation.getArgument(0);
        assertEquals(actor, principal.getUserId()); assertEquals(actor, principal.getAppUser().getUserId());
        assertTrue(actor == A || actor == B);
    }

    private static MockHttpServletRequestBuilder at(Route route, boolean bodySpoof, long otherOwner)
    {
        String path = "/api/ai/theme/draft/" + route.name().toLowerCase(java.util.Locale.ROOT);
        MockHttpServletRequestBuilder request = (route == Route.PREPARE || route == Route.EXECUTE ? post(path) : get(path))
                .servletPath(path).param("memberId", Long.toString(otherOwner)).param("ownerId", Long.toString(otherOwner))
                .contentType(MediaType.APPLICATION_JSON);
        String spoof = bodySpoof ? ",\"memberId\":" + otherOwner : "";
        return switch (route) {
            case PREPARE -> request.content("{\"prepareKey\":\"" + PREPARE + "\",\"idea\":\"night walk\",\"productType\":1" + spoof + "}");
            case EXECUTE -> request.content("{\"requestId\":\"" + RID + "\"" + spoof + "}");
            case REQUEST -> request.param("requestId", RID).content("{\"memberId\":" + otherOwner + "}");
            case RECOVERY -> request.param("beforeId", Long.toString(CURSOR)).content("{\"memberId\":" + otherOwner + "}");
        };
    }

    private static LoginAppUser appUser(long id)
    {
        AppUser user = new AppUser(); user.setUserId(id); user.setUserName("member-" + id);
        LoginAppUser login = new LoginAppUser(id, user); login.setSessionEpoch(1L); return login;
    }

    private static String inLoginRequest(Supplier<String> create)
    {
        MockHttpServletRequest login = new MockHttpServletRequest();
        login.setRemoteAddr("127.0.0.1"); login.addHeader("User-Agent", "JUnit local HTTP security fixture");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(login));
        try { return create.get(); }
        finally { RequestContextHolder.resetRequestAttributes(); }
    }

    private static String signedAppToken(LoginAppUser app, String key, boolean expired)
    {
        var builder = Jwts.builder().setClaims(new HashMap<>(Map.of(Constants.LOGIN_APP_USER_KEY, app.getToken())));
        if (expired) builder.setExpiration(new Date(System.currentTimeMillis() - 60_000));
        return builder.signWith(SignatureAlgorithm.HS512, key).compact();
    }

    private static AiThemeDraftIntentView view(boolean succeeded)
    {
        AiThemeDraftResp response = new AiThemeDraftResp(); AiThemePlan plan = new AiThemePlan();
        plan.setTitle("draft"); response.setDraft(plan); response.setRemainingQuota(2);
        return new AiThemeDraftIntentView(RID, succeeded ? AiThemeDraftIntentState.SUCCEEDED : AiThemeDraftIntentState.PREPARED,
                false, true, succeeded, succeeded ? response : null, null, NOW.plusSeconds(3600),
                succeeded ? NOW.plusSeconds(86400) : null, 1);
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    static class HttpConfiguration
    {
        @Bean static BeanFactoryPostProcessor isolatedBusinessDoubles()
        {
            // Completed singleton mocks avoid autowiring the mocked concrete business service's DB dependencies.
            return factory -> {
                factory.registerSingleton("redisCache", mock(RedisCache.class));
                factory.registerSingleton("appSessionRegistryService", mock(IAppSessionRegistryService.class));
                factory.registerSingleton("sysUserMapper", mock(SysUserMapper.class));
                factory.registerSingleton("sysPermissionService", mock(SysPermissionService.class));
                factory.registerSingleton("aiCreatorService", mock(IAiCreatorService.class));
                factory.registerSingleton("themeDraftRequests", mock(AiThemeDraftRequestService.class));
                factory.registerSingleton("merchantInsightService", mock(IMerchantInsightService.class));
                factory.registerSingleton("merchantAccessService", mock(IMerchantAccessService.class));
            };
        }
        @Bean UserDetailsService userDetailsService() { return username -> null; }
        @Bean CorsFilter corsFilter() { return new CorsFilter(new UrlBasedCorsConfigurationSource()); }
    }
}
