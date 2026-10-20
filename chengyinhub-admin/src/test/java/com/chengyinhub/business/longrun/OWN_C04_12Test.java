package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. A real gateway result has no usage fields; NPC log must not manufacture zero usage. */
class OWN_C04_12Test {
    @Test void successfulAndUnknownProviderOutcomesKeepLogUsageUnknown() {
        for (boolean unknown : new boolean[] {false, true}) {
            String id = "00000000-0000-4000-8000-000000000012";
            NpcChatWriteService writes = mock(NpcChatWriteService.class);
            AiGatewayService gateway = mock(AiGatewayService.class);
            if (unknown) when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new AiProviderOutcomeUnknownException("fixture timeout", new RuntimeException()));
            else when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new AiGatewayResult("fixture", "answer", "fixture", 0));
            AtomicReference<NpcChatRequest> stored = new AtomicReference<>();
            when(writes.read(1001L, id)).thenAnswer(inv -> stored.get());
            when(writes.admit(any(NpcChatRequest.class), anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(writes.finish(any(NpcChatRequest.class), any(NpcChatLog.class))).thenAnswer(inv -> {
                NpcChatLog entry = inv.getArgument(1);
                assertNull(entry.getTokenIn()); assertNull(entry.getTokenOut());
                assertEquals(Long.valueOf(1001L), entry.getUserId());
                assertEquals("question", entry.getUserMsg());
                assertEquals(unknown ? null : "answer", entry.getNpcReply());
                NpcChatRequest terminal = inv.getArgument(0); stored.set(terminal); return terminal;
            });
            NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
            when(requests.renewLease(anyLong(), anyString(), anyString(), any(Date.class))).thenReturn(1);
            NpcProfileMapper profiles = mock(NpcProfileMapper.class);
            NpcProfile profile = new NpcProfile(); profile.setId(1L); profile.setPersona("fixture persona");
            when(profiles.selectGlobalNpc()).thenReturn(profile);
            RoamMapper roam = mock(RoamMapper.class);
            RoamSession session = new RoamSession(); session.setId(99L); session.setMemberId(1001L); session.setStatus(0);
            when(roam.selectSessionById(99L)).thenReturn(session);
            NpcMemoryBuilder memory = mock(NpcMemoryBuilder.class); when(memory.buildRoam(any())).thenReturn("progress");
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            doReturn(mock(ScheduledFuture.class)).when(scheduler)
                    .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
            NpcChatService service = new NpcChatService();
            ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
            ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
            ReflectionTestUtils.setField(service, "npcChatRequestMapper", requests);
            ReflectionTestUtils.setField(service, "npcProfileMapper", profiles);
            ReflectionTestUtils.setField(service, "roamMapper", roam);
            ReflectionTestUtils.setField(service, "memoryBuilder", memory);
            ReflectionTestUtils.setField(service, "scheduledExecutorService", scheduler);
            NpcChatReq query = new NpcChatReq(); query.setRequestId(id); query.setBizId(99L); query.setMessage("question");
            NpcChatResp result = service.chat(query, 1001L);
            assertEquals(unknown ? "PROCESSING" : "SUCCEEDED", result.getOutcomeStatus());
            verify(writes, times(1)).finish(any(NpcChatRequest.class), any(NpcChatLog.class));
        }
    }
}
