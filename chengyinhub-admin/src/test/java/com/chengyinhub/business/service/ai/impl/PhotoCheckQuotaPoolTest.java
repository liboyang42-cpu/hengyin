package com.chengyinhub.business.service.ai.impl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.config.AiModelConfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拍照审核额度分池契约(2026-09-18)。
 *
 * <p>硬负控:photo_check 必须计进 photo 独立池,绝不吃 gen 创作额度。
 * 共用 gen 池时,玩家拍几张照就会把创作额度耗光,之后审核静默降级
 * (degraded 只打标不拦路),真审核悄悄停掉 —— 不炸所以发现不了,必须有门禁看着。
 */
class PhotoCheckQuotaPoolTest {

    private static final long MEMBER_ID = 7L;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private AiGatewayServiceImpl service;
    private ValueOperations<Object, Object> valueOperations;

    private final String today = LocalDate.now().format(DATE_FMT);
    private final String photoKey = "ai:gen:limit:photo:" + MEMBER_ID + ":" + today;
    private final String genKey = "ai:gen:limit:gen:" + MEMBER_ID + ":" + today;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RedisTemplate<Object, Object> redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        AiModelConfig aiModelConfig = new AiModelConfig();
        ReflectionTestUtils.setField(aiModelConfig, "dailyLimit", 20);
        ReflectionTestUtils.setField(aiModelConfig, "npcDailyLimit", 100);

        service = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "aiModelConfig", aiModelConfig);
        ReflectionTestUtils.setField(service, "photoDailyLimit", 30);
    }

    @Test
    void 负控_拍照审核计进photo池_不碰gen创作池() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        service.consumeQuota(MEMBER_ID, "photo_check");

        verify(valueOperations).increment(photoKey);
        verify(valueOperations, never()).increment(genKey);
    }

    @Test
    void 负控_photo池按photoDailyLimit判_不按gen的dailyLimit() {
        // 25 > gen 的 20,但 ≤ photo 的 30:拍照不该在创作额度用完时被误伤
        when(valueOperations.increment(anyString())).thenReturn(25L);
        assertDoesNotThrow(() -> service.consumeQuota(MEMBER_ID, "photo_check"),
                "photo 池上限是 30,25 次不该被判超限");

        when(valueOperations.increment(anyString())).thenReturn(31L);
        assertThrows(AiServiceException.class, () -> service.consumeQuota(MEMBER_ID, "photo_check"),
                "31 > 30 必须判超限");
    }

    @Test
    void 创作类仍走gen池按dailyLimit判() {
        when(valueOperations.increment(anyString())).thenReturn(25L);

        assertThrows(AiServiceException.class, () -> service.consumeQuota(MEMBER_ID, "theme_draft"),
                "创作类 25 > 20,仍必须按 gen 池判超限");
        verify(valueOperations).increment(genKey);
        verify(valueOperations, never()).increment(photoKey);
    }

    @Test
    void 退款退的是photo池键() {
        when(valueOperations.decrement(anyString())).thenReturn(29L);

        service.refundQuota(MEMBER_ID, "photo_check");

        verify(valueOperations).decrement(photoKey);
        verify(valueOperations, never()).decrement(genKey);
    }

    @Test
    void 未知场景仍落gen池_分池只认白名单() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        service.consumeQuota(MEMBER_ID, "some_future_scene");

        verify(valueOperations).increment(genKey);
        verify(valueOperations, never()).increment(photoKey);
    }
}
