package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.AiQuotaRecoveryService;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.config.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Depends on C05-17. Real gateway quota classification through real NPC orchestration. */
class OWN_C04_14Test {
    @SuppressWarnings("unchecked")
    @Test void realQuotaLimitExceptionReachesNpcDayLimitOutcomeWithoutRefund() {
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt())).thenReturn(21L);
        AiQuotaRecoveryService recovery = mock(AiQuotaRecoveryService.class);
        AiModelConfig config = new AiModelConfig(); ReflectionTestUtils.setField(config, "npcDailyLimit", 20);
        AiGatewayServiceImpl gateway = new AiGatewayServiceImpl();
        ReflectionTestUtils.setField(gateway, "redisTemplate", redis);
        ReflectionTestUtils.setField(gateway, "quotaRecovery", recovery);
        ReflectionTestUtils.setField(gateway, "aiModelConfig", config);
        NpcChatWriteService writes = mock(NpcChatWriteService.class);
        when(writes.admit(any(NpcChatRequest.class), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(writes.finish(any(NpcChatRequest.class), any(NpcChatLog.class))).thenAnswer(inv -> inv.getArgument(0));
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
        NpcChatReq query = new NpcChatReq();
        query.setRequestId("00000000-0000-4000-8000-000000000014"); query.setBizId(99L); query.setMessage("question");
        NpcChatResp result = service.chat(query, 1001L);
        assertEquals("REJECTED", result.getOutcomeStatus()); assertEquals("RATE_LIMITED", result.getErrorCode());
        assertEquals("DAY", result.getLimitType()); assertTrue(result.getRetryAfterSeconds() > 0);
        assertEquals(query.getRequestId(), result.getRequestId());
        verify(recovery).retain(anyString(), eq("RATE_LIMITED"));
        verify(recovery, never()).requireRefund(anyString(), anyString());
    }
}
