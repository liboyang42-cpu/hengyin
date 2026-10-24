package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.business.service.impl.NpcChatWriteService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Documents existing requestId compatibility; does not invent a message fingerprint. */
class OWN_C04_01Test {
    @Test void existingScopeIsCheckedButChangedMessageKeepsOriginalReplayContract() {
        String id = "00000000-0000-4000-8000-000000000001";
        NpcChatWriteService writes = mock(NpcChatWriteService.class);
        AiGatewayService gateway = mock(AiGatewayService.class);
        NpcChatRequest old = new NpcChatRequest();
        old.setUserId(1001L); old.setRequestId(id); old.setSessionId(99L);
        old.setStatus("SUCCEEDED"); old.setSafetyDecision("PASS"); old.setSafeText("original answer");
        old.setRetryable(false); old.setAi(true);
        when(writes.read(1001L, id)).thenReturn(old);
        NpcChatService service = new NpcChatService();
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
        NpcChatReq request = new NpcChatReq();
        request.setRequestId(id); request.setBizId(100L); request.setMessage("different message");
        NpcChatResp mismatch = service.chat(request, 1001L);
        assertEquals("REJECTED", mismatch.getOutcomeStatus());
        assertEquals("INVALID_REQUEST", mismatch.getErrorCode());
        assertNotEquals("original answer", mismatch.getSafeText());
        request.setBizId(99L);
        assertEquals("original answer", service.chat(request, 1001L).getSafeText());
        verifyNoInteractions(gateway);
    }
}
