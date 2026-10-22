package com.chengyinhub.business.service.impl;

import java.util.Date;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.service.ai.AiGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** #27 原请求只读回读:本人 + 原会话/原店 + 原 requestId,从不进模型。 */
class NpcChatReadbackTest {
    private static final Long USER = 2002L;
    private static final String RID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final long MAX_ENTITY = Long.MAX_VALUE / 2;

    private NpcChatService service;
    private NpcChatWriteService writes;
    private AiGatewayService gateway;

    @BeforeEach
    void setUp() {
        service = new NpcChatService();
        writes = mock(NpcChatWriteService.class);
        gateway = mock(AiGatewayService.class);
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
    }

    private NpcChatRequest stored(long sessionKey, String status) {
        NpcChatRequest r = new NpcChatRequest();
        r.setUserId(USER);
        r.setRequestId(RID);
        r.setSessionId(sessionKey);
        r.setStatus(status);
        r.setSafetyDecision("PASS");
        r.setSafeText("原答案");
        r.setAi(true);
        return r;
    }

    @Test
    void roamAnswerIsReturnedWithoutCallingProvider() {
        when(writes.read(USER, RID)).thenReturn(stored(99L, NpcChatRequest.STATUS_SUCCEEDED));
        NpcChatResp resp = service.readOriginalRequest("roam", 99L, RID, USER);
        assertEquals("SUCCEEDED", resp.getOutcomeStatus());
        assertEquals("原答案", resp.getSafeText());
        verifyNoInteractions(gateway);
    }

    @Test
    void merchantAndShopKeysMatchTheirWritePaths() {
        when(writes.read(USER, RID)).thenReturn(stored(-(MAX_ENTITY + 7L), NpcChatRequest.STATUS_SUCCEEDED));
        assertEquals("原答案", service.readOriginalRequest("merchant", 7L, RID, USER).getSafeText());
        assertEquals("INVALID_REQUEST", service.readOriginalRequest("shop", 7L, RID, USER).getErrorCode());
        when(writes.read(USER, RID)).thenReturn(stored(-21L, NpcChatRequest.STATUS_SUCCEEDED));
        assertEquals("原答案", service.readOriginalRequest("shop", 21L, RID, USER).getSafeText());
        assertEquals("INVALID_REQUEST", service.readOriginalRequest("roam", 21L, RID, USER).getErrorCode());
    }

    @Test
    void otherSessionOrScopeIsIndistinguishableFromInvalid() {
        when(writes.read(USER, RID)).thenReturn(stored(99L, NpcChatRequest.STATUS_SUCCEEDED));
        NpcChatResp resp = service.readOriginalRequest("roam", 100L, RID, USER);
        assertEquals("INVALID_REQUEST", resp.getErrorCode());
        assertTrue(!"原答案".equals(resp.getSafeText()));
        assertEquals("INVALID_REQUEST", service.readOriginalRequest("other", 99L, RID, USER).getErrorCode());
        assertEquals("INVALID_REQUEST", service.readOriginalRequest("roam", 99L, "not-a-uuid", USER).getErrorCode());
        assertEquals("INVALID_REQUEST", service.readOriginalRequest("roam", null, RID, USER).getErrorCode());
    }

    @Test
    void missingRowIsNotFoundAndRetryableNeverATerminalFailure() {
        when(writes.read(USER, RID)).thenReturn(null);
        NpcChatResp resp = service.readOriginalRequest("roam", 99L, RID, USER);
        assertEquals(NpcChatService.STATUS_NOT_FOUND, resp.getOutcomeStatus());
        assertEquals("REQUEST_NOT_FOUND", resp.getErrorCode());
        assertTrue(resp.isRetryable());
    }

    @Test
    void expiredLeaseIsUnknownAndLiveLeaseIsInProgress() {
        NpcChatRequest processing = stored(99L, NpcChatRequest.STATUS_PROCESSING);
        processing.setLeaseUntil(new Date(0L));
        when(writes.read(USER, RID)).thenReturn(processing);
        NpcChatResp unknown = service.readOriginalRequest("roam", 99L, RID, USER);
        assertEquals("REQUEST_OUTCOME_UNKNOWN", unknown.getErrorCode());
        processing.setLeaseUntil(new Date(System.currentTimeMillis() + 60_000L));
        assertEquals("REQUEST_IN_PROGRESS", service.readOriginalRequest("roam", 99L, RID, USER).getErrorCode());
    }

    @Test
    void storageFailureIsUnknownNotMissing() {
        when(writes.read(USER, RID)).thenThrow(new RuntimeException("db down"));
        NpcChatResp resp = service.readOriginalRequest("roam", 99L, RID, USER);
        assertEquals("REQUEST_OUTCOME_UNKNOWN", resp.getErrorCode());
    }

    @Test
    void anonymousCallerReadsNothing() {
        NpcChatResp resp = service.readOriginalRequest("roam", 99L, RID, null);
        assertEquals("UNAUTHENTICATED", resp.getErrorCode());
        verify(writes, never()).read(anyLong(), anyString());
    }
}
