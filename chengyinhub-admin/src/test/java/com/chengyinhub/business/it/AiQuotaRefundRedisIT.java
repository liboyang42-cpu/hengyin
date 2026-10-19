package com.chengyinhub.business.it;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_AI_REDIS_IT", matches = "1")
class AiQuotaRefundRedisIT
{
    private static final Long MEMBER = 910012L;
    private LettuceConnectionFactory connection;
    private RedisTemplate<Object, Object> redis;
    private AiGatewayServiceImpl service;
    private final Set<Object> ownedKeys = new HashSet<>();
    private String reservationId;
    private String originalKey;
    private String markerKey;

    @BeforeEach
    void connectToDedicatedLoopbackFixture()
    {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        server.setDatabase(13);
        connection = new LettuceConnectionFactory(server, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        connection.afterPropertiesSet();
        redis = new RedisTemplate<>();
        redis.setConnectionFactory(connection);
        redis.setKeySerializer(new StringRedisSerializer());
        redis.setValueSerializer(new StringRedisSerializer());
        redis.afterPropertiesSet();
        service = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        reservationId = UUID.randomUUID().toString();
        originalKey = "ai:gen:limit:photo:" + MEMBER + ":20000101";
        markerKey = "ai:gen:refund:" + reservationId;
        ownedKeys.add(originalKey);
        ownedKeys.add(markerKey);
    }

    @AfterEach
    void removeOnlyOwnedKeys()
    {
        try { if (redis != null) redis.delete(ownedKeys); }
        finally { if (connection != null) connection.destroy(); }
    }

    private String reservation()
    {
        return originalKey + "|" + reservationId;
    }

    private void seed(String key, String count)
    {
        ownedKeys.add(key);
        redis.opsForValue().set(key, count, Duration.ofMinutes(5));
    }

    @Test
    void concurrentRetriesRestoreOnlyOneUseAndKeepOriginalExpiry() throws Exception
    {
        seed(originalKey, "10");
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try
        {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < 24; i++)
                attempts.add(workers.submit(() -> service.refundQuota(MEMBER, "photo_check", reservation())));
            for (Future<?> attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
        }
        finally { workers.shutdownNow(); }
        assertEquals("9", redis.opsForValue().get(originalKey));
        assertTrue(redis.getExpire(originalKey) > 0);
        assertTrue(redis.getExpire(originalKey) <= 300);
        assertTrue(redis.getExpire(markerKey) > 0);
    }

    @Test
    void crossDayRefundDoesNotTouchTheNewDayCounter()
    {
        String nextDay = originalKey.replace("20000101", "20000102");
        seed(originalKey, "3");
        seed(nextDay, "4");
        service.refundQuota(MEMBER, "photo_check", reservation());
        service.refundQuota(MEMBER, "photo_check", reservation());
        assertEquals("2", redis.opsForValue().get(originalKey));
        assertEquals("4", redis.opsForValue().get(nextDay));
    }

    @Test
    void expiredCounterIsNotRecreatedOrAppliedToLaterUnrelatedUse()
    {
        service.refundQuota(MEMBER, "photo_check", reservation());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(originalKey)));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(markerKey)));
        seed(originalKey, "5");
        service.refundQuota(MEMBER, "photo_check", reservation());
        assertEquals("5", redis.opsForValue().get(originalKey));
    }

    @SuppressWarnings("unchecked")
    @Test
    void appliedRefundWithLostReplyCannotRestoreAnotherUse()
    {
        seed(originalKey, "2");
        RedisTemplate<Object, Object> lostReply = spy(redis);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new RuntimeException("simulated reply lost after Redis applied the refund");
        }).when(lostReply).execute(any(RedisScript.class), anyList());
        ReflectionTestUtils.setField(service, "redisTemplate", lostReply);
        service.refundQuota(MEMBER, "photo_check", reservation());
        assertEquals("1", redis.opsForValue().get(originalKey));
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        service.refundQuota(MEMBER, "photo_check", reservation());
        assertEquals("1", redis.opsForValue().get(originalKey));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(markerKey)));
    }

    @Test
    void invalidCounterFailsWithoutConsumingTheRefundMarker()
    {
        seed(originalKey, "invalid-counter");
        service.refundQuota(MEMBER, "photo_check", reservation());
        assertEquals("invalid-counter", redis.opsForValue().get(originalKey));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(markerKey)));
    }
}
