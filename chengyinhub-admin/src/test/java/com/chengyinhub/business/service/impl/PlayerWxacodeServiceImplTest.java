package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerWxacodeServiceImplTest {

    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
    };

    private PlayerWxacodeServiceImpl.WxCodeGateway gateway;
    private PlayerWxacodeServiceImpl.WxCodeStorage storage;
    private IWxContentSecurityService tokenService;
    private RedisCache redisCache;
    private StringRedisTemplate rateRedis;
    private PlayerWxacodeServiceImpl service;

    @BeforeEach
    void setUp() {
        gateway = mock(PlayerWxacodeServiceImpl.WxCodeGateway.class);
        storage = mock(PlayerWxacodeServiceImpl.WxCodeStorage.class);
        tokenService = mock(IWxContentSecurityService.class);
        redisCache = mock(RedisCache.class);
        rateRedis = mock(StringRedisTemplate.class);
        service = new PlayerWxacodeServiceImpl(gateway, storage);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        ReflectionTestUtils.setField(service, "rateRedis", rateRedis);
        ReflectionTestUtils.setField(service, "signingSecret", "test-only-player-code-secret");
        when(redisCache.setIfAbsent(anyString(), any(), eq(300), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(rateRedis.execute(any(DefaultRedisScript.class), any(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        when(tokenService.getAccessToken()).thenReturn("cached-access-token");
    }

    @Test
    void generatesSignedBoundedSceneAndCachesOssUrl() {
        when(gateway.generate(anyString(), anyString()))
                .thenReturn(new PlayerWxacodeServiceImpl.WxCodeResponse("image/png", PNG));
        when(storage.store(PNG, "png")).thenReturn("upload/player-code.png");
        when(storage.url("upload/player-code.png")).thenReturn("https://oss.example/player-code.png");

        String result = service.getOrCreate(9223372036854775807L);

        assertEquals("https://oss.example/player-code.png", result);
        ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
        verify(gateway).generate(eq("cached-access-token"), request.capture());
        Map<String, Object> body = JSON.parseObject(request.getValue());
        String scene = String.valueOf(body.get("scene"));
        assertTrue(scene.matches("p1\\.[0-9a-z]+\\.[A-Za-z0-9_-]{12}"), scene);
        assertTrue(scene.length() <= 32, scene);
        assertEquals("pages/index/index", body.get("page"));
        assertEquals(Boolean.TRUE, body.get("check_path"));
        assertEquals("release", body.get("env_version"));
        assertEquals(430, ((Number) body.get("width")).intValue());
        verify(redisCache).setCacheObject(
                "wx:player-code:v2:9223372036854775807", "upload/player-code.png");
    }

    @Test
    void doorSceneUsesRawCheckinCodeAndSameLandingPage() {
        when(gateway.generate(anyString(), anyString()))
                .thenReturn(new PlayerWxacodeServiceImpl.WxCodeResponse("image/png", PNG));
        when(storage.store(PNG, "png")).thenReturn("upload/door.png");
        when(storage.url("upload/door.png")).thenReturn("https://oss.example/door.png");

        String result = service.getOrCreateScene("0f1e2d3c4b5a69788796a5b4c3d2e1f0");

        assertEquals("https://oss.example/door.png", result);
        ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
        verify(gateway).generate(eq("cached-access-token"), request.capture());
        Map<String, Object> body = JSON.parseObject(request.getValue());
        assertEquals("0f1e2d3c4b5a69788796a5b4c3d2e1f0", body.get("scene"));
        assertEquals("pages/index/index", body.get("page"));
        verify(redisCache).setCacheObject(
                "wx:door-code:v2:0f1e2d3c4b5a69788796a5b4c3d2e1f0", "upload/door.png");
    }

    /** E-4:缓存的是对象 key,每次读取现签;AccessKey 轮换后旧签名不会永久留成空白码图。 */
    @Test
    void cachedCodeAvoidsWechatFrequencyAndStorageCalls() {
        when(redisCache.getCacheObject("wx:player-code:v2:42")).thenReturn("upload/cached.png");
        when(storage.url("upload/cached.png")).thenReturn("https://oss.example/fresh-signature.png");

        assertEquals("https://oss.example/fresh-signature.png", service.getOrCreate(42L));

        verify(tokenService, never()).getAccessToken();
        verify(gateway, never()).generate(anyString(), anyString());
        verify(storage, never()).store(any(), anyString());
    }

    /** 失败 JSON 绝不能被当图片上传；断言钉住微信 errcode 识别闸，不只断言异常类型。 */
    @Test
    void wxErrorJsonIsRejectedBeforeOssUpload() {
        byte[] error = "{\"errcode\":40129,\"errmsg\":\"invalid scene\"}"
                .getBytes(StandardCharsets.UTF_8);
        when(gateway.generate(anyString(), anyString()))
                .thenReturn(new PlayerWxacodeServiceImpl.WxCodeResponse("application/json", error));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.getOrCreate(42L));

        assertTrue(thrown.getMessage().contains("40129"), thrown.getMessage());
        verify(storage, never()).store(any(), anyString());
        verify(tokenService, never()).refreshAccessToken();
    }

    @Test
    void invalidAccessTokenRefreshesOnceAndRetries() {
        byte[] tokenError = "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"
                .getBytes(StandardCharsets.UTF_8);
        when(gateway.generate(eq("cached-access-token"), anyString()))
                .thenReturn(new PlayerWxacodeServiceImpl.WxCodeResponse("application/json", tokenError));
        when(tokenService.refreshAccessToken()).thenReturn("refreshed-access-token");
        when(gateway.generate(eq("refreshed-access-token"), anyString()))
                .thenReturn(new PlayerWxacodeServiceImpl.WxCodeResponse("image/png", PNG));
        when(storage.store(PNG, "png")).thenReturn("upload/player-code.png");
        when(storage.url("upload/player-code.png")).thenReturn("https://oss.example/player-code.png");

        assertEquals("https://oss.example/player-code.png", service.getOrCreate(42L));

        verify(tokenService).refreshAccessToken();
        verify(gateway).generate(eq("refreshed-access-token"), anyString());
    }

    @Test
    void globalMinuteLimitRejectsBeforeWechatCall() {
        when(rateRedis.execute(any(DefaultRedisScript.class), any(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.getOrCreate(42L));

        assertTrue(thrown.getMessage().contains("频繁"), thrown.getMessage());
        verify(gateway, never()).generate(anyString(), anyString());
        verify(storage, never()).store(any(), anyString());
    }

    @Test
    void rateLimitUsesAtomicSlidingWindowWithTtl() {
        DefaultRedisScript<?> script = (DefaultRedisScript<?>) ReflectionTestUtils.getField(
                PlayerWxacodeServiceImpl.class, "RATE_LIMIT_SCRIPT");
        String lua = script.getScriptAsString().toLowerCase();

        assertTrue(lua.contains("zremrangebyscore"), lua);
        assertTrue(lua.contains("zcard"), lua);
        assertTrue(lua.contains("60000"), lua);
        assertTrue(lua.contains("pexpire"), lua);
    }
}
