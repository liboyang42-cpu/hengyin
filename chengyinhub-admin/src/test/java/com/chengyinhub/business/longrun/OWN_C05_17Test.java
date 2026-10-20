package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.ai.AiQuotaRecoveryService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import com.chengyinhub.common.config.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN: verifies internal classification, not a live quota/Redis test. */
class OWN_C05_17Test {
    @SuppressWarnings("unchecked")
    @Test
    void actualOverLimitReservationUsesExistingTypedReason() {
        AiGatewayServiceImpl gateway = new AiGatewayServiceImpl();
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "dailyLimit", 20);
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenReturn(21L);
        AiQuotaRecoveryService recovery = mock(AiQuotaRecoveryService.class);
        ReflectionTestUtils.setField(gateway, "redisTemplate", redis);
        ReflectionTestUtils.setField(gateway, "aiModelConfig", config);
        ReflectionTestUtils.setField(gateway, "quotaRecovery", recovery);
        AiServiceException failure = assertThrows(AiServiceException.class,
                () -> gateway.consumeQuota(7L, "merchant_plan"));
        assertEquals(AiServiceException.Reason.RATE_LIMITED, failure.getReason());
        assertEquals("今日AI次数已用完(20/天),明天再来吧", failure.getMessage());
        verify(recovery).retain(anyString(), eq("RATE_LIMITED"));
        verify(recovery, never()).requireRefund(anyString(), anyString());
    }
}
