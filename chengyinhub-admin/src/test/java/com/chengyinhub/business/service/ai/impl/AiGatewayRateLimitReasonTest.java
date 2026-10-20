package com.chengyinhub.business.service.ai.impl;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.LlmClient;
import com.chengyinhub.common.config.AiModelConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CL-44 续:频控超限必须带 RATE_LIMITED,
 * 否则 NpcChatService 会把 NPC 频控记成 AI_UNAVAILABLE。超限仍是 fail-closed(抛异常、不调模型)。
 */
class AiGatewayRateLimitReasonTest {

    private static final Long MEMBER_ID = 1001L;

    private AiGatewayServiceImpl service;
    private IWxContentSecurityService security;
    private LlmClient llm;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        // 额度走预占脚本（reserve-then-refund），返回值即本次预占后的当日用量。
        when(redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenReturn(101L);
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(1L);
        security = mock(IWxContentSecurityService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        when(members.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        AiGenerationLogMapper logs = mock(AiGenerationLogMapper.class);
        when(logs.insertAiGenerationLog(any())).thenReturn(1);
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "dailyLimit", 20);
        ReflectionTestUtils.setField(config, "npcDailyLimit", 100);
        ReflectionTestUtils.setField(config, "provider", "mock");
        llm = mock(LlmClient.class);
        when(llm.name()).thenReturn("mock");
        when(llm.chat(anyString(), anyString())).thenReturn("NPC 回答");

        service = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(service, "umsMemberService", members);
        ReflectionTestUtils.setField(service, "aiGenerationLogMapper", logs);
        ReflectionTestUtils.setField(service, "aiModelConfig", config);
        ReflectionTestUtils.setField(service, "llmClients", Collections.singletonList(llm));
    }

    @Test
    void npcRateLimitOverrunCarriesRateLimitedReasonAndNeverCallsTheModel() {
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "player", "npc_chat", "[BIZ:npc_chat]\n", "正常提问"));

        assertEquals(AiServiceException.Reason.RATE_LIMITED, error.getReason());
        assertEquals("今日AI次数已用完(100/天),明天再来吧", error.getMessage());
        verify(security, never()).checkText(anyString(), anyString());
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void consumeQuotaOverrunCarriesRateLimitedReason() {
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.consumeQuota(MEMBER_ID, "npc_chat"));

        assertEquals(AiServiceException.Reason.RATE_LIMITED, error.getReason());
        assertEquals("今日AI次数已用完(100/天),明天再来吧", error.getMessage());
    }
}
