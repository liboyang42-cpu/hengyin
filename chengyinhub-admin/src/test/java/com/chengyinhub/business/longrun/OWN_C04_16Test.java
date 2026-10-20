package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Busy response is not a queued receipt; a later explicit same-ID admission still runs once. */
class OWN_C04_16Test {
    @Test void busyThenExplicitSameIdRetryAdmitsOnceAndSubsequentReplayDoesNotGenerate() {
        String id = "00000000-0000-4000-8000-000000000016";
        AtomicReference<NpcChatRequest> stored = new AtomicReference<>();
        AtomicInteger admissions = new AtomicInteger();
        NpcChatWriteService writes = mock(NpcChatWriteService.class);
        when(writes.read(1001L, id)).thenAnswer(inv -> stored.get());
        when(writes.admit(any(NpcChatRequest.class), anyString())).thenAnswer(inv -> {
            if (admissions.getAndIncrement() == 0) throw new DuplicateKeyException("fixture other request owns active session");
            return inv.getArgument(0);
        });
        when(writes.finish(any(NpcChatRequest.class), any(NpcChatLog.class))).thenAnswer(inv -> {
            NpcChatRequest terminal = inv.getArgument(0); stored.set(terminal); return terminal;
        });
        AiGatewayService gateway = mock(AiGatewayService.class);
        when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("fixture", "admitted answer", "fixture", 0));
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
        assertEquals("REQUEST_IN_PROGRESS", service.chat(query, 1001L).getErrorCode());
        assertNull(stored.get(), "busy response did not claim an incoming request receipt was persisted");
        verifyNoInteractions(gateway);
        assertEquals("SUCCEEDED", service.chat(query, 1001L).getOutcomeStatus());
        assertEquals("admitted answer", service.chat(query, 1001L).getSafeText());
        verify(gateway, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
        assertEquals(2, admissions.get());
    }
}
