package com.chengyinhub.business.service.impl;

import java.util.UUID;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.service.ai.AiNotSentGatewayFixture;
import com.chengyinhub.business.service.ai.AiProviderNotSentException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real NpcChat + Gateway composition; reused frozen mapper-double fixture, not a MySQL claim. */
class NpcChatGatewayNotSentContractTest
{
    @Test void typedUnsentMapsToStoredNotRunAndSameNpcRequestDoesNotReserveOrSendAgain()
    {
        check(new AiProviderNotSentException(), "FAILED", "NOT_RUN", "AI_UNAVAILABLE");
    }

    @Test void ordinaryProviderFailureKeepsOriginalUnknownAndRefundsDialogueQuotaOnce()
    {
        check(new IllegalStateException("provider timeout"), "PROCESSING", "UNKNOWN", "REQUEST_OUTCOME_UNKNOWN");
    }

    private void check(RuntimeException failure, String outcome, String safety, String code)
    {
        NpcChatServiceTest original = new NpcChatServiceTest(); original.setUp();
        NpcChatService npc = (NpcChatService) ReflectionTestUtils.getField(original, "service");
        AiNotSentGatewayFixture gateway = new AiNotSentGatewayFixture(2002L);
        when(gateway.model.chat(anyString(), anyString())).thenThrow(failure);
        ReflectionTestUtils.setField(npc, "aiGatewayService", gateway.gateway);
        NpcChatReq request = new NpcChatReq();
        request.setBizId(99L); request.setRequestId(UUID.randomUUID().toString()); request.setMessage("hello");
        var first = npc.chat(request, 2002L);
        assertEquals(outcome, first.getOutcomeStatus()); assertEquals(safety, first.getSafetyDecision());
        assertEquals(code, first.getErrorCode());
        var replay = npc.chat(request, 2002L);
        assertEquals(first.getRequestId(), replay.getRequestId());
        assertEquals(first.getOutcomeStatus(), replay.getOutcomeStatus());
        assertEquals(first.getSafetyDecision(), replay.getSafetyDecision());
        assertEquals(first.getErrorCode(), replay.getErrorCode());
        var read = npc.readOriginalRequest("roam", 99L, request.getRequestId(), 2002L);
        assertEquals(first.getRequestId(), read.getRequestId()); assertEquals(safety, read.getSafetyDecision());
        String receipt = gateway.originalReceipt(2002L, "npc_chat", "npc");
        verify(gateway.model, times(1)).chat(anyString(), anyString());
        verify(gateway.recovery).requireRefund(receipt, "DIALOGUE_FAILURE");
        verify(gateway.recovery).stage(receipt, "INPUT_REVIEW_STARTED");
        verify(gateway.recovery).stage(receipt, "MODEL_STARTED");
        verify(gateway.recovery, never()).retain(anyString(), anyString());
        verifyNoMoreInteractions(gateway.recovery);
        verify(gateway.redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyInt(), anyInt(), anyInt());
        verifyNoMoreInteractions(gateway.redis);
    }
}
