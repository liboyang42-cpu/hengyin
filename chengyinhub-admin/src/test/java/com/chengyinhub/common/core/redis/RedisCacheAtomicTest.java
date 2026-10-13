package com.chengyinhub.common.core.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisCacheAtomicTest
{
    private RedisTemplate<Object, Object> redisTemplate;
    private RedisCache redisCache;

    @BeforeEach
    void setUp()
    {
        redisTemplate = mock(RedisTemplate.class);
        redisCache = new RedisCache();
        redisCache.redisTemplate = redisTemplate;
    }

    @Test
    void compareAndSetRunsAsOneScriptWithExpectedOwnerAndFiniteTtl()
    {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(Collections.singletonList("lease-key")),
                eq("owner-a"), eq("done"))).thenReturn(1L);

        assertTrue(redisCache.compareAndSet(
                "lease-key", "owner-a", "done", 2, TimeUnit.DAYS));

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(
                script.capture(),
                eq(Collections.singletonList("lease-key")),
                eq("owner-a"), eq("done"));
        String scriptText = script.getValue().getScriptAsString();
        assertTrue(scriptText.contains("redis.call('get', KEYS[1]) == ARGV[1]"));
        assertTrue(scriptText.contains("redis.call('psetex', KEYS[1], 172800000, ARGV[2])"));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void compareAndSetRejectsStaleOwner()
    {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(Collections.singletonList("lease-key")),
                eq("owner-old"), eq("done"))).thenReturn(0L);

        assertFalse(redisCache.compareAndSet(
                "lease-key", "owner-old", "done", 2, TimeUnit.MINUTES));
    }

    @Test
    void compareAndDeleteRunsAsOneScriptAndCannotDeleteAnotherOwner()
    {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(Collections.singletonList("lease-key")),
                eq("owner-a"))).thenReturn(1L);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(Collections.singletonList("lease-key")),
                eq("owner-old"))).thenReturn(0L);

        assertTrue(redisCache.compareAndDelete("lease-key", "owner-a"));
        assertFalse(redisCache.compareAndDelete("lease-key", "owner-old"));

        ArgumentCaptor<RedisScript> scripts = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, org.mockito.Mockito.times(2)).execute(
                scripts.capture(), eq(Collections.singletonList("lease-key")), any());
        for (RedisScript script : scripts.getAllValues())
        {
            String scriptText = script.getScriptAsString();
            assertTrue(scriptText.contains("redis.call('get', KEYS[1]) == ARGV[1]"));
            assertTrue(scriptText.contains("return redis.call('del', KEYS[1])"));
        }
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void fixedWindowIncrementUsesOneScriptAndSetsTtlOnlyForFirstHit()
    {
        when(redisTemplate.execute(
                any(RedisScript.class), eq(Collections.singletonList("rate-key"))))
                .thenReturn(1L, 2L);

        assertEquals(1L, redisCache.incrementWithinWindow("rate-key", 60, TimeUnit.SECONDS));
        assertEquals(2L, redisCache.incrementWithinWindow("rate-key", 60, TimeUnit.SECONDS));

        ArgumentCaptor<RedisScript> scripts = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, org.mockito.Mockito.times(2)).execute(
                scripts.capture(), eq(Collections.singletonList("rate-key")));
        for (RedisScript script : scripts.getAllValues())
        {
            String text = script.getScriptAsString();
            assertTrue(text.contains("redis.call('incr', KEYS[1])"));
            assertTrue(text.contains("if current == 1 then"));
            assertTrue(text.contains("redis.call('pexpire', KEYS[1], 60000)"));
            assertEquals(1, occurrences(text, "redis.call('pexpire'"));
        }
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void fixedWindowIncrementRejectsInvalidWindowWithoutRedisCall()
    {
        assertEquals(null, redisCache.incrementWithinWindow("rate-key", 0, TimeUnit.SECONDS));
        assertEquals(null, redisCache.incrementWithinWindow(null, 60, TimeUnit.SECONDS));

        verify(redisTemplate, never()).execute(
                any(RedisScript.class), any(java.util.List.class));
    }

    private int occurrences(String text, String needle)
    {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0)
        {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
