package com.chengyinhub.business.it;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.PhotoCheckVisionService;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import com.chengyinhub.framework.config.FastJson2JsonRedisSerializer;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_AI_REDIS_IT", matches = "1")
class AiQuotaReservationRedisIT
{
    private LettuceConnectionFactory connection;
    private RedisTemplate<Object, Object> redis;
    private AiGatewayServiceImpl service;
    private final Set<Object> ownedKeys = new HashSet<>();
    private long member;
    private String photoKey;
    private final List<List<Object>> reserveKeys = new ArrayList<>();

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
        redis.setValueSerializer(new FastJson2JsonRedisSerializer<>(Object.class));
        redis.afterPropertiesSet();
        member = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        photoKey = "ai:gen:limit:photo:" + member + ":" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        service = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", recordingTemplate(false, false, false));
        ReflectionTestUtils.setField(service, "photoDailyLimit", 30);
        ownedKeys.add(photoKey);
    }

    @AfterEach
    void removeOnlyOwnedKeys()
    {
        try { if (redis != null) redis.delete(ownedKeys); }
        finally { if (connection != null) connection.destroy(); }
    }

    @SuppressWarnings("unchecked")
    private RedisTemplate<Object, Object> recordingTemplate(boolean failBefore, boolean failAfter, boolean nullAfter)
    {
        RedisTemplate<Object, Object> observed = spy(redis);
        doAnswer(invocation -> {
            List<Object> keys = invocation.getArgument(1);
            reserveKeys.add(new ArrayList<>(keys));
            ownedKeys.addAll(keys);
            if (failBefore) throw new IllegalStateException("before Redis reserve");
            Object used = invocation.callRealMethod();
            if (failAfter) throw new IllegalStateException("lost Redis reserve reply");
            return nullAfter ? null : used;
        }).when(observed).execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt());
        return observed;
    }

    private void seed(String key, int used)
    {
        ownedKeys.add(key);
        redis.opsForValue().set(key, used, Duration.ofMinutes(5));
    }

    private int count(String key)
    {
        return ((Number) redis.opsForValue().get(key)).intValue();
    }

    @Test
    void appliedConsumeWithLostReplyRestoresOnlyItsReservation()
    {
        seed(photoKey, 2);
        ReflectionTestUtils.setField(service, "redisTemplate", recordingTemplate(false, true, false));
        assertThrows(IllegalStateException.class, () -> service.consumeQuota(member, "photo_check"));
        assertEquals(2, count(photoKey));
        assertEquals(0, count((String) reserveKeys.get(0).get(1)));
    }

    @Test
    void unappliedConsumeFailureDoesNotRefundOtherRequests()
    {
        seed(photoKey, 2);
        ReflectionTestUtils.setField(service, "redisTemplate", recordingTemplate(true, false, false));
        assertThrows(IllegalStateException.class, () -> service.consumeQuota(member, "photo_check"));
        assertEquals(2, count(photoKey));
    }

    @Test
    void nullConsumeReplyIsCancelledAndDoesNotReturnUnconfirmedReservation()
    {
        seed(photoKey, 2);
        ReflectionTestUtils.setField(service, "redisTemplate", recordingTemplate(false, false, true));
        assertThrows(IllegalStateException.class, () -> service.consumeQuota(member, "photo_check"));
        assertEquals(2, count(photoKey));
    }

    @Test
    void cancellationBlocksDelayedReserveAndDoesNotDebitOthers()
    {
        seed(photoKey, 2);
        ReflectionTestUtils.setField(service, "redisTemplate", recordingTemplate(true, false, false));
        assertThrows(IllegalStateException.class, () -> service.consumeQuota(member, "photo_check"));
        String nonce = ((String) reserveKeys.get(0).get(1)).substring("ai:gen:refund:".length());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "reserveQuota", photoKey, nonce));
        assertEquals(2, count(photoKey));
    }

    @Test
    void generateLostReserveReplyRestoresQuotaBeforeAnySafetyOrProviderCall()
    {
        String genKey = photoKey.replace(":photo:", ":gen:");
        seed(genKey, 2);
        IWxContentSecurityService safety = mock(IWxContentSecurityService.class);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", safety);
        ReflectionTestUtils.setField(service, "redisTemplate", recordingTemplate(false, true, false));
        assertThrows(IllegalStateException.class,
                () -> service.generate(member, "club", "theme_draft", "system", "user"));
        assertEquals(2, count(genKey));
        verifyNoInteractions(safety);
    }

    @Test
    void photoFallbackKeepsUnmeteredContractWithoutLeakingAppliedReservation()
    {
        seed(photoKey, 2);
        ReflectionTestUtils.setField(service, "redisTemplate", recordingTemplate(false, true, false));
        PhotoCheckVisionService photo = new PhotoCheckVisionService();
        ReflectionTestUtils.setField(photo, "aiGatewayService", service);
        String reservation = ReflectionTestUtils.invokeMethod(photo, "chargeQuota", member);
        assertEquals("unmetered", reservation);
        ReflectionTestUtils.invokeMethod(photo, "refundQuota", member, reservation);
        assertEquals(2, count(photoKey));
    }

    @Test
    void photoNullReplyUsesUnmeteredFallbackInsteadOfQuotaExhaustion()
    {
        seed(photoKey, 2);
        ReflectionTestUtils.setField(service, "redisTemplate", recordingTemplate(false, false, true));
        PhotoCheckVisionService photo = new PhotoCheckVisionService();
        ReflectionTestUtils.setField(photo, "aiGatewayService", service);
        String reservation = ReflectionTestUtils.invokeMethod(photo, "chargeQuota", member);
        assertEquals("unmetered", reservation);
        ReflectionTestUtils.invokeMethod(photo, "refundQuota", member, reservation);
        assertEquals(2, count(photoKey));
        assertEquals(0, count((String) reserveKeys.get(0).get(1)));
    }

    @Test
    void photoConfirmedOverLimitKeepsQuotaExhaustionFallback()
    {
        seed(photoKey, 30);
        ReflectionTestUtils.setField(service, "photoDailyLimit", 30);
        PhotoCheckVisionService photo = new PhotoCheckVisionService();
        ReflectionTestUtils.setField(photo, "aiGatewayService", service);
        assertNull(ReflectionTestUtils.invokeMethod(photo, "chargeQuota", member));
        assertEquals(31, count(photoKey));
    }

    @Test
    void actualReservationRefundReplaysOnceUnderConcurrencyAndKeepsExpiry() throws Exception
    {
        seed(photoKey, 2);
        String reservation = service.consumeQuota(member, "photo_check");
        assertTrue(reservation.contains("|v2:"));
        assertEquals(3, count(photoKey));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < 24; i++) attempts.add(workers.submit(
                    () -> service.refundQuota(member, "photo_check", reservation)));
            for (Future<?> attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertEquals(2, count(photoKey));
        assertTrue(redis.getExpire(photoKey) > 0 && redis.getExpire(photoKey) <= 300);
    }

    @SuppressWarnings("unchecked")
    @Test
    void productionSerializerTtlMustBeIntegerAndInvalidTtlCannotPartiallyIncrement()
    {
        FastJson2JsonRedisSerializer<Object> serializer = new FastJson2JsonRedisSerializer<>(Object.class);
        assertEquals("86400L", new String(serializer.serialize(86400L), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("86400", new String(serializer.serialize(86400), java.nio.charset.StandardCharsets.UTF_8));
        String marker = "ai:gen:refund:v2:" + UUID.randomUUID();
        ownedKeys.add(marker);
        RedisScript<Long> script = (RedisScript<Long>) ReflectionTestUtils.getField(AiGatewayServiceImpl.class, "RESERVE_SCRIPT");
        assertEquals(Long.valueOf(-1), redis.execute(script, java.util.Arrays.asList(photoKey, marker), 86400L));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(photoKey)), "invalid serialized TTL must fail before INCR");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(marker)));
        service.consumeQuota(member, "photo_check");
        byte[] stored = redis.execute((org.springframework.data.redis.core.RedisCallback<byte[]>)
                connection -> connection.get(photoKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("1", new String(stored, java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(redis.getExpire(photoKey) > 86390 && redis.getExpire(photoKey) <= 86400);
    }

    @Test
    void reserveStillSetsOriginalTtlAndCountsOverLimitAttempts()
    {
        ReflectionTestUtils.setField(service, "photoDailyLimit", 1);
        service.consumeQuota(member, "photo_check");
        assertTrue(redis.getExpire(photoKey) > 86390 && redis.getExpire(photoKey) <= 86400);
        assertThrows(AiServiceException.class, () -> service.consumeQuota(member, "photo_check"));
        assertEquals(2, count(photoKey));
        assertTrue(redis.getExpire(photoKey) <= 86400);
    }
}
