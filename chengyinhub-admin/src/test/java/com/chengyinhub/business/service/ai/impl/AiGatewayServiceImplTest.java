package com.chengyinhub.business.service.ai.impl;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiSceneRouter;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.LlmClient;
import com.chengyinhub.common.config.AiModelConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 调用网关实现测试:频控/入参安全/模型调用/出参安全/审计日志。
 */
class AiGatewayServiceImplTest {

    private AiGatewayServiceImpl service;
    private RedisTemplate<Object, Object> redisTemplate;
    private ValueOperations<Object, Object> valueOperations;
    private IWxContentSecurityService wxContentSecurityService;
    private IUmsMemberService umsMemberService;
    private AiGenerationLogMapper aiGenerationLogMapper;
    private AiModelConfig aiModelConfig;
    private LlmClient mockLlm;

    private Long reserveResult = 1L;
    private boolean countByKey;
    private final java.util.Map<String, Long> counters = new java.util.HashMap<>();
    private final java.util.List<java.util.List<Object>> reserved = new java.util.ArrayList<>();
    private final java.util.List<java.util.List<Object>> refunded = new java.util.ArrayList<>();
    private final java.util.List<Integer> reserveTtls = new java.util.ArrayList<>();

    @SuppressWarnings("unchecked")
    private void installReservationStub() {
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            java.util.List<Object> keys = inv.getArgument(1);
            reserved.add(new java.util.ArrayList<>(keys));
            Object ttl = inv.getArgument(2);
            assertTrue(ttl instanceof Integer, "production serializer requires Integer TTL");
            reserveTtls.add((Integer) ttl);
            String key = String.valueOf(keys.get(0));
            if (countByKey) {
                long value = counters.getOrDefault(key, 0L) + 1;
                counters.put(key, value);
                return value;
            }
            return reserveResult;
        });
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            refunded.add(new java.util.ArrayList<>((java.util.List<Object>) inv.getArgument(1)));
            return 1L;
        });
    }

    private static final Long MEMBER_ID = 1001L;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        wxContentSecurityService = mock(IWxContentSecurityService.class);
        umsMemberService = mock(IUmsMemberService.class);
        aiGenerationLogMapper = mock(AiGenerationLogMapper.class);
        aiModelConfig = new AiModelConfig();
        // @Value not processed outside Spring → set fields manually
        ReflectionTestUtils.setField(aiModelConfig, "dailyLimit", 20);
        ReflectionTestUtils.setField(aiModelConfig, "npcDailyLimit", 100);
        ReflectionTestUtils.setField(aiModelConfig, "provider", "deepseek");
        mockLlm = mock(LlmClient.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(mockLlm.name()).thenReturn("deepseek");
        when(mockLlm.chat(anyString(), anyString())).thenReturn("{\"result\":\"ok\"}");

        // 默认: 内容安全放行
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(null);

        // 默认: member 存在,有 openid
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        when(umsMemberService.selectUmsMemberById(MEMBER_ID)).thenReturn(member);

        // 默认: 日志写入成功
        when(aiGenerationLogMapper.insertAiGenerationLog(any())).thenReturn(1);

        installReservationStub();
        // 默认预占回执：这里只验证调用合同，Lua由真实Redis IT覆盖。
        reserveResult = 1L;

        List<LlmClient> clients = Collections.singletonList(mockLlm);

        service = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", wxContentSecurityService);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "aiGenerationLogMapper", aiGenerationLogMapper);
        ReflectionTestUtils.setField(service, "aiModelConfig", aiModelConfig);
        ReflectionTestUtils.setField(service, "llmClients", clients);
    }

    // ---- normal flow ----

    @Test
    void generateNormalFlowReturnsResult() {
        reserveResult = 1L;

        AiGatewayResult result = service.generate(MEMBER_ID, "club", "merchant_plan",
                "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}");

        assertNotNull(result);
        assertEquals("deepseek", result.getModel());
        assertNotNull(result.getText());
        assertNotNull(result.getTraceId());
        assertEquals(0, result.getSafetyFlag());
        assertTrue(result.isSafetyOk());

        // verify all steps ran
        verify(mockLlm).chat(anyString(), anyString());
        verify(wxContentSecurityService, times(2)).checkText(anyString(), anyString()); // input + output safety
        verify(aiGenerationLogMapper).insertAiGenerationLog(any());
    }

    @Test
    void playerThemeDraftSkipsGenericQuotaUntilDraftIsValidated() {
        AiGatewayResult result = service.generate(MEMBER_ID, "player", "theme_draft",
                "[BIZ:theme_draft]\n", "{\"idea\":\"night walk\"}");

        assertNotNull(result);
        assertEquals(0, reserved.size());
    }

    // ---- input safety block ----

    @Test
    void generateInputSafetyBlockThrowsException() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn("包含违规内容");

        AiServiceException ex = assertThrows(AiServiceException.class, () -> {
            service.generate(MEMBER_ID, "club", "merchant_plan",
                    "[BIZ:merchant_plan]\n", "bad content");
        });

        assertEquals("包含违规内容", ex.getMessage());

        // audit log is written even on failure
        verify(aiGenerationLogMapper).insertAiGenerationLog(any());
        // model not called
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    // ---- output safety block ----

    @Test
    void generateOutputSafetyBlockThrowsException() {
        // input safety passes
        when(wxContentSecurityService.checkText(anyString(), anyString()))
                .thenReturn(null)   // first call: input
                .thenReturn("生成内容违规");  // second call: output

        AiServiceException ex = assertThrows(AiServiceException.class, () -> {
            service.generate(MEMBER_ID, "club", "merchant_plan",
                    "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}");
        });

        assertEquals("生成内容未通过安全检测,请调整后重试", ex.getMessage());

        // model was called
        verify(mockLlm).chat(anyString(), anyString());
        // audit log written with SAFETY_OUTPUT_BLOCKED
        verify(aiGenerationLogMapper).insertAiGenerationLog(any());
    }

    // ---- rate limit ----

    @Test
    void generateRateLimitExceededThrowsException() {
        reserveResult = 25L; // dailyLimit is 20

        AiServiceException ex = assertThrows(AiServiceException.class, () -> {
            service.generate(MEMBER_ID, "club", "merchant_plan",
                    "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}");
        });

        assertTrue(ex.getMessage().contains("AI次数已用完"));
        // model not called
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    // ---- audit log always written ----

    @Test
    void generateModelErrorStillWritesAuditLog() {
        when(mockLlm.chat(anyString(), anyString())).thenThrow(new RuntimeException("model down"));

        assertThrows(AiServiceException.class, () -> {
            service.generate(MEMBER_ID, "club", "merchant_plan",
                    "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}");
        });

        // audit log must be written even on model error
        verify(aiGenerationLogMapper).insertAiGenerationLog(any());
    }

    @Test
    void missingApiKeyReturnsDiagnosableConfigurationFailure() {
        LlmClient unavailable = new com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient(
                "deepseek", "https://unused.invalid", "", "test", true, 1);
        ReflectionTestUtils.setField(service, "llmClients", Collections.singletonList(unavailable));

        AiServiceException error = assertThrows(AiServiceException.class, () ->
            service.generate(MEMBER_ID, "club", "merchant_plan",
                "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}"));

        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason(),
            "配置缺失必须由上游可用性闸指认，不能被普通模型繁忙兜底吞掉");
        assertTrue(error.getMessage().contains("配置缺失"));
        assertTrue(error.getMessage().contains("traceId="), "外部报错必须带可查日志的追踪号");
        assertEquals(reserved, refunded, "local readiness failed before any provider request");
    }

    // 输入审核服务故障退本次预占；模型执行结果未知不退款，明确违规不退。
    // REVIEW_REQUIRED 与输出审核沿原规则，原子补偿另由真实 Redis IT 验证。

    @Test
    void modelErrorKeepsOriginalReservationForUnknownOutcome() {
        when(mockLlm.chat(anyString(), anyString())).thenThrow(new RuntimeException("model down"));

        assertThrows(AiServiceException.class, () -> service.generate(
                MEMBER_ID, "club", "merchant_plan", "[BIZ:merchant_plan]\n", "{\"idea\":\"x\"}"));

        assertEquals(1, reserved.size());
        assertEquals(0, refunded.size(), "provider may already have executed the request");
    }

    @Test
    void noModelAvailable_refundsQuota_configErrorIsOurFault() {
        // 无可用 client:selectClient/resolveClient 抛「无可用的 AI 模型」——纯配置错
        ReflectionTestUtils.setField(service, "llmClients", Collections.<LlmClient>emptyList());
        ReflectionTestUtils.setField(service, "llmClientsByBeanName", null);

        assertThrows(AiServiceException.class, () -> service.generate(
                MEMBER_ID, "club", "merchant_plan", "[BIZ:merchant_plan]\n", "{\"idea\":\"x\"}"));

        assertEquals(1, refunded.size());
    }

    @Test
    void unknownInputRejectionKeepsExistingNonRefundPolicy() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn("含违规内容");

        assertThrows(AiServiceException.class, () -> service.generate(
                MEMBER_ID, "club", "merchant_plan", "[BIZ:merchant_plan]\n", "{\"idea\":\"x\"}"));

        assertEquals(0, refunded.size());
    }

    @Test
    void normalFlow_doesNotRefund() {
        service.generate(MEMBER_ID, "club", "merchant_plan", "[BIZ:merchant_plan]\n", "{\"idea\":\"x\"}");
        assertEquals(0, refunded.size());
    }

    @Test
    void rateLimitExceeded_doesNotRefund_alreadyOverAnyway() {
        reserveResult = 25L; // dailyLimit=20

        assertThrows(AiServiceException.class, () -> service.generate(
                MEMBER_ID, "club", "merchant_plan", "[BIZ:merchant_plan]\n", "{\"idea\":\"x\"}"));

        // 已经超了,退不退都拦;退反而会让「攻击者狂刷」看起来像没刷过。不退。
        assertEquals(0, refunded.size());
    }

    @Test
    void refundRequestsAtomicCompensationWithoutStandaloneDelete() {
        // Missing key is rejected locally before any provider request starts.
        LlmClient unavailable = org.mockito.Mockito.spy(
                new com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient(
                        "deepseek", "https://unused.invalid", "", "test", true, 1));
        ReflectionTestUtils.setField(service, "llmClients", Collections.singletonList(unavailable));
        assertThrows(AiServiceException.class, () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(1, refunded.size());
        assertEquals(reserved.get(0), refunded.get(0));
        verify(redisTemplate, never()).delete(anyString());
        verify(valueOperations, never()).decrement(anyString());
        verify(unavailable, never()).chat(anyString(), anyString());
    }

    /** 正常退款(余额 >= 0)不该删 key —— 删了会把当天已用次数清零 = 白送整池额度。 */
    @Test
    void refund_normal_doesNotDeleteKey() {
        when(mockLlm.chat(anyString(), anyString())).thenThrow(new RuntimeException("model down"));


        assertThrows(AiServiceException.class, () -> service.generate(
                MEMBER_ID, "club", "merchant_plan", "[BIZ:merchant_plan]\n", "{\"idea\":\"x\"}"));

        verify(redisTemplate, never()).delete(anyString());
    }

    /** 退款要退到和扣的同一个 key —— 退错池子等于没退,还会把别人的额度还错。 */
    @Test
    void refundUsesSameOriginalDayGenerationAndNonceKeys() {
        // Missing key is rejected locally before any provider request starts.
        LlmClient unavailable = org.mockito.Mockito.spy(
                new com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient(
                        "deepseek", "https://unused.invalid", "", "test", true, 1));
        ReflectionTestUtils.setField(service, "llmClients", Collections.singletonList(unavailable));
        assertThrows(AiServiceException.class, () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(1, reserved.size());
        assertEquals(reserved, refunded);
        verify(unavailable, never()).chat(anyString(), anyString());
    }

    // ---- trace ID ----

    @Test
    void generateReturnsUniqueTraceId() {
        AiGatewayResult r1 = service.generate(MEMBER_ID, "club", "merchant_plan",
                "[BIZ:merchant_plan]\n", "{\"idea\":\"a\"}");
        AiGatewayResult r2 = service.generate(MEMBER_ID, "club", "theme_draft",
                "[BIZ:theme_draft]\n", "{\"idea\":\"b\"}");

        assertNotNull(r1.getTraceId());
        assertNotNull(r2.getTraceId());
        // trace IDs should be different
        assertTrue(!r1.getTraceId().equals(r2.getTraceId()));
    }

    // ---- TTL set on first increment ----

    // ---- traceId 链路一致性:落库的 trace_id 必须 == 返给前端的 traceId ----
    // ⚠️ 上面 generateReturnsUniqueTraceId 只看「两次调用不重复」,断链时照样绿(假绿)。
    //    真正抓 bug 的是 captor:必须比对**落库那一行**与**返回值**。

    @Test
    void normalFlowLogsSameTraceIdAsReturned() {
        AiGatewayResult result = service.generate(MEMBER_ID, "club", "merchant_plan",
                "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}");

        ArgumentCaptor<AiGenerationLog> captor = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(aiGenerationLogMapper).insertAiGenerationLog(captor.capture());
        AiGenerationLog logged = captor.getValue();

        assertNotNull(logged.getTraceId());
        // 断链时这里红:落库是另起的 UUID
        assertEquals(result.getTraceId(), logged.getTraceId());
        // token_in/token_out 必须真被 set(它们要落库,见 migration_ai_log_token_trace_20260716.sql)
        assertNotNull(logged.getTokenIn());
        assertNotNull(logged.getTokenOut());
        assertTrue(logged.getTokenIn() > 0);
        assertTrue(logged.getTokenOut() > 0);
        assertEquals(logged.getTokenIn() + logged.getTokenOut(), logged.getTokenEst().intValue());
    }

    @Test
    void inputBlockedLogsTraceIdGeneratedBeforeRateLimit() {
        // input-blocked 分支在 traceId 原先的生成点**之前**执行 → 提到方法开头才有值
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn("包含违规内容");

        assertThrows(AiServiceException.class, () -> service.generate(MEMBER_ID, "club", "merchant_plan",
                "[BIZ:merchant_plan]\n", "bad content"));

        ArgumentCaptor<AiGenerationLog> captor = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(aiGenerationLogMapper).insertAiGenerationLog(captor.capture());
        AiGenerationLog logged = captor.getValue();

        assertNotNull(logged.getTraceId());
        assertTrue(logged.getTraceId().length() > 0);
        assertEquals(AiGenerationLog.SAFETY_INPUT_BLOCKED, logged.getSafetyFlag().intValue());
    }

    @Test
    void outputBlockedLogsNonNullTraceIdAndTokens() {
        when(wxContentSecurityService.checkText(anyString(), anyString()))
                .thenReturn(null)              // input
                .thenReturn("生成内容违规");     // output

        assertThrows(AiServiceException.class, () -> service.generate(MEMBER_ID, "club", "merchant_plan",
                "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}"));

        ArgumentCaptor<AiGenerationLog> captor = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(aiGenerationLogMapper).insertAiGenerationLog(captor.capture());
        AiGenerationLog logged = captor.getValue();

        // 拿不到 result(抛异常),只能断言落库侧非空 + token 已 set
        assertNotNull(logged.getTraceId());
        assertTrue(logged.getTraceId().length() > 0);
        assertNotNull(logged.getTokenIn());
        assertNotNull(logged.getTokenOut());
        assertTrue(logged.getTokenIn() > 0);
        assertTrue(logged.getTokenOut() > 0);
        assertEquals(AiGenerationLog.SAFETY_OUTPUT_BLOCKED, logged.getSafetyFlag().intValue());
    }

    @Test
    void cacheOnlyLogsSameTraceIdAsReturned() {
        // cache-only 分支只有注入 sceneRouter 且 isCacheOnly=true 才可达
        // (默认 setUp 不注入 → selectClient 走 resolveClient(provider="deepseek") → 拿到 mockLlm)
        AiSceneRouter sceneRouter = mock(AiSceneRouter.class);
        when(sceneRouter.resolve(anyString())).thenReturn("cache");
        when(sceneRouter.isCacheOnly(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "sceneRouter", sceneRouter);

        AiGatewayResult result = service.generate(MEMBER_ID, "club", "npc_event",
                "[BIZ:npc_event]\n", "{\"idea\":\"test\"}");

        assertEquals("cache", result.getModel());

        ArgumentCaptor<AiGenerationLog> captor = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(aiGenerationLogMapper).insertAiGenerationLog(captor.capture());

        // 断链时这里红:saveLog 一个 UUID、返回值另一个 UUID(原 :108/:109 两处各起一个)
        assertEquals(result.getTraceId(), captor.getValue().getTraceId());
        // 模型没被调
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    // 按 key 维护 mock 计数，仅验证调用分池；不是 Redis 脚本或并发验收。
    private java.util.Map<String, Long> installPerKeyCounterStub() {
        countByKey = true;
        return counters;
    }

    @Test
    void dailyQuotaKeyUsesBeijingDateEvenWhenJvmDefaultZoneDiffers() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            // UTC-12 与北京差 20 小时:一天里大部分时刻两地日期不同,旧实现按默认时区取日会换错键。
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Etc/GMT+12"));
            String beijingDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            service.remainingQuota(MEMBER_ID, "theme_draft");
            ArgumentCaptor<Object> key = ArgumentCaptor.forClass(Object.class);
            verify(valueOperations).get(key.capture());
            assertEquals("ai:gen:limit:gen:" + MEMBER_ID + ":" + beijingDay, key.getValue());
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void npcChatAndCreationUseDifferentRateKeys() {
        java.util.Map<String, Long> counters = installPerKeyCounterStub();

        service.generate(MEMBER_ID, "player", "npc_chat", "s", "u");
        service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u");

        // 两把 key,各记 1 次 —— 同一把 key 记 2 次就是没分池
        assertEquals(2, counters.size(), "npc_chat 与 merchant_plan 必须落在两把不同的 rate key 上");
        for (String key : counters.keySet()) {
            assertEquals(1L, counters.get(key).longValue());
        }
        assertTrue(counters.keySet().stream().anyMatch(k -> k.startsWith("ai:gen:limit:npc:" + MEMBER_ID + ":")),
                "npc 池 key 应为 ai:gen:limit:npc:{memberId}:{yyyyMMdd},实际=" + counters.keySet());
        assertTrue(counters.keySet().stream().anyMatch(k -> k.startsWith("ai:gen:limit:gen:" + MEMBER_ID + ":")),
                "创作池 key 应为 ai:gen:limit:gen:{memberId}:{yyyyMMdd},实际=" + counters.keySet());
    }

    @Test
    void creationBizTypesShareOneRateKey() {
        // 分组的意义:创作类 7 个 bizType **共用一池**。
        // 若按 bizType 各自一池,单用户实际额度会被悄悄放大 7 倍。
        java.util.Map<String, Long> counters = installPerKeyCounterStub();

        service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u");
        service.generate(MEMBER_ID, "club", "theme_draft", "s", "u");
        service.generate(MEMBER_ID, "club", "node_generate", "s", "u");

        assertEquals(1, counters.size(), "创作类 bizType 必须共用同一把 rate key");
        assertEquals(3L, counters.values().iterator().next().longValue());
    }

    @Test
    void npcUsageDoesNotConsumeCreationQuota() {
        ReflectionTestUtils.setField(aiModelConfig, "npcDailyLimit", 100);
        ReflectionTestUtils.setField(aiModelConfig, "dailyLimit", 20);
        installPerKeyCounterStub();

        // NPC 用掉 30 次(远超创作类的 20/天上限)
        for (int i = 0; i < 30; i++) {
            service.generate(MEMBER_ID, "player", "npc_chat", "s", "u");
        }

        // 创作额度必须分毫未损:第 1 次创作仍应成功
        AiGatewayResult r = service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u");
        assertNotNull(r);
        assertEquals(0, r.getSafetyFlag());
    }

    @Test
    void creationExhaustedDoesNotBlockNpc() {
        ReflectionTestUtils.setField(aiModelConfig, "npcDailyLimit", 100);
        ReflectionTestUtils.setField(aiModelConfig, "dailyLimit", 20);
        installPerKeyCounterStub();

        // 创作类用满 20 次
        for (int i = 0; i < 20; i++) {
            service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u");
        }
        // 第 21 次创作必须被拦
        assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));

        // 但 NPC 完全不受影响
        AiGatewayResult r = service.generate(MEMBER_ID, "player", "npc_chat", "s", "u");
        assertNotNull(r);
        assertEquals(0, r.getSafetyFlag());
    }

    @Test
    void npcRateLimitUsesNpcDailyLimitAndKeepsFrontendMessagePrefix() {
        ReflectionTestUtils.setField(aiModelConfig, "npcDailyLimit", 100);
        ReflectionTestUtils.setField(aiModelConfig, "dailyLimit", 20);
        // 第 101 次 NPC 调用 → 超 npcDailyLimit(100)
        reserveResult = 101L;

        AiServiceException ex = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "player", "npc_chat", "s", "u"));

        // 额度数字必须是 npcDailyLimit(100),不是 dailyLimit(20)
        assertTrue(ex.getMessage().contains("100/天"), "NPC 超限文案应带 npcDailyLimit=100,实际=" + ex.getMessage());
        // ⚠️ 前端用 indexOf('今日AI次数已用完') 做包含匹配识别频控,前缀改了会静默失效
        assertTrue(ex.getMessage().startsWith("今日AI次数已用完"), "频控文案前缀被改会让前端识别静默失效");
    }

    @Test
    void npcUnderLimitPasses() {
        ReflectionTestUtils.setField(aiModelConfig, "npcDailyLimit", 100);
        // 第 21 次 NPC 调用:超了创作类的 20,但没超 NPC 的 100 → 必须放行
        reserveResult = 21L;

        AiGatewayResult r = service.generate(MEMBER_ID, "player", "npc_chat", "s", "u");
        assertNotNull(r);
        assertEquals(0, r.getSafetyFlag());
    }

    @Test
    void generatePassesOriginalTtlAsIntegerToAtomicReservation() {
        reserveResult = 1L;

        service.generate(MEMBER_ID, "club", "merchant_plan",
                "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}");

        assertEquals(Integer.valueOf(86400), reserveTtls.get(0));
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void generateDoesNotRenewTtlOutsideAtomicReservation() {
        reserveResult = 5L;

        service.generate(MEMBER_ID, "club", "merchant_plan",
                "[BIZ:merchant_plan]\n", "{\"idea\":\"test\"}");

        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void detectionServiceFailureRefundsWithoutCallingProvider() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.MSG_BUSY);
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "theme_draft", "s", "u"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
        assertEquals(reserved, refunded);
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    @Test
    void explicitRiskyInputKeepsQuotaAndTypedBlockedReason() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn("内容包含违规信息，请修改后重试");
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "theme_draft", "s", "u"));
        assertEquals(AiServiceException.Reason.INPUT_BLOCKED, error.getReason());
        assertEquals(0, refunded.size());
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    @Test
    void reviewRequiredIsInputBlockedAndDoesNotRefundOrdinaryPool() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "theme_draft", "s", "u"));
        // master #322：微信审核拒绝（含人工复核）一律 INPUT_BLOCKED。
        assertEquals(AiServiceException.Reason.INPUT_BLOCKED, error.getReason());
        assertEquals(0, refunded.size());
    }

    @Test
    void unknownReservationReplyCancelsOnlyItsOwnNonceBeforeProvider() {
        reserveResult = null;
        assertThrows(IllegalStateException.class,
                () -> service.generate(MEMBER_ID, "club", "theme_draft", "s", "u"));
        assertEquals(1, reserved.size());
        assertEquals(reserved, refunded);
        verify(mockLlm, never()).chat(anyString(), anyString());
    }
    // A02: these test doubles never contact a paid provider.
    private void assertProviderUnavailable() {
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
        assertEquals(reserved, refunded);
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    @Test
    void legacyUnknownProviderDoesNotUseAvailableMock() {
        ReflectionTestUtils.setField(aiModelConfig, "provider", "missing");
        when(mockLlm.name()).thenReturn("mock");
        assertProviderUnavailable();
    }

    @Test
    void sceneProviderMissingDoesNotUseAvailableMock() {
        ReflectionTestUtils.setField(service, "sceneRouter", new AiSceneRouter());
        when(mockLlm.name()).thenReturn("mock");
        assertProviderUnavailable();
    }

    @Test
    void explicitLegacyMockConfigurationCannotSucceed() {
        ReflectionTestUtils.setField(aiModelConfig, "provider", "mock");
        when(mockLlm.name()).thenReturn("mock");
        assertProviderUnavailable();
    }

    @Test
    void sceneBeanAliasCannotReturnMock() {
        ReflectionTestUtils.setField(service, "sceneRouter", new AiSceneRouter());
        ReflectionTestUtils.setField(service, "llmClientsByBeanName",
                Collections.singletonMap("deepseekLlmClient", mockLlm));
        when(mockLlm.name()).thenReturn("mock");
        assertProviderUnavailable();
    }

    @Test
    void unknownSceneRefundsAndDoesNotCallMock() {
        ReflectionTestUtils.setField(service, "sceneRouter", new AiSceneRouter());
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "unregistered_scene", "s", "u"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
        assertEquals(reserved, refunded);
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    @Test
    void configuredRealSceneBeanIsUsed() {
        ReflectionTestUtils.setField(service, "sceneRouter", new AiSceneRouter());
        ReflectionTestUtils.setField(service, "llmClientsByBeanName",
                Collections.singletonMap("deepseekLlmClient", mockLlm));
        assertEquals("deepseek", service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u").getModel());
        verify(mockLlm).chat("s", "u");
    }

    @Test
    void bothSelectionPathsRejectUnconfiguredRealClientWithoutNetwork() {
        // requireReady rejects before HTTP: both scene and legacy provider selection are covered.
        for (boolean routed : new boolean[] {false, true}) {
            for (boolean enabled : new boolean[] {false, true}) {
                ReflectionTestUtils.setField(service, "clientMap", null);
                ReflectionTestUtils.setField(service, "sceneRouter", routed ? new AiSceneRouter() : null);
                LlmClient unavailable = new com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient(
                        "deepseek", "https://unused.invalid", "", "test", enabled, 1);
                ReflectionTestUtils.setField(service, "llmClients", Collections.singletonList(unavailable));
                AiServiceException error = assertThrows(AiServiceException.class,
                        () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
                assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
                assertTrue(error.getMessage().contains("配置缺失"));
            }
        }
        assertEquals(4, reserved.size());
        assertEquals(reserved, refunded);
    }

    private String repeatText(char value, int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private String uniqueText(int first, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) text.append((char) (first + i));
        return text.toString();
    }

    @Test
    void unsafeUserSuffixBeyondTwoThousandNeverReachesProvider() {
        String prompt = repeatText('a', 2100) + "UNSAFE_SUFFIX";
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).contains("UNSAFE_SUFFIX")
                        ? "内容包含违规信息，请修改后重试" : null);
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", prompt));
        assertEquals(AiServiceException.Reason.INPUT_BLOCKED, error.getReason());
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    @Test
    void assembledSystemContextIsReviewedBeforeProvider() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).contains("UNSAFE_CONTEXT")
                        ? "内容包含违规信息，请修改后重试" : null);
        assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "UNSAFE_CONTEXT", "safe"));
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    @Test
    void longSafeInputPreservesEveryCharacterAndOriginalProviderArguments() {
        String system = uniqueText(0x4e00, 2100);
        String user = uniqueText(0x6000, 1999) + "\uD83D\uDE00" + uniqueText(0x7000, 2001);
        service.generate(MEMBER_ID, "club", "merchant_plan", system, user);
        verify(mockLlm).chat(system, user);
        ArgumentCaptor<String> parts = ArgumentCaptor.forClass(String.class);
        verify(wxContentSecurityService, org.mockito.Mockito.atLeastOnce()).checkText(parts.capture(), anyString());
        java.util.List<String> reviewed = parts.getAllValues();
        String assembled = system + "\n" + user;
        int previousEnd = 0;
        // Last captured part is the output; all preceding parts cover input in order with overlap.
        for (int i = 0; i < reviewed.size() - 1; i++) {
            String part = reviewed.get(i);
            assertTrue(part.length() <= 2000);
            assertTrue(!Character.isLowSurrogate(part.charAt(0)));
            assertTrue(!Character.isHighSurrogate(part.charAt(part.length() - 1)));
            int start = assembled.indexOf(part, Math.max(0, previousEnd - 101));
            assertTrue(start >= 0 && start <= previousEnd, "review chunks cannot leave a gap");
            previousEnd = start + part.length();
        }
        assertEquals(assembled.length(), previousEnd);
    }

    @Test
    void longOutputSuffixIsBlockedBeforeReturningResult() {
        String output = repeatText('o', 2100) + "UNSAFE_OUTPUT";
        when(mockLlm.chat(anyString(), anyString())).thenReturn(output);
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).contains("UNSAFE_OUTPUT") ? "生成内容违规" : null);
        assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(0, refunded.size(), "model ran; output rejection keeps existing quota policy");
    }

    @Test
    void outputReviewOutageIsUnavailableAndKeepsUsedQuota() {
        when(wxContentSecurityService.checkText(anyString(), anyString()))
                .thenReturn(null).thenReturn(IWxContentSecurityService.MSG_BUSY);
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
        assertEquals(0, refunded.size());
    }

    @Test
    void outputReviewExceptionIsUnavailableAndKeepsUsedQuota() {
        when(wxContentSecurityService.checkText(anyString(), anyString()))
                .thenReturn(null).thenThrow(new IllegalStateException("review unavailable"));
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
        assertEquals(0, refunded.size());
    }

    @Test
    void emptyProviderReplyCannotBecomeSuccessfulResult() {
        when(mockLlm.chat(anyString(), anyString())).thenReturn(null);
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(AiServiceException.Reason.UNKNOWN, error.getReason());
        assertEquals(0, refunded.size());
    }

    @Test
    void laterInputReviewFailureRefundsBeforeAnyProviderCall() {
        when(wxContentSecurityService.checkText(anyString(), anyString()))
                .thenReturn(null).thenThrow(new IllegalStateException("review unavailable"));
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", repeatText('u', 4000)));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
        assertEquals(reserved, refunded);
        verify(mockLlm, never()).chat(anyString(), anyString());
    }

    @Test
    void providerTimeoutFailsWithoutSuccessfulResult() {
        when(mockLlm.chat(anyString(), anyString())).thenThrow(new RuntimeException("request timed out"));
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(AiServiceException.Reason.UNKNOWN, error.getReason());
        assertTrue(error instanceof com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException);
        assertEquals(0, refunded.size());
        verify(mockLlm, times(1)).chat("s", "u");
    }

    @Test
    void modelUnknownPreservesReceiptAndDoesNotClassifyConfigurationByMessage() {
        com.chengyinhub.business.service.ai.AiQuotaRecoveryService recovery =
                mock(com.chengyinhub.business.service.ai.AiQuotaRecoveryService.class);
        ReflectionTestUtils.setField(service, "quotaRecovery", recovery);
        when(mockLlm.chat(anyString(), anyString()))
                .thenThrow(new IllegalStateException("upstream echoed 未配置 apiKey"));
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "club", "merchant_plan", "s", "u"));
        assertEquals(AiServiceException.Reason.UNKNOWN, error.getReason());
        assertTrue(error.getMessage().contains("traceId="));
        String receipt = String.valueOf(reserved.get(0).get(2));
        verify(recovery).stage(receipt, "MODEL_STARTED");
        verify(recovery, never()).requireRefund(anyString(), anyString());
        verify(recovery, never()).retain(anyString(), anyString());
        ArgumentCaptor<AiGenerationLog> logged = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(aiGenerationLogMapper).insertAiGenerationLog(logged.capture());
        org.junit.jupiter.api.Assertions.assertNull(logged.getValue().getTokenOut());
        org.junit.jupiter.api.Assertions.assertNull(logged.getValue().getTokenEst());
        assertEquals(Integer.valueOf(2), logged.getValue().getTokenIn());
        verify(mockLlm, times(1)).chat("s", "u");
    }

    @Test
    void reviewChunksNeverSplitSurrogatePairAtLimitOrOverlap() {
        String user = repeatText('a', 1897) + "\uD83D\uDE00" + repeatText('b', 98)
                + "\uD83D\uDE00" + repeatText('c', 2000);
        service.generate(MEMBER_ID, "club", "merchant_plan", "s", user);
        ArgumentCaptor<String> parts = ArgumentCaptor.forClass(String.class);
        verify(wxContentSecurityService, org.mockito.Mockito.atLeastOnce()).checkText(parts.capture(), anyString());
        for (String part : parts.getAllValues()) {
            assertTrue(part.length() <= 2000);
            assertTrue(!Character.isLowSurrogate(part.charAt(0)));
            assertTrue(!Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
        verify(mockLlm).chat("s", user);
    }


    @Test void npcAsrHandoffUsesOriginalGenReservationWithoutASecondReserve() {
        var recovery=mock(com.chengyinhub.business.service.ai.AiQuotaRecoveryService.class);
        ReflectionTestUtils.setField(service,"quotaRecovery",recovery);
        var original=new com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff(MEMBER_ID,
                "e2596106-82d9-4a4f-9ff2-058414599f90",21L,42L,
                "ai:gen:limit:gen:"+MEMBER_ID+":20261013|01234567890123456789012345678901");
        AiGatewayResult result=service.generateNpcAsrHandoff(original,"你是店主","原转写");
        assertNotNull(result);assertEquals("01234567890123456789012345678901",result.getTraceId());
        assertTrue(reserved.isEmpty());assertTrue(refunded.isEmpty());verify(recovery).requireNpcAsrHandoff(original);
        verify(mockLlm).chat("你是店主","原转写");
        org.mockito.Mockito.verifyNoMoreInteractions(recovery);
    }
    @Test void npcTextNotSentCannotRefundTheAsrAlreadyAttemptedReservation() {
        var recovery=mock(com.chengyinhub.business.service.ai.AiQuotaRecoveryService.class);
        ReflectionTestUtils.setField(service,"quotaRecovery",recovery);
        when(mockLlm.chat(anyString(),anyString())).thenThrow(new com.chengyinhub.business.service.ai.AiProviderNotSentException());
        var original=new com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff(MEMBER_ID,
                "e2596106-82d9-4a4f-9ff2-058414599f90",21L,42L,
                "ai:gen:limit:gen:"+MEMBER_ID+":20261013|01234567890123456789012345678901");
        assertThrows(com.chengyinhub.business.service.ai.AiServiceException.class,
                ()->service.generateNpcAsrHandoff(original,"你是店主","原转写"));
        assertTrue(reserved.isEmpty());assertTrue(refunded.isEmpty());
        verify(recovery).requireNpcAsrHandoff(original);org.mockito.Mockito.verifyNoMoreInteractions(recovery);
    }
    @Test void unprovenNpcAsrReservationStopsBeforeReviewOrModel() {
        var recovery=mock(com.chengyinhub.business.service.ai.AiQuotaRecoveryService.class);
        ReflectionTestUtils.setField(service,"quotaRecovery",recovery);
        org.mockito.Mockito.doThrow(new IllegalStateException("controlled original mismatch"))
                .when(recovery).requireNpcAsrHandoff(org.mockito.ArgumentMatchers.any());
        var original=new com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff(MEMBER_ID,
                "e2596106-82d9-4a4f-9ff2-058414599f90",21L,42L,"untrusted");
        assertThrows(IllegalStateException.class,()->service.generateNpcAsrHandoff(original,"sys","text"));
        org.mockito.Mockito.verifyNoInteractions(mockLlm,wxContentSecurityService);assertTrue(reserved.isEmpty());
    }

}
