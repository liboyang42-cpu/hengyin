package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
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
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real gateway output-review exceptions consumed by real NPC orchestration; all IO is doubled. */
class OWN_C04_15Test {
    @Test void outputReviewPhaseNeverClaimsNotRunEvenWhenReceiptStorageAlsoFails() {
        for (int mode = 0; mode < 3; mode++) for (boolean storageFailure : new boolean[] {false, true})
            scenario(mode, storageFailure);
    }
    @SuppressWarnings("unchecked")
    private void scenario(int mode, boolean storageFailure) {
        String id = "00000000-0000-4000-8000-000000000015";
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenReturn(1L);
        AiQuotaRecoveryService recovery = mock(AiQuotaRecoveryService.class);
        AiModelConfig config = new AiModelConfig(); ReflectionTestUtils.setField(config, "npcDailyLimit", 100);
        IWxContentSecurityService review = mock(IWxContentSecurityService.class);
        if (mode == 0) when(review.checkText(anyString(), anyString())).thenReturn(null)
                .thenThrow(new IllegalStateException("fixture review unavailable"));
        else when(review.checkText(anyString(), anyString())).thenReturn(null,
                mode == 1 ? IWxContentSecurityService.MSG_BUSY : "fixture output denied");
        LlmClient provider = mock(LlmClient.class); when(provider.name()).thenReturn("deepseek");
        when(provider.chat(anyString(), anyString())).thenReturn("WITHHELD_PROVIDER_TEXT");
        AiGenerationLogMapper audit = mock(AiGenerationLogMapper.class);
        when(audit.insertAiGenerationLog(any())).thenReturn(1);
        AiGatewayServiceImpl gateway = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(gateway, "redisTemplate", redis);
        ReflectionTestUtils.setField(gateway, "quotaRecovery", recovery);
        ReflectionTestUtils.setField(gateway, "aiModelConfig", config);
        ReflectionTestUtils.setField(gateway, "wxContentSecurityService", review);
        ReflectionTestUtils.setField(gateway, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(gateway, "aiGenerationLogMapper", audit);
        ReflectionTestUtils.setField(gateway, "sceneRouter", new AiSceneRouter());
        ReflectionTestUtils.setField(gateway, "llmClients", Collections.singletonList(provider));
        NpcChatWriteService writes = mock(NpcChatWriteService.class);
        AtomicReference<NpcChatRequest> stored = new AtomicReference<>();
        AtomicBoolean admitted = new AtomicBoolean();
        when(writes.read(anyLong(), anyString())).thenAnswer(inv -> {
            if (admitted.get() && storageFailure) throw new IllegalStateException("fixture storage unavailable");
            return stored.get();
        });
        when(writes.admit(any(NpcChatRequest.class), anyString())).thenAnswer(inv -> {
            admitted.set(true); return inv.getArgument(0);
        });
        when(writes.finish(any(NpcChatRequest.class), any(NpcChatLog.class))).thenAnswer(inv -> {
            if (storageFailure) throw new IllegalStateException("fixture finish unavailable");
            NpcChatRequest terminal = inv.getArgument(0); stored.set(terminal); return terminal;
        });
        NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
        when(requests.renewLease(anyLong(), anyString(), anyString(), any(Date.class))).thenReturn(1);
        RoamMapper roam = mock(RoamMapper.class);
        RoamSession session = new RoamSession(); session.setId(99L); session.setMemberId(1001L); session.setStatus(0);
        when(roam.selectSessionById(99L)).thenReturn(session);
        NpcProfileMapper profiles = mock(NpcProfileMapper.class);
        NpcProfile profile = new NpcProfile(); profile.setId(1L); profile.setPersona("fixture persona");
        when(profiles.selectGlobalNpc()).thenReturn(profile);
        NpcMemoryBuilder memory = mock(NpcMemoryBuilder.class); when(memory.buildRoam(any())).thenReturn("progress");
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        NpcChatService service = new NpcChatService();
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "npcChatRequestMapper", requests);
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
        ReflectionTestUtils.setField(service, "roamMapper", roam);
        ReflectionTestUtils.setField(service, "npcProfileMapper", profiles);
        ReflectionTestUtils.setField(service, "memoryBuilder", memory);
        ReflectionTestUtils.setField(service, "scheduledExecutorService", scheduler);
        NpcChatReq query = new NpcChatReq(); query.setRequestId(id); query.setBizId(99L); query.setMessage("question");
        NpcChatResp result = service.chat(query, 1001L);
        assertEquals(id, result.getRequestId()); assertEquals("PROCESSING", result.getOutcomeStatus());
        assertEquals("UNKNOWN", result.getSafetyDecision()); assertEquals("REQUEST_OUTCOME_UNKNOWN", result.getErrorCode());
        assertFalse(result.isRetryable()); assertFalse(result.getSafeText().contains("WITHHELD_PROVIDER_TEXT"));
        verify(provider, times(1)).chat(anyString(), anyString());
        verify(recovery).retain(anyString(), eq(mode == 2 ? "OUTPUT_REJECTED" : "OUTPUT_REVIEW_UNKNOWN"));
        verify(recovery, never()).requireRefund(anyString(), anyString());
    }
}
