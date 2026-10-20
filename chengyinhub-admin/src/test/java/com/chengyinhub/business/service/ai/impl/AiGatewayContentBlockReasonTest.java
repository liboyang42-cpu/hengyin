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
 * CL-44(F-L5C-16)修复:内容安全拦截必须带 INPUT_BLOCKED / OUTPUT_BLOCKED,
 * 否则 NpcChatService 会把违规记成 AI_UNAVAILABLE 并标为可重试。拦截仍是 fail-closed(抛异常、不返回内容)。
 */
class AiGatewayContentBlockReasonTest {

    private static final Long MEMBER_ID = 1001L;

    private AiGatewayServiceImpl service;
    private IWxContentSecurityService security;
    private LlmClient llm;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        // 额度走预占脚本（reserve-then-refund），返回值即本次预占后的当日用量。
        when(redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenReturn(1L);
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
    void inputContentBlockCarriesInputBlockedReasonAndNeverCallsTheModel() {
        when(security.checkText(anyString(), anyString())).thenReturn("包含违规内容");

        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "player", "npc_chat", "[BIZ:npc_chat]\n", "违规提问"));

        assertEquals(AiServiceException.Reason.INPUT_BLOCKED, error.getReason());
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void outputContentBlockCarriesOutputBlockedReason() {
        when(security.checkText(anyString(), anyString()))
                .thenReturn(null)
                .thenReturn("生成内容违规");

        AiServiceException error = assertThrows(AiServiceException.class,
                () -> service.generate(MEMBER_ID, "player", "npc_chat", "[BIZ:npc_chat]\n", "正常提问"));

        assertEquals(AiServiceException.Reason.OUTPUT_BLOCKED, error.getReason());
        assertEquals("生成内容未通过安全检测,请调整后重试", error.getMessage());
    }
}
