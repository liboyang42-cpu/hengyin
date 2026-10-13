package com.chengyinhub.framework.web.service;

import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WechatReconfirmationServiceTest {
    @Test void freshCodeExchangesThroughActualHttpAndCanOnlyConfirmSameOwnerOnce() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sns/jscode2session", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"openid\":\"owner-seven\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            Fixture f = new Fixture(new MockEnvironment().withProperty("chengyin.test.wechat-api-base-url", "http://127.0.0.1:" + server.getAddress().getPort()));
            assertTrue(f.service.verify(7L, "owner-seven", "fresh-code", "fixture-ip"));
            assertFalse(f.service.verify(7L, "owner-seven", "fresh-code", "fixture-ip"));
            assertFalse(f.service.verify(8L, "owner-seven", "fresh-code", "fixture-ip"));
            assertFalse(f.service.verify(8L, "other-owner", "another-fresh-code", "fixture-ip"));
            assertEquals(2, requests.get());
            assertTrue(f.keys.stream().filter(k -> k.startsWith("deregister_wx_code_")).allMatch(k -> !k.contains("fresh-code")));
            verify(f.redis, atLeastOnce()).setIfAbsent(anyString(), anyString(), eq(5), eq(TimeUnit.MINUTES));
        } finally { server.stop(0); }
    }
    @Test void unavailableOrRedirectNeverConfirmsAndDoesNotReuseCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/sns/jscode2session", exchange -> { calls.incrementAndGet(); exchange.getResponseHeaders().add("Location", "/redirect"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/redirect", exchange -> { fail("must not forward secret-bearing requests"); });
        server.start();
        try {
            Fixture f = new Fixture(new MockEnvironment().withProperty("chengyin.test.wechat-api-base-url", "http://127.0.0.1:" + server.getAddress().getPort()));
            assertFalse(f.service.verify(7L, "owner", "fresh", "ip"));
            assertFalse(f.service.verify(7L, "owner", "fresh", "ip"));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
    @Test void invalidIdentityAndMissingConfigAreClosedWithoutExternalCall() {
        Fixture f = new Fixture(new MockEnvironment());
        assertFalse(f.service.verify(null, "owner", "code", "ip"));
        assertFalse(f.service.verify(7L, "", "code", "ip"));
        assertFalse(f.service.verify(7L, "owner", "", "ip"));
        verifyNoInteractions(f.redis);
        when(f.config.selectConfigByKey("app.wechat.appsecret")).thenReturn("");
        assertFalse(f.service.verify(7L, "owner", "code", "ip"));
    }
    @Test void ipOrOwnerLimitAndRedisFailureAreFailClosed() {
        Fixture f = new Fixture(new MockEnvironment());
        when(f.redis.incrementWithinWindow(startsWith("deregister_wx_ip_"), eq(1), eq(TimeUnit.MINUTES))).thenReturn(11L);
        assertFalse(f.service.verify(7L, "owner", "code", "ip"));
        verify(f.redis, never()).setIfAbsent(anyString(), anyString(), anyInt(), any());
        when(f.redis.incrementWithinWindow(anyString(), eq(1), eq(TimeUnit.MINUTES))).thenThrow(new IllegalStateException("fixture"));
        assertFalse(f.service.verify(7L, "owner", "other", "ip"));
    }
    @Test void productionAndUnknownProfilesCannotOverrideOfficialBase() {
        MockEnvironment env = new MockEnvironment().withProperty("chengyin.test.wechat-api-base-url", "http://127.0.0.1:1");
        env.setActiveProfiles("prod", "test");
        assertEquals("https://api.weixin.qq.com", new WechatReconfirmationService(mock(RedisCache.class), mock(ISysConfigService.class), env).sessionBaseUrl());
        env.setActiveProfiles("unknown");
        assertEquals("https://api.weixin.qq.com", new WechatReconfirmationService(mock(RedisCache.class), mock(ISysConfigService.class), env).sessionBaseUrl());
    }
    @Test void nonproductionBaseRejectsCredentialsQueriesAndRedirectPaths() {
        for (String base : new String[]{"http://u:p@127.0.0.1", "http://127.0.0.1?token=value", "http://127.0.0.1/path", "file:///tmp/test"}) {
            Fixture f = new Fixture(new MockEnvironment().withProperty("chengyin.test.wechat-api-base-url", base));
            assertThrows(IllegalArgumentException.class, f.service::sessionBaseUrl);
        }
    }
    private static class Fixture {
        final RedisCache redis = mock(RedisCache.class);
        final ISysConfigService config = mock(ISysConfigService.class);
        final Set<String> keys = new HashSet<>();
        final WechatReconfirmationService service;
        Fixture(MockEnvironment env) {
            env.setActiveProfiles("test");
            when(config.selectConfigByKey("app.wechat.appid")).thenReturn("fixture-app");
            when(config.selectConfigByKey("app.wechat.appsecret")).thenReturn("fixture-secret");
            when(redis.incrementWithinWindow(anyString(), eq(1), eq(TimeUnit.MINUTES))).thenReturn(1L);
            when(redis.setIfAbsent(anyString(), anyString(), eq(5), eq(TimeUnit.MINUTES))).thenAnswer(call -> keys.add(call.getArgument(0)));
            service = new WechatReconfirmationService(redis, config, env);
        }
    }
}
