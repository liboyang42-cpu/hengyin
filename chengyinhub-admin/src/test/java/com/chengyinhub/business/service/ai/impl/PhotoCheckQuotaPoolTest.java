package com.chengyinhub.business.service.ai.impl;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.config.AiModelConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pool and receipt call contracts; actual Lua execution is covered separately by Redis IT. */
class PhotoCheckQuotaPoolTest {
    private AiGatewayServiceImpl service;
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
        service = new AiGatewayServiceImpl();
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config,"dailyLimit",20);
        ReflectionTestUtils.setField(config,"npcDailyLimit",100);
        ReflectionTestUtils.setField(service,"photoDailyLimit",30);
        ReflectionTestUtils.setField(service,"aiModelConfig",config);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
    }

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
    }

    @Test void planningDoesNotWriteQuotaAndConsumptionKeepsOriginalDayAndNonce() {
        String nonce = "0123456789abcdef0123456789abcdef";
        String planned = service.planQuotaReservation(7L, "photo_check", nonce);
        assertTrue(planned.endsWith("|" + nonce));
        assertTrue(reserves.isEmpty());
        assertTrue(refunds.isEmpty());
        String original = "ai:gen:limit:photo:7:20261020|" + nonce;
        assertEquals(original, service.consumePlannedQuota(7L, "photo_check", original));
        assertEquals("ai:gen:limit:photo:7:20261020", reserves.get(0).get(0));
        assertEquals("ai:gen:reservation:ai:gen:limit:photo:7:20261020:" + nonce, reserves.get(0).get(2));
    }

    @Test void plannedQuotaRejectsForeignOwnerPoolAndInvalidIdentityBeforeRedis() {
        String token = "ai:gen:limit:photo:7:20261020|0123456789abcdef0123456789abcdef";
        assertThrows(IllegalArgumentException.class,
                () -> service.consumePlannedQuota(8L, "photo_check", token));
        assertThrows(IllegalArgumentException.class,
                () -> service.consumePlannedQuota(7L, "theme_draft", token));
        assertThrows(java.time.format.DateTimeParseException.class,
                () -> service.consumePlannedQuota(7L, "photo_check", token.replace("20261020", "20260230")));
        assertThrows(IllegalArgumentException.class,
                () -> service.planQuotaReservation(7L, "photo_check", "new-nonce"));
        assertTrue(reserves.isEmpty());
        assertTrue(refunds.isEmpty());
    }

    @Test void plannedQuotaRecordsOriginalRecoveryEvidenceBeforeRedis() {
        com.chengyinhub.business.service.ai.AiQuotaRecoveryService recovery =
                mock(com.chengyinhub.business.service.ai.AiQuotaRecoveryService.class);
        ReflectionTestUtils.setField(service, "quotaRecovery", recovery);
        String token = "ai:gen:limit:photo:7:20261020|0123456789abcdef0123456789abcdef";
        service.consumePlannedQuota(7L, "photo_check", token);
        org.mockito.InOrder order = inOrder(recovery, redis);
        order.verify(recovery).prepare(eq(7L), eq("photo_check"), eq("photo"),
                eq("ai:gen:limit:photo:7:20261020"), eq((String) reserves.get(0).get(2)));
        order.verify(redis).execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt());
        order.verify(recovery).reserved((String) reserves.get(0).get(2), 1L);
    }
}
