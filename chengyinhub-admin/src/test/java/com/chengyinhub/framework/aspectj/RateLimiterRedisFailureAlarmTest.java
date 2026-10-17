package com.chengyinhub.framework.aspectj;

import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.framework.web.exception.RuntimeFailureAlarmFilter;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimiterRedisFailureAlarmTest {
    @RateLimiter(key = "fixture:", time = 60, count = 5, limitType = LimitType.DEFAULT)
    public void limited() { }

    @Test
    @SuppressWarnings("unchecked")
    void springWrappedLettuceTimeoutPreservesRateLimitFailureAndItsRedisCause() throws Exception {
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("private-command",
                        new io.lettuce.core.RedisCommandTimeoutException("private-redis")));
        RateLimiterAspect aspect = new RateLimiterAspect();
        aspect.setRedisTemplate1(redis);
        aspect.setLimitScript(mock(RedisScript.class));
        JoinPoint point = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(point.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(getClass().getMethod("limited"));
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                aspect.doBefore(point, getClass().getMethod("limited").getAnnotation(RateLimiter.class)));
        assertEquals("服务器限流异常，请稍候再试", failure.getMessage());
        AlarmService alarms = mock(AlarmService.class);
        new RuntimeFailureAlarmFilter(alarms).systemFailure(failure);
        verify(alarms).alarmThrottled("runtime.redis.connection", "缓存连接异常", "type=RedisCommandTimeoutException");
        verifyNoMoreInteractions(alarms);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureKeepsUserMessageAndIsClassifiedAsRedisConnectionAlarm() throws Exception {
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("pool exhausted"));
        RateLimiterAspect aspect = new RateLimiterAspect();
        aspect.setRedisTemplate1(redis);
        aspect.setLimitScript(mock(RedisScript.class));
        JoinPoint point = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(point.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(getClass().getMethod("limited"));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                aspect.doBefore(point, getClass().getMethod("limited").getAnnotation(RateLimiter.class)));
        assertEquals("服务器限流异常，请稍候再试", failure.getMessage());

        AlarmService alarms = mock(AlarmService.class);
        new RuntimeFailureAlarmFilter(alarms).systemFailure(failure);
        verify(alarms).alarmThrottled("runtime.redis.connection", "缓存连接异常", "type=RedisConnectionFailureException");
        verifyNoMoreInteractions(alarms);
    }
}
