package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.web.exception.RuntimeFailureAlarmFilter;
import io.lettuce.core.RedisCommandTimeoutException;
import org.springframework.dao.QueryTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmThrottleTest {
    @Test void knownRedisFailuresNeverAskTheFailedRedisForAnAlarmLease() {
        RedisCache redis = mock(RedisCache.class);
        AtomicInteger calls = new AtomicInteger();
        AlarmService alarm = service(redis, calls, 200, "https://alerts.example.test/stub");
        alarm.alarmThrottled("runtime.redis.connection", "cache", "type=RedisCommandTimeoutException");
        alarm.alarmThrottled("runtime.redis.connection", "cache", "type=RedisCommandTimeoutException");
        alarm.alarmThrottled("runtime.redis.timeout", "cache", "type=RedisCommandTimeoutException");
        assertEquals(2, calls.get(), "同窗口同事件只投递一次，不同Redis事件独立");
        verifyNoInteractions(redis);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> local = (java.util.Map<String, Long>) ReflectionTestUtils.getField(alarm, "localAttempts");
        local.put("runtime.redis.connection", System.currentTimeMillis() - 601_000L);
        alarm.alarmThrottled("runtime.redis.connection", "cache", "type=RedisCommandTimeoutException");
        assertEquals(3, calls.get(), "复用原本地10分钟窗口，过期后允许重试");
        verifyNoInteractions(redis);
    }

    @Test void actualFilterAndAlarmHandleWrappedLettuceTimeoutWithoutASecondRedisCommand() {
        RedisCache redis = mock(RedisCache.class);
        AtomicInteger calls = new AtomicInteger();
        AlarmService alarm = spy(service(redis, calls, 200, "https://alerts.example.test/stub"));
        RuntimeFailureAlarmFilter filter = new RuntimeFailureAlarmFilter(alarm);
        RuntimeException failure = new IllegalStateException("private-provider-detail",
                new QueryTimeoutException("private-command-detail",
                        new RedisCommandTimeoutException("private-redis-detail")));
        filter.systemFailure(failure);
        filter.systemFailure(failure);
        verify(alarm, times(2)).alarmThrottled("runtime.redis.connection", "缓存连接异常",
                "type=RedisCommandTimeoutException");
        verify(alarm, never()).alarmThrottled(eq("runtime.request.unhandled"), anyString(), anyString());
        assertEquals(1, calls.get());
        verifyNoInteractions(redis);
    }

    @Test void oneAtomicEventLeaseLimitsAttemptsAndExpiryAllowsRetry() {
        RedisCache redis = mock(RedisCache.class);
        when(redis.setIfAbsent(eq("alarm:attempt:monitor.audit.backlog"), eq("1"), eq(600), eq(TimeUnit.SECONDS)))
                .thenReturn(true, false, true);
        AtomicInteger calls = new AtomicInteger();
        AlarmService alarm = service(redis, calls, 200, "https://alerts.example.test/stub");
        for (int i = 0; i < 3; i++) alarm.alarmThrottled("monitor.audit.backlog", "backlog", "taskIds=[1]");
        assertEquals(2, calls.get());
        verify(redis, times(3)).setIfAbsent("alarm:attempt:monitor.audit.backlog", "1", 600, TimeUnit.SECONDS);
    }

    @Test void differentFixedEventKeysRemainIndependent() {
        RedisCache redis = mock(RedisCache.class);
        when(redis.setIfAbsent(anyString(), any(), eq(600), eq(TimeUnit.SECONDS))).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        AlarmService alarm = service(redis, calls, 200, "https://alerts.example.test/stub");
        alarm.alarmThrottled("monitor.withdraw.stuck", "stuck", "withdrawIds=[1]");
        alarm.alarmThrottled("monitor.withdraw.manual", "manual", "withdrawIds=[2]");
        assertEquals(2, calls.get());
        verify(redis).setIfAbsent("alarm:attempt:monitor.withdraw.stuck", "1", 600, TimeUnit.SECONDS);
        verify(redis).setIfAbsent("alarm:attempt:monitor.withdraw.manual", "1", 600, TimeUnit.SECONDS);
    }

    // 需求变更:Redis 失效时不再「每次都投递」(会在每个请求上触发 runtime.redis.* 告警、刷屏并占住请求线程),
    // 改为进程内限频——首次仍投递,同一实例 10 分钟内同一事件不再重复;不同事件互不影响。
    @Test void nullOrFailedRedisIsUnknownAndFallsBackToLocalThrottle() {
        RedisCache redis = mock(RedisCache.class);
        when(redis.setIfAbsent(anyString(), any(), eq(600), eq(TimeUnit.SECONDS)))
                .thenReturn(null).thenThrow(new IllegalStateException("synthetic private details"));
        AtomicInteger calls = new AtomicInteger();
        AlarmService alarm = service(redis, calls, 200, "https://alerts.example.test/stub");
        Logger logger = (Logger) LoggerFactory.getLogger(AlarmService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            alarm.alarmThrottled("monitor.metrics.sample", "sample", "source=app");
            alarm.alarmThrottled("monitor.metrics.sample", "sample", "source=app");
        } finally { logger.detachAppender(logs); logs.stop(); }
        assertEquals(1, calls.get(), "首次未知租约仍投递,同窗口内重复尝试由本地限频挡下");
        alarm.alarmThrottled("monitor.metrics.other", "other", "source=app");
        assertEquals(2, calls.get(), "不同事件互不抑制");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> local = (java.util.Map<String, Long>) ReflectionTestUtils.getField(alarm, "localAttempts");
        local.put("monitor.metrics.sample", System.currentTimeMillis() - 601_000L);
        alarm.alarmThrottled("monitor.metrics.sample", "sample", "source=app");
        assertEquals(3, calls.get(), "本地窗口过期后恢复投递");
        assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("IllegalStateException")));
        assertFalse(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("synthetic private details")));
        assertTrue(logs.list.stream().allMatch(event -> event.getThrowableProxy() == null));
    }

    @Test void leaseDoesNotTurnMissingSinkOrRejectedHttpIntoDelivery() {
        RedisCache redis = mock(RedisCache.class);
        when(redis.setIfAbsent(anyString(), any(), eq(600), eq(TimeUnit.SECONDS))).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        AlarmService missing = service(redis, calls, 200, "");
        missing.alarmThrottled("monitor.audit.backlog", "backlog", "taskIds=[1]");
        assertEquals(0, calls.get());
        assertEquals(AlarmService.AlarmDelivery.SKIPPED_NOT_CONFIGURED, missing.deliver("backlog", "taskIds=[1]"));
        AlarmService failed = service(redis, calls, 503, "https://alerts.example.test/stub");
        failed.alarmThrottled("monitor.audit.backlog", "backlog", "taskIds=[1]");
        assertEquals(1, calls.get());
        assertEquals(AlarmService.AlarmDelivery.FAILED, failed.deliver("backlog", "taskIds=[1]"));
    }

    @Test void arbitraryPayloadCannotBecomeAnUnboundedOrSensitiveRedisKey() {
        RedisCache redis = mock(RedisCache.class);
        AtomicInteger calls = new AtomicInteger();
        AlarmService alarm = service(redis, calls, 200, "https://alerts.example.test/stub");
        assertThrows(IllegalArgumentException.class, () -> alarm.alarmThrottled("https://invalid.example/path", "title", "detail"));
        assertThrows(IllegalArgumentException.class, () -> alarm.alarmThrottled(null, "title", "detail"));
        assertThrows(IllegalArgumentException.class, () -> alarm.alarmThrottled("", "title", "detail"));
        assertThrows(IllegalArgumentException.class, () -> alarm.alarmThrottled("runtime.redis.connection?token=private", "title", "detail"));
        verifyNoInteractions(redis);
        assertEquals(0, calls.get());
    }

    private AlarmService service(RedisCache redis, AtomicInteger calls, int status, String sink) {
        AlarmService alarm = new AlarmService((url, body) -> {
            calls.incrementAndGet(); return new TransportResponse(status, "{\"errcode\":0}");
        }, sink, "");
        ReflectionTestUtils.setField(alarm, "redisCache", redis);
        return alarm;
    }
}
