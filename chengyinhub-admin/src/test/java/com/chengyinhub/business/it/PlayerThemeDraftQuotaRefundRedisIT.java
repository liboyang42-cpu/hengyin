package com.chengyinhub.business.it;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import com.chengyinhub.framework.config.FastJson2JsonRedisSerializer;
import com.chengyinhub.business.service.ai.AiServiceException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.business.service.impl.AiCreatorServiceImpl;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_AI_REDIS_IT", matches = "1")
class PlayerThemeDraftQuotaRefundRedisIT
{
    private LettuceConnectionFactory connection;
    private RedisTemplate<Object, Object> redis;
    private AiCreatorServiceImpl service;
    private final Set<Object> ownedKeys = new HashSet<>();
    private long member;
    private String originalKey;
    private String markerKey;
    private Object reservation;

    @BeforeEach
    void connectToDedicatedLoopbackFixture() throws Exception
    {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        server.setDatabase(13);
        connection = new LettuceConnectionFactory(server, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        connection.afterPropertiesSet();
        redis = new RedisTemplate<>();
        redis.setConnectionFactory(connection);
        redis.setKeySerializer(new StringRedisSerializer());
        redis.setValueSerializer(new FastJson2JsonRedisSerializer<>(Object.class));
        redis.afterPropertiesSet();
        service = new AiCreatorServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        member = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        originalKey = "ai:theme:draft:player:" + member + ":" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Class<?> type = Class.forName(AiCreatorServiceImpl.class.getName() + "$PlayerThemeDraftQuotaReservation");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        reservation = constructor.newInstance(originalKey, 0);
        markerKey = (String) ReflectionTestUtils.getField(reservation, "refundKey");
        ownedKeys.add(originalKey);
        ownedKeys.add(markerKey);
    }

    @AfterEach
    void removeOnlyOwnedKeys()
    {
        try { if (redis != null) redis.delete(ownedKeys); }
        finally { if (connection != null) connection.destroy(); }
    }

    private void release()
    {
        ReflectionTestUtils.invokeMethod(service, "releasePlayerThemeDraftQuota", reservation);
    }

    @SuppressWarnings("unchecked")
    private Long reserve()
    {
        RedisScript<Long> script = (RedisScript<Long>) ReflectionTestUtils.getField(
                AiCreatorServiceImpl.class, "PLAYER_THEME_DRAFT_RESERVE_SCRIPT");
        return redis.execute(script, java.util.Arrays.asList(originalKey, markerKey), 300, 3);
    }

    private void seed(String key, Object count)
    {
        ownedKeys.add(key);
        redis.opsForValue().set(key, count, Duration.ofMinutes(5));
    }

    private int count(String key)
    {
        return ((Number) redis.opsForValue().get(key)).intValue();
    }

    @Test
    void concurrentRetriesRestoreOnlyOneUseAndKeepOriginalExpiry() throws Exception
    {
        seed(originalKey, 2);
        assertEquals(Long.valueOf(3), reserve());
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try
        {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < 24; i++) attempts.add(workers.submit(this::release));
            for (Future<?> attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
        }
        finally { workers.shutdownNow(); }
        assertEquals(2, count(originalKey));
        assertTrue(redis.getExpire(originalKey) > 0 && redis.getExpire(originalKey) <= 300);
        assertTrue(redis.getExpire(markerKey) > 0 && redis.getExpire(markerKey) <= 172800);
    }

    @Test
    void crossDayRefundDoesNotTouchNewDayCounter()
    {
        String nextDay = "ai:theme:draft:player:" + member + ":"
                + LocalDate.now().plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        seed(originalKey, 2);
        assertEquals(Long.valueOf(3), reserve());
        seed(nextDay, 2);
        release();
        release();
        assertEquals(2, count(originalKey));
        assertEquals(2, count(nextDay));
    }

    @SuppressWarnings("unchecked")
    @Test
    void productionSerializerTtlMustBeIntegerAndInvalidTtlCannotPartiallyIncrement()
    {
        FastJson2JsonRedisSerializer<Object> serializer = new FastJson2JsonRedisSerializer<>(Object.class);
        assertEquals("300L", new String(serializer.serialize(300L), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("300", new String(serializer.serialize(300), java.nio.charset.StandardCharsets.UTF_8));
        RedisScript<Long> script = (RedisScript<Long>) ReflectionTestUtils.getField(AiCreatorServiceImpl.class,
                "PLAYER_THEME_DRAFT_RESERVE_SCRIPT");
        assertEquals(Long.valueOf(-1), redis.execute(script, java.util.Arrays.asList(originalKey, markerKey), 300L, 3));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(originalKey)), "invalid serialized TTL must fail before INCR");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(markerKey)));
        assertEquals(Long.valueOf(1), reserve());
        byte[] stored = redis.execute((org.springframework.data.redis.core.RedisCallback<byte[]>)
                connection -> connection.get(originalKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("1", new String(stored, java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(redis.getExpire(originalKey) > 290 && redis.getExpire(originalKey) <= 300);
    }

    @Test
    void missingOldDayIsNotRecreatedAndReplayCannotDebitLaterCounter()
    {
        assertEquals(Long.valueOf(1), reserve());
        redis.delete(originalKey);
        release();
        assertFalse(Boolean.TRUE.equals(redis.hasKey(originalKey)));
        assertEquals(0, count(markerKey));
        seed(originalKey, 3);
        release();
        assertEquals(3, count(originalKey));
    }

    @SuppressWarnings("unchecked")
    @Test
    void appliedRefundWithLostReplyCannotRestoreAnotherUse()
    {
        seed(originalKey, 1);
        assertEquals(Long.valueOf(2), reserve());
        RedisTemplate<Object, Object> lostReply = spy(redis);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new RuntimeException("simulated reply lost after Redis applied the refund");
        }).when(lostReply).execute(any(RedisScript.class), anyList());
        ReflectionTestUtils.setField(service, "redisTemplate", lostReply);
        release();
        assertEquals(1, count(originalKey));
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        release();
        assertEquals(1, count(originalKey));
    }

    @Test
    void invalidCounterKeepsReservationAndZeroDoesNotGoNegative()
    {
        assertEquals(Long.valueOf(1), reserve());
        seed(originalKey, "invalid");
        release();
        assertEquals("invalid", redis.opsForValue().get(originalKey));
        assertEquals(1, count(markerKey));
        seed(originalKey, 0);
        release();
        assertEquals(0, count(originalKey));
        assertEquals(0, count(markerKey));
    }

    @Test
    void cancelledUnknownReservationNeverDebitsOthersAndBlocksLateReserve()
    {
        seed(originalKey, 2);
        release();
        assertEquals(2, count(originalKey));
        assertEquals(Long.valueOf(-1), reserve());
        assertEquals(2, count(originalKey));
    }

    @Test
    void reserveReplayOnlyConsumesOneSlotAndFourthRequestNeverIncrements() throws Exception
    {
        assertEquals(Long.valueOf(1), reserve());
        assertEquals(Long.valueOf(1), reserve());
        assertEquals(1, count(originalKey));
        seed(originalKey, 3);
        String originalMarker = markerKey;
        markerKey = originalMarker + ":fourth";
        ownedKeys.add(markerKey);
        assertEquals(Long.valueOf(0), reserve());
        assertEquals(3, count(originalKey));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(markerKey)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void parallelReservationsStopAtThreeAndDoNotRefreshExistingExpiry() throws Exception
    {
        seed(originalKey, 0);
        RedisScript<Long> script = (RedisScript<Long>) ReflectionTestUtils.getField(
                AiCreatorServiceImpl.class, "PLAYER_THEME_DRAFT_RESERVE_SCRIPT");
        List<String> markers = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String marker = markerKey + ":parallel:" + i;
            markers.add(marker);
            ownedKeys.add(marker);
        }
        ExecutorService workers = Executors.newFixedThreadPool(8);
        int accepted = 0;
        try {
            List<Future<Long>> attempts = new ArrayList<>();
            for (String marker : markers) attempts.add(workers.submit(() ->
                    redis.execute(script, java.util.Arrays.asList(originalKey, marker), 3600, 3)));
            for (Future<Long> attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS) > 0) accepted++;
        } finally { workers.shutdownNow(); }
        assertEquals(3, accepted);
        assertEquals(3, count(originalKey));
        assertTrue(redis.getExpire(originalKey) > 0 && redis.getExpire(originalKey) <= 300);
    }

