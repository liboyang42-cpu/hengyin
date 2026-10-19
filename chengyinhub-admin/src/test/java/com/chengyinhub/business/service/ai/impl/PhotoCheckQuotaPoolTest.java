package com.chengyinhub.business.service.ai.impl;

<<<<<<< public
import java.util.*;
import org.junit.jupiter.api.*;
=======
import java.time.LocalDate;
import java.util.Arrays;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
>>>>>>> private-codex/batch1
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.config.AiModelConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

<<<<<<< public
/** Pool and receipt call contracts; actual Lua execution is covered separately by Redis IT. */
=======
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
>>>>>>> private-codex/batch1
class PhotoCheckQuotaPoolTest {
    private AiGatewayServiceImpl service;
<<<<<<< public
    private RedisTemplate<Object,Object> redis;
    private Long used = 1L;
    private final List<List<Object>> reserves = new ArrayList<>();
    private final List<List<Object>> refunds = new ArrayList<>();

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            reserves.add(new ArrayList<>((List<Object>)call.getArgument(1)));
            return used;
        });
        when(redis.execute(any(RedisScript.class), anyList())).thenAnswer(call -> {
            refunds.add(new ArrayList<>((List<Object>)call.getArgument(1)));
            return 1L;
        });
=======
    private ValueOperations<Object, Object> valueOperations;
    private RedisTemplate<Object, Object> redisTemplate;

    private final String today = LocalDate.now().format(DATE_FMT);
    private final String photoKey = "ai:gen:limit:photo:" + MEMBER_ID + ":" + today;
    private final String genKey = "ai:gen:limit:gen:" + MEMBER_ID + ":" + today;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        AiModelConfig aiModelConfig = new AiModelConfig();
        ReflectionTestUtils.setField(aiModelConfig, "dailyLimit", 20);
        ReflectionTestUtils.setField(aiModelConfig, "npcDailyLimit", 100);

>>>>>>> private-codex/batch1
        service = new AiGatewayServiceImpl();
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config,"dailyLimit",20);
        ReflectionTestUtils.setField(config,"npcDailyLimit",100);
        ReflectionTestUtils.setField(service,"photoDailyLimit",30);
        ReflectionTestUtils.setField(service,"aiModelConfig",config);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
    }

<<<<<<< public
    @Test void photoUsesOwnPoolAndReturnsOpaqueReceipt() {
        String receipt = service.consumeQuota(7L,"photo_check");
        assertTrue(receipt.startsWith("ai:gen:limit:photo:7:"));
        assertTrue(String.valueOf(reserves.get(0).get(0)).startsWith("ai:gen:limit:photo:7:"));
        assertTrue(String.valueOf(reserves.get(0).get(2)).contains(receipt.substring(receipt.indexOf('|')+1)));
    }
    @Test void photoUsesThirtyWhileCreationUsesTwenty() {
        used=25L;
        assertDoesNotThrow(() -> service.consumeQuota(7L,"photo_check"));
        assertThrows(AiServiceException.class,()->service.consumeQuota(7L,"theme_draft"));
        used=31L;
        assertThrows(AiServiceException.class,()->service.consumeQuota(7L,"photo_check"));
        assertTrue(refunds.isEmpty());
    }
    @Test void refundUsesExactOriginalReceiptAndRejectsOtherMemberOrPool() {
        String token=service.consumeQuota(7L,"photo_check");
        service.refundQuota(8L,"photo_check",token);
        service.refundQuota(7L,"theme_draft",token);
        assertTrue(refunds.isEmpty());
        service.refundQuota(7L,"photo_check",token);
        assertEquals(reserves,refunds);
    }
    @Test void unknownReplyIsServiceFailureAndCompensatesItsOwnNonce() {
        used=null;
        assertThrows(IllegalStateException.class,()->service.consumeQuota(7L,"photo_check"));
        assertEquals(1, refunds.size());
        assertEquals(reserves,refunds);
    }
    @Test void futureScenesKeepCreationPool() {
        String token=service.consumeQuota(7L,"some_future_scene");
        assertTrue(token.startsWith("ai:gen:limit:gen:7:"));
=======
    @Test
    void 负控_拍照审核计进photo池_不碰gen创作池() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1L);

        service.consumeQuota(MEMBER_ID, "photo_check");

        verify(redisTemplate).execute(any(RedisScript.class), argThat(keys -> photoKey.equals(keys.get(0))), eq(86400));
        verify(redisTemplate, never()).execute(any(RedisScript.class), argThat(keys -> genKey.equals(keys.get(0))), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 负控_photo池按photoDailyLimit判_不按gen的dailyLimit() {
        // 25 > gen 的 20,但 ≤ photo 的 30:拍照不该在创作额度用完时被误伤
        when(redisTemplate.execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(25L);
        assertDoesNotThrow(() -> service.consumeQuota(MEMBER_ID, "photo_check"),
                "photo 池上限是 30,25 次不该被判超限");

        when(redisTemplate.execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(31L);
        assertThrows(AiServiceException.class, () -> service.consumeQuota(MEMBER_ID, "photo_check"),
                "31 > 30 必须判超限");
    }

    @Test
    void 创作类仍走gen池按dailyLimit判() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(25L);

        assertThrows(AiServiceException.class, () -> service.consumeQuota(MEMBER_ID, "theme_draft"),
                "创作类 25 > 20,仍必须按 gen 池判超限");
        verify(redisTemplate).execute(any(RedisScript.class), argThat(keys -> genKey.equals(keys.get(0))), eq(86400));
        verify(redisTemplate, never()).execute(any(RedisScript.class), argThat(keys -> photoKey.equals(keys.get(0))), org.mockito.ArgumentMatchers.anyInt());
    }

    @SuppressWarnings("unchecked")
    @Test
    void 退款退的是photo池键() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(1L);
        String reservation = service.consumeQuota(MEMBER_ID, "photo_check");
        service.refundQuota(MEMBER_ID, "photo_check", reservation);
        verify(redisTemplate).execute(any(RedisScript.class),
                eq(Arrays.asList(photoKey, "ai:gen:refund:" + reservation.substring(reservation.lastIndexOf('|') + 1))));
        verify(valueOperations, never()).decrement(genKey);
    }

    @Test
    void 未知场景仍落gen池_分池只认白名单() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1L);

        service.consumeQuota(MEMBER_ID, "some_future_scene");

        verify(redisTemplate).execute(any(RedisScript.class), argThat(keys -> genKey.equals(keys.get(0))), eq(86400));
        verify(redisTemplate, never()).execute(any(RedisScript.class), argThat(keys -> photoKey.equals(keys.get(0))), org.mockito.ArgumentMatchers.anyInt());
>>>>>>> private-codex/batch1
    }
}
