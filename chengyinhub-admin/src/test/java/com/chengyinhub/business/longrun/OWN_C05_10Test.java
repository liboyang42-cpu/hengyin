package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import com.chengyinhub.common.config.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN: display arithmetic only; Redis is a mock. */
class OWN_C05_10Test {
    @SuppressWarnings("unchecked")
    @Test
    void largeValidCountersNeverWrapIntoFullRemainingQuota() {
        AiGatewayServiceImpl gateway = new AiGatewayServiceImpl();
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "dailyLimit", 20);
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        ValueOperations<Object, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(gateway, "redisTemplate", redis);
        ReflectionTestUtils.setField(gateway, "aiModelConfig", config);
        for (Object count : new Object[] {"2147483648", 4294967296L, Long.MAX_VALUE, 20L}) {
            when(values.get(any())).thenReturn(count);
            assertEquals(0, gateway.remainingQuota(7L, "merchant_plan"), String.valueOf(count));
        }
        when(values.get(any())).thenReturn(3L);
        assertEquals(17, gateway.remainingQuota(7L, "merchant_plan"));
        for (Object count : new Object[] {0L, "0", null}) {
            when(values.get(any())).thenReturn(count);
            assertEquals(20, gateway.remainingQuota(7L, "merchant_plan"));
        }
    }
}
