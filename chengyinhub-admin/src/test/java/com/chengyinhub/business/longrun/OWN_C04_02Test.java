package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.business.service.impl.NpcChatWriteService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Admission-owner orchestration, not a substitute for real database concurrency tests. */
class OWN_C04_02Test {
    @Test void anotherWorkersAdmissionPreventsSecondProviderForProcessingAndTerminalRows() {
        for (String status : new String[] {"PROCESSING", "SUCCEEDED"}) {
            String id = "00000000-0000-4000-8000-000000000002";
            NpcChatWriteService writes = mock(NpcChatWriteService.class);
            AiGatewayService gateway = mock(AiGatewayService.class);
            RoamMapper roam = mock(RoamMapper.class);
            RoamSession session = new RoamSession(); session.setId(99L); session.setMemberId(1001L); session.setStatus(0);
            when(roam.selectSessionById(99L)).thenReturn(session);
            NpcChatRequest winner = new NpcChatRequest();
            winner.setUserId(1001L); winner.setRequestId(id); winner.setSessionId(99L);
            winner.setStatus(status); winner.setSafetyDecision("PASS"); winner.setLeaseToken("other-worker");
            winner.setLeaseUntil(new Date(System.currentTimeMillis() + 60000));
            winner.setSafeText("winner answer"); winner.setRetryable(false); winner.setAi(true);
            when(writes.admit(any(NpcChatRequest.class), anyString())).thenReturn(winner);
            NpcChatService service = new NpcChatService();
            ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
            ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
            ReflectionTestUtils.setField(service, "roamMapper", roam);
            NpcChatReq request = new NpcChatReq(); request.setRequestId(id); request.setBizId(99L); request.setMessage("question");
            assertEquals(status, service.chat(request, 1001L).getOutcomeStatus());
            verify(writes).admit(any(NpcChatRequest.class), anyString());
            verifyNoInteractions(gateway);
        }
    }
}