    @SuppressWarnings("unchecked")
    @Test
    void appliedReserveWithLostReplyIsRefundedByActualService()
    {
        seed(originalKey, 1);
        RedisTemplate<Object, Object> lostReply = spy(redis);
        doAnswer(invocation -> {
            List<Object> keys = invocation.getArgument(1);
            ownedKeys.addAll(keys);
            invocation.callRealMethod();
            throw new RuntimeException("simulated reply lost after Redis applied the reservation");
        }).when(lostReply).execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt(), eq(3));
        ReflectionTestUtils.setField(service, "redisTemplate", lostReply);
        assertThrows(AiServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "reservePlayerThemeDraftQuota", member));
        assertEquals(1, count(originalKey));
    }

    @SuppressWarnings("unchecked")
    @Test
    void unappliedReserveFailureDoesNotRefundSomeoneElse()
    {
        seed(originalKey, 2);
        RedisTemplate<Object, Object> beforeExecution = spy(redis);
        doAnswer(invocation -> {
            List<Object> keys = invocation.getArgument(1);
            ownedKeys.addAll(keys);
            throw new RuntimeException("simulated connection failure before reserve execution");
        }).when(beforeExecution).execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt(), eq(3));
        ReflectionTestUtils.setField(service, "redisTemplate", beforeExecution);
        assertThrows(AiServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "reservePlayerThemeDraftQuota", member));
        assertEquals(2, count(originalKey));
    }

    @SuppressWarnings("unchecked")
    @Test
    void unknownReserveReplyRefundsItsOwnSlot()
    {
        seed(originalKey, 1);
        RedisTemplate<Object, Object> unknownReply = spy(redis);
        doAnswer(invocation -> {
            List<Object> keys = invocation.getArgument(1);
            ownedKeys.addAll(keys);
            invocation.callRealMethod();
            return null;
        }).when(unknownReply).execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt(), eq(3));
        ReflectionTestUtils.setField(service, "redisTemplate", unknownReply);
        assertThrows(AiServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "reservePlayerThemeDraftQuota", member));
        assertEquals(1, count(originalKey));
    }
}
