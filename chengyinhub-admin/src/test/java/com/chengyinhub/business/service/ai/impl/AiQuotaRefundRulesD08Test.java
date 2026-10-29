package com.chengyinhub.business.service.ai.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.domain.AiQuotaReservationRecord;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.mapper.AiQuotaReservationMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException;
import com.chengyinhub.business.service.ai.AiQuotaRecoveryService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.LlmClient;
import com.chengyinhub.common.config.AiModelConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Doc2 O-12 / D-08 退款规则：
 * (a) 只退到预占时的原日 key；(b) 每个 traceId 至多退一次；(c) 内容安全拦截（INPUT/OUTPUT_BLOCKED）不退。
 * Lua 原子性另由 AiQuotaReservationRedisIT / AiQuotaRecoveryMySqlRedisIT 的真库用例覆盖。
 */
class AiQuotaRefundRulesD08Test {

    private static final Long MEMBER_ID = 1001L;
    private static final String TODAY_PREFIX = "ai:gen:limit:gen:" + MEMBER_ID + ":";

    private AiGatewayServiceImpl gateway;
    private RedisTemplate<Object, Object> redisTemplate;
    private IWxContentSecurityService wxContentSecurityService;
    private AiGenerationLogMapper aiGenerationLogMapper;
    private AiQuotaRecoveryService quotaRecovery;
    private LlmClient llm;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        wxContentSecurityService = mock(IWxContentSecurityService.class);
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        aiGenerationLogMapper = mock(AiGenerationLogMapper.class);
        quotaRecovery = mock(AiQuotaRecoveryService.class);
        llm = mock(LlmClient.class);
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "dailyLimit", 20);
        ReflectionTestUtils.setField(config, "npcDailyLimit", 100);
        ReflectionTestUtils.setField(config, "provider", "deepseek");

        when(llm.name()).thenReturn("deepseek");
        when(llm.chat(anyString(), anyString())).thenReturn("{\"result\":\"ok\"}");
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(null);
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        when(umsMemberService.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(aiGenerationLogMapper.insertAiGenerationLog(any())).thenReturn(1);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenReturn(1L);

        gateway = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(gateway, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(gateway, "quotaRecovery", quotaRecovery);
        ReflectionTestUtils.setField(gateway, "wxContentSecurityService", wxContentSecurityService);
        ReflectionTestUtils.setField(gateway, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(gateway, "aiGenerationLogMapper", aiGenerationLogMapper);
        ReflectionTestUtils.setField(gateway, "aiModelConfig", config);
        ReflectionTestUtils.setField(gateway, "llmClients", Collections.singletonList(llm));
    }

    private String loggedTraceId() {
        ArgumentCaptor<AiGenerationLog> log = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(aiGenerationLogMapper).insertAiGenerationLog(log.capture());
        return log.getValue().getTraceId();
    }

    private String preparedReceipt() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(quotaRecovery).prepare(eq(MEMBER_ID), eq("merchant_plan"), eq("gen"), key.capture(), receipt.capture());
        assertTrue(key.getValue().startsWith(TODAY_PREFIX));
        assertEquals("ai:gen:reservation:" + key.getValue() + ":", receipt.getValue().substring(0, receipt.getValue().length() - 32));
        return receipt.getValue();
    }

    // ---- (b) 退款身份 = traceId ----

    @Test
    void reservationNonceIsTheCallTraceIdSoRefundIsKeyedByTraceId() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.MSG_BUSY);
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> gateway.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());

        String traceId = loggedTraceId();
        assertTrue(traceId.matches("[a-f0-9]{32}"));
        String receipt = preparedReceipt();
        assertTrue(receipt.endsWith(":" + traceId), "reservation nonce must be the call traceId");
        verify(quotaRecovery).requireRefund(receipt, "SERVICE_FAILURE");
        verify(quotaRecovery, never()).retain(anyString(), anyString());
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void repeatedRefundOfSameCallReusesOneTraceIdReceipt() {
        String token = gateway.consumeQuota(MEMBER_ID, "merchant_plan");
        gateway.refundQuota(MEMBER_ID, "merchant_plan", token);
        gateway.refundQuota(MEMBER_ID, "merchant_plan", token);
        ArgumentCaptor<String> receipts = ArgumentCaptor.forClass(String.class);
        verify(quotaRecovery, times(2)).requireRefund(receipts.capture(), eq("SERVICE_FAILURE"));
        // 重放只能落到同一个收据；去重由收据 '0' 墓碑 + 台账 fence 完成（见下方 recovery 用例）。
        assertEquals(1, receipts.getAllValues().stream().distinct().count());
    }

    // ---- (c) 内容安全拦截不退 ----

    @Test
    void inputBlockedRetainsQuotaAndNeverRequestsRefund() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn("包含违规内容");
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> gateway.generate(MEMBER_ID, "club", "merchant_plan", "s", "bad"));
        assertEquals(AiServiceException.Reason.INPUT_BLOCKED, error.getReason());

        String receipt = preparedReceipt();
        assertTrue(receipt.endsWith(":" + loggedTraceId()));
        verify(quotaRecovery).retain(receipt, "INPUT_REJECTED");
        verify(quotaRecovery, never()).requireRefund(anyString(), anyString());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
    }

    @Test
    void outputBlockedRetainsQuotaAndNeverRequestsRefund() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(null).thenReturn("生成内容违规");
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> gateway.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(AiServiceException.Reason.OUTPUT_BLOCKED, error.getReason());

        String receipt = preparedReceipt();
        verify(quotaRecovery).retain(receipt, "OUTPUT_REJECTED");
        verify(quotaRecovery, never()).requireRefund(anyString(), anyString());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
    }

    // ---- 既有语义：上游失败 / provider 结果未知 ----

    @Test
    void providerOutcomeUnknownNeitherRefundsNorRetainsAndLeavesModelStartedForRecovery() {
        when(llm.chat(anyString(), anyString())).thenThrow(new IllegalStateException("timeout"));
        assertThrows(AiProviderOutcomeUnknownException.class,
                () -> gateway.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        String receipt = preparedReceipt();
        verify(quotaRecovery).stage(receipt, "MODEL_STARTED");
        verify(quotaRecovery, never()).requireRefund(anyString(), anyString());
        verify(quotaRecovery, never()).retain(anyString(), anyString());
    }

    // ---- (a) 跨零点：只退原日 ----

    @Test
    void refundDetectedAfterMidnightTargetsOnlyTheReservedDay() {
        String yesterdayKey = TODAY_PREFIX + "20261022";
        String nonce = "0123456789abcdef0123456789abcdef";
        gateway.refundQuota(MEMBER_ID, "merchant_plan", yesterdayKey + "|" + nonce);
        verify(quotaRecovery).requireRefund("ai:gen:reservation:" + yesterdayKey + ":" + nonce, "SERVICE_FAILURE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisOnlyRefundAfterMidnightPassesOnlyReservedDayKeys() {
        ReflectionTestUtils.setField(gateway, "quotaRecovery", null);
        String yesterdayKey = TODAY_PREFIX + "20261022";
        String nonce = "0123456789abcdef0123456789abcdef";
        gateway.refundQuota(MEMBER_ID, "merchant_plan", yesterdayKey + "|" + nonce);
        ArgumentCaptor<List<Object>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture());
        assertEquals(Arrays.<Object>asList(yesterdayKey, yesterdayKey + ":generation",
                "ai:gen:reservation:" + yesterdayKey + ":" + nonce), keys.getValue());
    }

    @Test
    void forgedTokenCannotRedirectRefundToAnotherDayMemberOrPool() {
        String nonce = "0123456789abcdef0123456789abcdef";
        gateway.refundQuota(MEMBER_ID + 1, "merchant_plan", TODAY_PREFIX + "20261022|" + nonce);
        gateway.refundQuota(MEMBER_ID, "npc_chat", TODAY_PREFIX + "20261022|" + nonce);
        gateway.refundQuota(MEMBER_ID, "merchant_plan", TODAY_PREFIX + "2026102|" + nonce);
        gateway.refundQuota(MEMBER_ID, "merchant_plan", TODAY_PREFIX + "20261022|not-a-nonce");
        verify(quotaRecovery, never()).requireRefund(anyString(), anyString());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
    }

    // ---- recovery 台账：原日 key、至多一次、被拦截的不可再退 ----

    private AiQuotaReservationMapper mapper;
    private RedisTemplate<Object, Object> recoveryRedis;
    private AiQuotaRecoveryService recovery;
    private AiQuotaReservationRecord row;
    private static final String OLD_KEY = "ai:gen:limit:gen:7:20261022";
    private static final String OLD_RECEIPT = "ai:gen:reservation:" + OLD_KEY + ":0123456789abcdef0123456789abcdef";

    @SuppressWarnings("unchecked")
    private void recoveryFixture(String state) {
        mapper = mock(AiQuotaReservationMapper.class);
        recoveryRedis = mock(RedisTemplate.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        recovery = new AiQuotaRecoveryService(mapper, recoveryRedis, tx,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        row = new AiQuotaReservationRecord();
        row.setId(1L); row.setFenceVersion(0L); row.setCounterKey(OLD_KEY); row.setReceiptKey(OLD_RECEIPT);
        row.setState(state); row.setStage("RESULT_CLASSIFIED"); row.setGeneration("g1");
        when(mapper.selectByReceipt(OLD_RECEIPT)).thenReturn(row);
        when(mapper.selectById(1L)).thenAnswer(inv -> { row.setFenceVersion(1L); return row; });
        when(mapper.complete(anyLong(), anyString(), anyLong(), anyString(), nullable(String.class))).thenReturn(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoveryCompensatesTheOriginalDayKeyNotToday() {
        recoveryFixture("REFUND_DUE");
        when(mapper.recordOutcome(OLD_RECEIPT, "REFUND_DUE", "SERVICE_FAILURE")).thenReturn(1);
        when(mapper.claim(eq(1L), eq(0L), anyString())).thenReturn(1);
        when(recoveryRedis.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class),
                anyList(), any(), any())).thenReturn(2L);
        recovery.requireRefund(OLD_RECEIPT, "SERVICE_FAILURE");
        ArgumentCaptor<List<Object>> keys = ArgumentCaptor.forClass(List.class);
        verify(recoveryRedis).execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class),
                keys.capture(), any(), any());
        assertEquals(Arrays.<Object>asList(OLD_KEY, OLD_KEY + ":generation", OLD_RECEIPT), keys.getValue());
        verify(mapper).complete(eq(1L), anyString(), eq(1L), eq("REFUNDED"), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateRefundRequestsForOneTraceIdCompensateAtMostOnce() {
        recoveryFixture("REFUND_DUE");
        // 第一次落 REFUND_DUE；重试时台账已不在 PREPARED/RESERVED，recordOutcome 返回 0 但状态一致即视为幂等。
        when(mapper.recordOutcome(OLD_RECEIPT, "REFUND_DUE", "SERVICE_FAILURE")).thenReturn(1).thenReturn(0);
        // fence 只允许一个领取者。
        when(mapper.claim(eq(1L), anyLong(), anyString())).thenReturn(1).thenReturn(0);
        when(recoveryRedis.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class),
                anyList(), any(), any())).thenReturn(2L);
        recovery.requireRefund(OLD_RECEIPT, "SERVICE_FAILURE");
        row.setState("REFUNDED");
        recovery.requireRefund(OLD_RECEIPT, "SERVICE_FAILURE");
        verify(recoveryRedis, times(1)).execute(any(RedisScript.class), any(RedisSerializer.class),
                any(RedisSerializer.class), anyList(), any(), any());
    }

    @Test
    void contentBlockedRetainedReceiptCannotBeRefundedLater() {
        recoveryFixture("RETAINED");
        row.setDecisionReason("INPUT_REJECTED");
        when(mapper.recordOutcome(OLD_RECEIPT, "REFUND_DUE", "SERVICE_FAILURE")).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> recovery.requireRefund(OLD_RECEIPT, "SERVICE_FAILURE"));
        verifyNoInteractions(recoveryRedis);
        verify(mapper, never()).claim(anyLong(), anyLong(), anyString());
    }
}
