package com.chengyinhub.business.service.ai;

import java.util.List;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import com.chengyinhub.common.config.AiModelConfig;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Gateway code; unit-only quota/SQL/Redis doubles unless the real-storage IT replaces them. */
public final class AiNotSentGatewayFixture
{
    public final AiGatewayServiceImpl gateway = new AiGatewayServiceImpl();
    public final LlmClient model = mock(LlmClient.class);
    public final AiQuotaRecoveryService recovery = mock(AiQuotaRecoveryService.class);
    public final AiGenerationLogMapper logs = mock(AiGenerationLogMapper.class);
    public final IWxContentSecurityService safety = mock(IWxContentSecurityService.class);
    public final RedisTemplate<Object, Object> redis;

    @SuppressWarnings("unchecked")
    public AiNotSentGatewayFixture(long memberId)
    {
        redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenReturn(1L);
        when(model.name()).thenReturn("deepseek");
        when(logs.insertAiGenerationLog(any())).thenReturn(1);
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember(); member.setId(memberId);
        when(members.selectUmsMemberById(memberId)).thenReturn(member);
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "dailyLimit", 20);
        ReflectionTestUtils.setField(config, "npcDailyLimit", 100);
        ReflectionTestUtils.setField(config, "provider", "deepseek");
        ReflectionTestUtils.setField(gateway, "redisTemplate", redis);
        ReflectionTestUtils.setField(gateway, "quotaRecovery", recovery);
        ReflectionTestUtils.setField(gateway, "wxContentSecurityService", safety);
        ReflectionTestUtils.setField(gateway, "umsMemberService", members);
        ReflectionTestUtils.setField(gateway, "aiGenerationLogMapper", logs);
        ReflectionTestUtils.setField(gateway, "aiModelConfig", config);
        ReflectionTestUtils.setField(gateway, "photoDailyLimit", 30);
        ReflectionTestUtils.setField(gateway, "llmClients", List.of(model));
    }

    public String originalReceipt(long member, String biz, String pool)
    {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(recovery).prepare(eq(member), eq(biz), eq(pool), key.capture(), receipt.capture());
        assertTrue(key.getValue().matches("ai:gen:limit:" + pool + ":" + member + ":[0-9]{8}"));
        assertTrue(receipt.getValue().startsWith("ai:gen:reservation:" + key.getValue() + ":"));
        assertTrue(receipt.getValue().substring(receipt.getValue().length() - 32).matches("[a-f0-9]{32}"));
        verify(recovery).reserved(receipt.getValue(), 1L);
        return receipt.getValue();
    }
}
