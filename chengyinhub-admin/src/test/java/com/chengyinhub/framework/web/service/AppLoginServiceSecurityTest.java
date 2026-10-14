package com.chengyinhub.framework.web.service;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppLoginServiceSecurityTest {

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static Stream<Arguments> blankPasswordLoginInputs() {
        return Stream.of(
                Arguments.of(null, "password", "openid"),
                Arguments.of(" ", "password", "openid"),
                Arguments.of("13800000000", "", "openid"),
                Arguments.of("13800000000", "password", "  ")
        );
    }

    @ParameterizedTest
    @MethodSource("blankPasswordLoginInputs")
    void rejectsBlankPasswordLoginInputsBeforeMemberLookup(String username, String password, String openid) {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loginService.login(username, password, openid));

        assertEquals("账号或者密码错误", exception.getMessage());
        verifyNoInteractions(memberService);
    }

    @ParameterizedTest
    @MethodSource("blankOpenIds")
    void rejectsBlankOpenIdLoginBeforeMemberLookup(String openid) {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);

        ServiceException exception = assertThrows(ServiceException.class, () -> loginService.login(openid));

        assertEquals("账号或者密码错误", exception.getMessage());
        verifyNoInteractions(memberService);
    }

    private static Stream<String> blankOpenIds() {
        return Stream.of((String) null, "", " ");
    }

    @Test
    void usesSameErrorForMissingAccountAndWrongCredentialsAcrossLoginModes() {
        IUmsMemberService missingMemberService = mock(IUmsMemberService.class);
        when(missingMemberService.selectUmsMemberList(any(UmsMember.class))).thenReturn(Collections.emptyList());
        AppLoginService missingLoginService = new AppLoginService();
        ReflectionTestUtils.setField(missingLoginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(missingLoginService, "appUserSevice", missingMemberService);

        IUmsMemberService wrongPasswordMemberService = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setPassword("stored-password");
        when(wrongPasswordMemberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        AppLoginService wrongPasswordLoginService = new AppLoginService();
        ReflectionTestUtils.setField(wrongPasswordLoginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(wrongPasswordLoginService, "appUserSevice", wrongPasswordMemberService);
        ReflectionTestUtils.setField(wrongPasswordLoginService, "appSessionRegistryService", stableRegistry());

        ServiceException missingPasswordAccount = assertThrows(ServiceException.class,
                () -> missingLoginService.login("13800000000", "password", "openid"));
        ServiceException wrongPassword = assertThrows(ServiceException.class,
                () -> wrongPasswordLoginService.login("13800000000", "password", "openid"));
        ServiceException missingOpenIdAccount = assertThrows(ServiceException.class,
                () -> missingLoginService.login("openid"));

        assertAll(
                () -> assertEquals("账号或者密码错误", missingPasswordAccount.getMessage()),
                () -> assertEquals("账号或者密码错误", wrongPassword.getMessage()),
                () -> assertEquals("账号或者密码错误", missingOpenIdAccount.getMessage())
        );
    }

    @Test
    void validPasswordLoginRejectsDifferentExistingOpenIdWithoutChangingBinding() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setPhone("13800000000");
        member.setPassword(SecurityUtils.encryptPassword("password"));
        member.setWxOpenId("existing-openid");
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", stableRegistry());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loginService.login("13800000000", "password", "different-openid"));

        assertEquals("账号或者密码错误", exception.getMessage());
        assertEquals("existing-openid", member.getWxOpenId());
        verify(memberService, never()).updateUmsMember(any(UmsMember.class));
    }

    @Test
    void successfulLoginNeverWritesBackStaleStatusOrDeletionSnapshot() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setPhone("13800000000");
        member.setPassword(SecurityUtils.encryptPassword("password"));
        member.setWxOpenId("existing-openid");
        member.setStatus(1L);
        member.setDelFlag(0);
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "appTokenService", realTokenService());
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", stableRegistry());

        loginService.login("13800000000", "password", "existing-openid");

        ArgumentCaptor<UmsMember> updates = ArgumentCaptor.forClass(UmsMember.class);
        verify(memberService, atLeastOnce()).updateUmsMember(updates.capture());
        assertTrue(updates.getAllValues().stream()
                .allMatch(update -> update.getStatus() == null && update.getDelFlag() == null));
    }

    @Test
    void passwordLoginRevalidatesCredentialAfterEpochFence() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        AppTokenService tokenService = mock(AppTokenService.class);
        UmsMember stale = new UmsMember();
        stale.setId(42L);
        stale.setPhone("13800000000");
        stale.setPassword(SecurityUtils.encryptPassword("old-password"));
        stale.setWxOpenId("existing-openid");
        UmsMember current = new UmsMember();
        current.setId(42L);
        current.setPhone("13800000000");
        current.setPassword(SecurityUtils.encryptPassword("new-password"));
        current.setWxOpenId("existing-openid");
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(stale), Collections.singletonList(current));
        when(memberService.selectUmsMemberById(42L)).thenReturn(current);
        when(registry.currentOrCreateEpoch(42L)).thenReturn(3L);
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(loginService, "appTokenService", tokenService);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loginService.login("13800000000", "old-password", "existing-openid"));

        assertEquals("账号或者密码错误", exception.getMessage());
        verify(tokenService, never()).createAppToken(any());
    }

    @Test
    void memberLoginRejectsWhenProvenPhoneChangedBeforeFenceRecheck() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        IAppSessionRegistryService registry = stableRegistry();
        AppTokenService tokenService = mock(AppTokenService.class);
        UmsMember snapshot = new UmsMember();
        snapshot.setId(42L);
        snapshot.setPhone("13800000000");
        UmsMember current = new UmsMember();
        current.setId(42L);
        current.setPhone("13900000000");
        when(memberService.selectUmsMemberById(42L)).thenReturn(current);
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(loginService, "appTokenService", tokenService);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loginService.loginByMember(snapshot, "phone", "13800000000", null));

        assertEquals("账号或者密码错误", exception.getMessage());
        verify(tokenService, never()).createAppToken(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void validPasswordLoginBindsOpenIdOnlyWhenExistingBindingIsBlank(String existingOpenId) {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        when(memberMapper.bindWxOpenIdIfBlank(42L, "new-openid")).thenReturn(1);
        AppTokenService tokenService = realTokenService();
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setPhone("13800000000");
        member.setPassword(SecurityUtils.encryptPassword("password"));
        member.setWxOpenId(existingOpenId);
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(loginService, "appTokenService", tokenService);
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", stableRegistry());

        loginService.login("13800000000", "password", "new-openid");

        assertEquals("new-openid", member.getWxOpenId());
    }

    @Test
    void concurrentFirstBindingsAllowOnlyOneOpenIdToReceiveToken() throws Exception {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        AppTokenService tokenService = mock(AppTokenService.class);
        CountDownLatch bothReadBlankBinding = new CountDownLatch(2);
        AtomicReference<String> storedOpenId = new AtomicReference<>("");
        AtomicInteger issuedTokens = new AtomicInteger();
        String encodedPassword = SecurityUtils.encryptPassword("password");

        when(memberService.selectUmsMemberList(any(UmsMember.class))).thenAnswer(invocation -> {
            UmsMember member = new UmsMember();
            member.setId(42L);
            member.setPhone("13800000000");
            member.setPassword(encodedPassword);
            member.setWxOpenId("");
            bothReadBlankBinding.countDown();
            assertTrue(bothReadBlankBinding.await(5, TimeUnit.SECONDS));
            return Collections.singletonList(member);
        });
        when(memberMapper.bindWxOpenIdIfBlank(eq(42L), anyString())).thenAnswer(invocation ->
                storedOpenId.compareAndSet("", invocation.getArgument(1)) ? 1 : 0);
        when(tokenService.createAppToken(any())).thenAnswer(invocation ->
                "token-" + issuedTokens.incrementAndGet());

        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(loginService, "appTokenService", tokenService);
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", stableRegistry());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> loginResult(loginService, "openid-a"));
            Future<String> second = executor.submit(() -> loginResult(loginService, "openid-b"));
            Set<String> results = new HashSet<>();
            results.add(first.get(5, TimeUnit.SECONDS));
            results.add(second.get(5, TimeUnit.SECONDS));

            assertEquals(2, results.size());
            assertTrue(results.contains("token-1"));
            assertTrue(results.contains("error:账号或者密码错误"));
            assertTrue(storedOpenId.get().equals("openid-a") || storedOpenId.get().equals("openid-b"));
            assertEquals(1, issuedTokens.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void passwordLoginRejectsAccountWithEmptyStoredPassword() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setPassword("");
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", stableRegistry());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loginService.login("13800000000", "password", "openid"));

        assertEquals("账号或者密码错误", exception.getMessage());
        verify(memberService, never()).updateUmsMember(any(UmsMember.class));
    }

    @Test
    void validLegacyPlaintextPasswordIsUpgradedWithoutBreakingLogin() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.beginMemberMutation(42L)).thenReturn("mutation-id");
        when(registry.currentOrCreateEpoch(42L)).thenReturn(1L);
        when(memberMapper.bindWxOpenIdIfBlank(42L, "openid")).thenReturn(1);
        when(memberMapper.upgradePasswordIfMatches(eq(42L), eq("legacy-password"), anyString())).thenReturn(1);
        AppTokenService tokenService = realTokenService();
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setPhone("13800000000");
        member.setPassword("legacy-password");
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(loginService, "appTokenService", tokenService);

        String token = loginService.login("13800000000", "legacy-password", "openid");

        assertFalse(token.isEmpty());
        assertTrue(SecurityUtils.matchesPassword("legacy-password", member.getPassword()));
        verify(memberMapper).upgradePasswordIfMatches(eq(42L), eq("legacy-password"), anyString());
    }

    @Test
    void legacyPasswordUpgradeFailsClosedWhenPasswordChangedConcurrently() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.beginMemberMutation(42L)).thenReturn("mutation-id");
        when(registry.currentOrCreateEpoch(42L)).thenReturn(1L);
        AppTokenService tokenService = mock(AppTokenService.class);
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setPhone("13800000000");
        member.setPassword("legacy-password");
        member.setWxOpenId("existing-openid");
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        when(memberMapper.upgradePasswordIfMatches(eq(42L), eq("legacy-password"), anyString())).thenReturn(0);
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(loginService, "appTokenService", tokenService);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loginService.login("13800000000", "legacy-password", "existing-openid"));

        assertEquals("账号或者密码错误", exception.getMessage());
        verify(tokenService, never()).createAppToken(any());
    }

    @Test
    void passwordLoginRejectsAmbiguousMemberResults() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember first = new UmsMember();
        first.setId(41L);
        first.setPhone("13800000000");
        first.setPassword(SecurityUtils.encryptPassword("password"));
        UmsMember second = new UmsMember();
        second.setId(42L);
        second.setPhone("13800000000");
        second.setPassword(SecurityUtils.encryptPassword("password"));
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(java.util.Arrays.asList(first, second));
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "appTokenService", realTokenService());
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", stableRegistry());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loginService.login("13800000000", "password", "openid"));

        assertEquals("账号或者密码错误", exception.getMessage());
        verify(memberService, never()).updateUmsMember(any(UmsMember.class));
    }

    @Test
    void openIdLoginRejectsAmbiguousMemberResults() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember first = new UmsMember();
        first.setId(41L);
        first.setPhone("13800000000");
        first.setPassword("password");
        first.setWxOpenId("openid");
        UmsMember second = new UmsMember();
        second.setId(42L);
        second.setPhone("13900000000");
        second.setPassword("password");
        second.setWxOpenId("openid");
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(java.util.Arrays.asList(first, second));
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "appTokenService", realTokenService());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loginService.login("openid"));

        assertEquals("账号或者密码错误", exception.getMessage());
        verify(memberService, never()).updateUmsMember(any(UmsMember.class));
    }

    @Test
    void validOpenIdLoginReturnsOriginalMemberSession() {
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(42L);
        member.setPhone("");
        member.setPassword("");
        member.setWxOpenId("existing-openid");
        when(memberService.selectUmsMemberList(any(UmsMember.class)))
                .thenReturn(Collections.singletonList(member));
        AppLoginService loginService = new AppLoginService();
        ReflectionTestUtils.setField(loginService, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(loginService, "appUserSevice", memberService);
        ReflectionTestUtils.setField(loginService, "appTokenService", realTokenService());
        ReflectionTestUtils.setField(loginService, "appSessionRegistryService", stableRegistry());

        String token = loginService.login("existing-openid");

        assertFalse(token.isEmpty());
        assertEquals("existing-openid", member.getWxOpenId());
        verify(memberService).updateUmsMember(any(UmsMember.class));
    }

    @Test
    void passwordFailuresAreBoundedPerPhoneAndLockedBeforeAccountLookup() {
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(42L); member.setPhone("13800000000"); member.setPassword(SecurityUtils.encryptPassword("correct"));
        when(members.selectUmsMemberList(any(UmsMember.class))).thenReturn(Collections.singletonList(member));
        RedisCache cache = mock(RedisCache.class);
        AppLoginService service = new AppLoginService();
        ReflectionTestUtils.setField(service, "appUserSevice", members);
        ReflectionTestUtils.setField(service, "appSessionRegistryService", stableRegistry());
        ReflectionTestUtils.setField(service, "redisCache", cache);
        assertThrows(ServiceException.class, () -> service.login("13800000000", "wrong", "openid"));
        verify(cache).incrementWithinWindow("app_password_fail_13800000000", 10, TimeUnit.MINUTES);
        when(cache.incrementWithinWindow("app_password_fail_13800000000", 10, TimeUnit.MINUTES)).thenReturn(6L);
        org.mockito.Mockito.clearInvocations(members);
        ServiceException locked = assertThrows(ServiceException.class,
                () -> service.login("13800000000", "correct", "openid"));
        assertTrue(locked.getMessage().contains("10分钟"));
        verifyNoInteractions(members);
    }

    @Test
    void concurrentPasswordGuessesReserveAttemptsAtomicallyBeforeVerification() {
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(42L); member.setPhone("13800000000"); member.setPassword(SecurityUtils.encryptPassword("correct"));
        when(members.selectUmsMemberList(any(UmsMember.class))).thenReturn(Collections.singletonList(member));
        RedisCache cache = mock(RedisCache.class);
        AtomicInteger counter = new AtomicInteger();
        when(cache.incrementWithinWindow("app_password_fail_13800000000", 10, TimeUnit.MINUTES))
                .thenAnswer(invocation -> (long) counter.incrementAndGet());
        AppLoginService service = new AppLoginService();
        ReflectionTestUtils.setField(service, "appUserSevice", members);
        ReflectionTestUtils.setField(service, "appSessionRegistryService", stableRegistry());
        ReflectionTestUtils.setField(service, "redisCache", cache);
        // 计数从不经“读→比对→再写”，所以 20 次猜测里只有前 5 次会走到口令比对。
        for (int i = 0; i < 20; i++) {
            assertThrows(ServiceException.class, () -> service.login("13800000000", "wrong", "openid"));
        }
        verify(members, org.mockito.Mockito.times(10)).selectUmsMemberList(any(UmsMember.class));
        verify(cache, never()).getCacheObject("app_password_fail_13800000000");
        ServiceException locked = assertThrows(ServiceException.class,
                () -> service.login("13800000000", "correct", "openid"));
        assertTrue(locked.getMessage().contains("10分钟"));
        verify(cache, never()).deleteObject("app_password_fail_13800000000");
    }

    private AppTokenService realTokenService() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AppTokenService tokenService = new AppTokenService();
        ReflectionTestUtils.setField(tokenService, "redisCache", mock(RedisCache.class));
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
        when(registry.isCurrentEpoch(anyLong(), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(tokenService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(tokenService, "idleTime", 24 * 60);
        ReflectionTestUtils.setField(tokenService, "absoluteTime", 7 * 24 * 60);
        ReflectionTestUtils.setField(tokenService, "secret",
                "unit-test-secret-unit-test-secret-unit-test-secret");
        return tokenService;
    }

    private IAppSessionRegistryService stableRegistry() {
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
        return registry;
    }

    private String loginResult(AppLoginService loginService, String openId) {
        try {
            return loginService.login("13800000000", "password", openId);
        } catch (ServiceException e) {
            return "error:" + e.getMessage();
        }
    }
}
