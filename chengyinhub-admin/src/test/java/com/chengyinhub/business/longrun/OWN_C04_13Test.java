package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.business.service.impl.NpcChatWriteService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. A transport/storage lookup failure is not proof of an unexecuted original request. */
class OWN_C04_13Test {
    @Test void initialReadFailurePreservesUnknownOriginalIdentityAcrossNpcEntries() {
        String id = "00000000-0000-4000-8000-000000000013";
        NpcChatWriteService writes = mock(NpcChatWriteService.class);
        when(writes.read(anyLong(), anyString())).thenThrow(new IllegalStateException("fixture read unavailable"));
        AiGatewayService gateway = mock(AiGatewayService.class);
        NpcChatService service = new NpcChatService();
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
        NpcChatReq query = new NpcChatReq(); query.setRequestId(id); query.setBizId(99L); query.setMessage("question");
        NpcChatResp[] results = {
                service.chat(query, 1001L),
                service.chatWithShop(id, 1001L, 21L, 41L, "fixture", "question"),
                service.chatWithMerchant(query, 1001L, 41L),
                service.storedShopRequest(id, 1001L, 21L)
        };
        for (NpcChatResp result : results) {
            assertEquals(id, result.getRequestId());
            assertEquals("PROCESSING", result.getOutcomeStatus());
            assertEquals("UNKNOWN", result.getSafetyDecision());
            assertEquals("REQUEST_OUTCOME_UNKNOWN", result.getErrorCode());
            assertFalse(result.isRetryable());
        }
        verifyNoInteractions(gateway);
        verify(writes, never()).admit(any(), anyString());
    }
    @Test void invalidIdentityStillFailsBeforeAnyReadOrProvider() {
        NpcChatWriteService writes = mock(NpcChatWriteService.class);
        AiGatewayService gateway = mock(AiGatewayService.class);
        NpcChatService service = new NpcChatService();
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
        NpcChatResp result = service.storedShopRequest("not-a-uuid", 1001L, 21L);
        assertEquals("REJECTED", result.getOutcomeStatus());
        assertEquals("INVALID_REQUEST", result.getErrorCode());
        verifyNoInteractions(writes, gateway);
    }
}
