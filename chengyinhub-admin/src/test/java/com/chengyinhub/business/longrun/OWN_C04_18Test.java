package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.config.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Depends on C05-21: actual shop orchestration/gateway/router; no real paid provider. */
class OWN_C04_18Test {
    @SuppressWarnings("unchecked")
    @Test void existingShopBizTypeReachesRegisteredProviderWithoutChangingQuotaOrAuditIdentity() {
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenReturn(1L);
        AiQuotaRecoveryService recovery = mock(AiQuotaRecoveryService.class);
        AiModelConfig config = new AiModelConfig();
        ReflectionTestUtils.setField(config, "dailyLimit", 20);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        LlmClient provider = mock(LlmClient.class);
        when(provider.name()).thenReturn("deepseek");
        when(provider.chat(anyString(), eq("question"))).thenReturn("shop answer");
        AiGenerationLogMapper audit = mock(AiGenerationLogMapper.class);
        when(audit.insertAiGenerationLog(any())).thenReturn(1);
        AiGatewayServiceImpl gateway = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(gateway, "redisTemplate", redis);
        ReflectionTestUtils.setField(gateway, "quotaRecovery", recovery);
        ReflectionTestUtils.setField(gateway, "aiModelConfig", config);
        ReflectionTestUtils.setField(gateway, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(gateway, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(gateway, "aiGenerationLogMapper", audit);
        ReflectionTestUtils.setField(gateway, "sceneRouter", new AiSceneRouter());
        ReflectionTestUtils.setField(gateway, "llmClients", Collections.singletonList(provider));
        NpcChatWriteService writes = mock(NpcChatWriteService.class);
        AtomicReference<NpcChatRequest> stored = new AtomicReference<>();
        when(writes.read(anyLong(), anyString())).thenAnswer(inv -> stored.get());
        when(writes.admitShop(any(NpcChatRequest.class), anyString(), eq(41L), eq("question")))
                .thenAnswer(inv -> new NpcChatWriteService.ShopAdmission(inv.getArgument(0), null));
        when(writes.finish(any(NpcChatRequest.class), any(NpcChatLog.class))).thenAnswer(inv -> {
            NpcChatRequest terminal = inv.getArgument(0); stored.set(terminal); return terminal;
        });
        NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
        when(requests.renewLease(anyLong(), anyString(), anyString(), any(Date.class))).thenReturn(1);
        NpcProfileMapper profiles = mock(NpcProfileMapper.class);
        NpcProfile profile = new NpcProfile(); profile.setId(9L); profile.setPersona("approved fixture shop persona");
        when(profiles.selectEnabledByScope(2, 41L)).thenReturn(Collections.singletonList(profile));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        NpcChatService service = new NpcChatService();
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "npcChatRequestMapper", requests);
        ReflectionTestUtils.setField(service, "npcProfileMapper", profiles);
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
        ReflectionTestUtils.setField(service, "scheduledExecutorService", scheduler);
        String id = "00000000-0000-4000-8000-000000000018";
        NpcChatResp result = service.chatWithShop(id, 1001L, 21L, 41L, "approved fixture shop facts", "question");
        assertEquals("SUCCEEDED", result.getOutcomeStatus()); assertEquals("shop answer", result.getSafeText());
        assertEquals(id, result.getRequestId());
        verify(provider, times(1)).chat(anyString(), eq("question"));
        verify(recovery).prepare(eq(1001L), eq("npc_shop_chat"), eq("gen"), anyString(), anyString());
        org.mockito.ArgumentCaptor<AiGenerationLog> entry = org.mockito.ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(audit).insertAiGenerationLog(entry.capture());
        assertEquals("npc_shop_chat", entry.getValue().getBizType());
        verify(recovery, never()).requireRefund(anyString(), anyString());
        assertEquals("shop answer", service.chatWithShop(id, 1001L, 21L, 41L, "same facts", "question").getSafeText());
        verify(provider, times(1)).chat(anyString(), eq("question"));
    }
}
