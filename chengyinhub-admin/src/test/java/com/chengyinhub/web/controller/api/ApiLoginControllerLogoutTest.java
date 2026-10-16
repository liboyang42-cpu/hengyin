package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.LoginResponseVO;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.chengyinhub.framework.web.service.AppTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ApiLoginControllerLogoutTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void concurrentLogoutRequestsAreIdempotentWithoutChangingMemberIdentityBindings() throws Exception {
        RedisCache redisCache = mock(RedisCache.class);
        CountDownLatch concurrentDeletes = new CountDownLatch(2);
        when(redisCache.deleteObject(anyString())).thenAnswer(invocation -> {
            concurrentDeletes.countDown();
            assertEquals(true, concurrentDeletes.await(5, TimeUnit.SECONDS));
            return true;
        });
        AppTokenService tokenService = new AppTokenService();
        ReflectionTestUtils.setField(tokenService, "redisCache", redisCache);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
        when(registry.isCurrentEpoch(anyLong(), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(tokenService, "appSessionRegistryService", registry);
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        ApiLoginController controller = new ApiLoginController();
        ReflectionTestUtils.setField(controller, "appTokenService", tokenService);
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);

        AppUser appUser = new AppUser();
        appUser.setUserId(42L);
        LoginAppUser loginAppUser = new LoginAppUser(42L, appUser);
        loginAppUser.setToken("current-session-id");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> logout(controller, loginAppUser));
            Future<Object> second = executor.submit(() -> logout(controller, loginAppUser));
            assertEquals(200, first.get(5, TimeUnit.SECONDS));
            assertEquals(200, second.get(5, TimeUnit.SECONDS));
            verify(redisCache, times(2)).deleteObject(CacheConstants.LOGIN_APP_TOKEN_KEY + "current-session-id");
            verifyNoInteractions(memberService);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameTokenAuthenticatesBeforeLogoutAndIsRejectedAfterLogout() throws Exception {
        AtomicReference<LoginAppUser> storedSession = new AtomicReference<>();
        RedisCache redisCache = mock(RedisCache.class);
        doAnswer(invocation -> {
            storedSession.set(invocation.getArgument(1));
            return null;
        }).when(redisCache).setCacheObject(anyString(), any(LoginAppUser.class), anyInt(), any(TimeUnit.class));
        when(redisCache.getCacheObject(anyString())).thenAnswer(invocation -> storedSession.get());
        doAnswer(invocation -> {
            storedSession.set(null);
            return true;
        }).when(redisCache).deleteObject(anyString());
        AppTokenService tokenService = tokenService(redisCache);
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("127.0.0.1");
        loginRequest.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(loginRequest));
        AppUser appUser = new AppUser();
        appUser.setUserId(42L);
        LoginAppUser loginAppUser = new LoginAppUser(42L, appUser);
        loginAppUser.setSessionEpoch(1L);
        String jwt = tokenService.createAppToken(loginAppUser);
        JwtAuthenticationTokenFilter filter = new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(filter, "appTokenService", tokenService);

        filter.doFilter(authenticatedRequest(jwt), new MockHttpServletResponse(), new MockFilterChain());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());

        IUmsMemberService memberService = mock(IUmsMemberService.class);
        ApiLoginController controller = new ApiLoginController();
        ReflectionTestUtils.setField(controller, "appTokenService", tokenService);
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
        controller.logout();
        SecurityContextHolder.clearContext();

        filter.doFilter(authenticatedRequest(jwt), new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(memberService);
    }

    @Test
    void codeLoginAfterLogoutReturnsOriginalMemberWithoutChangingOpenIdOrCreatingDuplicate() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember original = new UmsMember();
        original.setId(42L);
        original.setWxOpenId("existing-openid");
        original.setStatus(1L);
        original.setDelFlag(0);
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(original));
        when(memberService.getOrCreateWechatMember("existing-openid")).thenReturn(original);
        AppLoginService loginService = mock(AppLoginService.class);
        when(loginService.login("existing-openid")).thenReturn("fresh-token");
        AppTokenService tokenService = mock(AppTokenService.class);
        ApiLoginController controller = new StubOpenIdLoginController();
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
        ReflectionTestUtils.setField(controller, "appLoginService", loginService);
        ReflectionTestUtils.setField(controller, "appTokenService", tokenService);

        AppUser appUser = new AppUser();
        appUser.setUserId(42L);
        LoginAppUser currentSession = new LoginAppUser(42L, appUser);
        currentSession.setToken("old-session-id");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentSession, null));
        assertEquals(200, controller.logout().get("code"));
        SecurityContextHolder.clearContext();

        Object data = controller.logincode("fresh-code").get("data");

        assertEquals(42L, ((LoginResponseVO) data).getId());
        assertEquals("existing-openid", original.getWxOpenId());
        verify(memberService, never()).insertUmsMember(any(UmsMember.class));
    }

    private MockHttpServletRequest authenticatedRequest(String jwt) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/protected");
        request.addHeader("Authorization", Constants.TOKEN_PREFIX + jwt);
        return request;
    }

    private Object logout(ApiLoginController controller, LoginAppUser loginAppUser) {
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(loginAppUser, null));
            return controller.logout().get("code");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private AppTokenService tokenService(RedisCache redisCache) {
        AppTokenService tokenService = new AppTokenService();
        ReflectionTestUtils.setField(tokenService, "redisCache", redisCache);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
        when(registry.isCurrentEpoch(anyLong(), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(tokenService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(tokenService, "header", "Authorization");
        ReflectionTestUtils.setField(tokenService, "secret",
                "unit-test-secret-unit-test-secret-unit-test-secret");
        ReflectionTestUtils.setField(tokenService, "idleTime", 24 * 60);
        ReflectionTestUtils.setField(tokenService, "absoluteTime", 7 * 24 * 60);
        return tokenService;
    }

    private static class StubOpenIdLoginController extends ApiLoginController {
        @Override
        protected String getOpenID(String code) {
            return "existing-openid";
        }
    }
}
