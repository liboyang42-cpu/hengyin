package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcRequestStatusTest {
    private static final String RID = "abcdef01-abcd-4abc-8abc-abcdefabcdef";
    private NpcChatService service;
    private NpcChatRequestMapper requests;
    private RoamMapper roam;

    @BeforeEach
    void setup() {
        service = new NpcChatService();
        requests = mock(NpcChatRequestMapper.class);
        roam = mock(RoamMapper.class);
        ReflectionTestUtils.setField(service, "npcChatRequestMapper", requests);
        ReflectionTestUtils.setField(service, "roamMapper", roam);
        // Model, voice, scheduler and quota dependencies are deliberately absent: reads cannot use them.
    }

    private NpcChatRequest stored(long session, String status) {
        NpcChatRequest row = new NpcChatRequest();
        row.setUserId(9L); row.setRequestId(RID); row.setSessionId(session);
        row.setStatus(status); row.setSafetyDecision("PASS"); row.setSafeText("原来的安全回答");
        when(requests.selectByUserAndRequestId(9L, RID)).thenReturn(row);
        return row;
    }

    @Test
    void recreatedServiceReadsOriginalTerminalWithoutGeneratingOrWriting() {
        stored(-11L, "SUCCEEDED");
        NpcChatResp result = service.requestStatus(RID, 9L, "shop", 11L);
        assertEquals(RID, result.getRequestId());
        assertEquals("SUCCEEDED", result.getOutcomeStatus());
        assertEquals("原来的安全回答", result.getSafeText());
        assertNull(result.getAudioUrl());
        verify(requests).selectByUserAndRequestId(9L, RID);
        verifyNoMoreInteractions(requests);
        verifyNoInteractions(roam);
    }

    @Test
    void sameRequestOtherOwnerAndObjectReturnIdenticalUnknownWithoutOriginalText() {
        stored(-11L, "SUCCEEDED");
        NpcChatResp otherOwner = service.requestStatus(RID, 10L, "shop", 11L);
        NpcChatResp otherObject = service.requestStatus(RID, 9L, "shop", 12L);
        assertEquals("REQUEST_NOT_FOUND", otherOwner.getErrorCode());
        assertEquals(otherOwner.getErrorCode(), otherObject.getErrorCode());
        assertEquals(otherOwner.getSafeText(), otherObject.getSafeText());
        assertNotEquals("原来的安全回答", otherOwner.getSafeText());
        verify(requests).selectByUserAndRequestId(10L, RID);
        verify(requests).selectByUserAndRequestId(9L, RID);
        verifyNoMoreInteractions(requests);
    }

    @Test
    void merchantAndShopScopesCannotReadEachOthersRows() {
        NpcChatRequest row = stored(-11L, "SUCCEEDED");
        assertEquals("REQUEST_NOT_FOUND", service.requestStatus(RID, 9L, "merchant", 11L).getErrorCode());
        row.setSessionId(-(Long.MAX_VALUE / 2 + 11L));
        assertEquals("SUCCEEDED", service.requestStatus(RID, 9L, "merchant", 11L).getOutcomeStatus());
        assertEquals("REQUEST_NOT_FOUND", service.requestStatus(RID, 9L, "shop", 11L).getErrorCode());
    }

    @Test
    void invalidIdentifiersNeverLookupOrGenerate() {
        assertEquals("INVALID_REQUEST", service.requestStatus("broken", 9L, "shop", 11L).getErrorCode());
        assertEquals("INVALID_REQUEST", service.requestStatus(RID, null, "shop", 11L).getErrorCode());
        assertEquals("INVALID_REQUEST", service.requestStatus(RID, 9L, "global", 11L).getErrorCode());
        assertEquals("INVALID_REQUEST", service.requestStatus(RID, 9L, "merchant", Long.MAX_VALUE).getErrorCode());
        assertEquals("INVALID_REQUEST", service.requestStatus(RID, 9L, "shop", 0L).getErrorCode());
        verifyNoInteractions(requests, roam);
    }

    @Test
    void missingProcessingAndUnavailableStatesNeverAuthorizeAnotherGeneration() {
        assertEquals("REQUEST_NOT_FOUND", service.requestStatus(RID, 9L, "shop", 11L).getErrorCode());
        stored(-11L, "PROCESSING");
        assertEquals("PROCESSING", service.requestStatus(RID, 9L, "shop", 11L).getOutcomeStatus());
        when(requests.selectByUserAndRequestId(9L, RID)).thenThrow(new IllegalStateException("unavailable"));
        assertEquals("REQUEST_STATE_UNAVAILABLE", service.requestStatus(RID, 9L, "shop", 11L).getErrorCode());
        verify(requests, times(3)).selectByUserAndRequestId(9L, RID);
        verifyNoMoreInteractions(requests);
    }

    @Test
    void corruptRowRemainsUnavailableInsteadOfBecomingTerminal() {
        NpcChatRequest row = stored(-11L, null);
        assertEquals("REQUEST_STATE_UNAVAILABLE", service.requestStatus(RID, 9L, "shop", 11L).getErrorCode());
        row.setStatus("SUCCEEDED"); row.setSafetyDecision("BLOCKED");
        assertEquals("REQUEST_STATE_UNAVAILABLE", service.requestStatus(RID, 9L, "shop", 11L).getErrorCode());
        row.setSafetyDecision("PASS"); row.setSafeText(null);
        assertEquals("REQUEST_STATE_UNAVAILABLE", service.requestStatus(RID, 9L, "shop", 11L).getErrorCode());
        row.setSafeText("text"); row.setStatus("FAILED"); row.setErrorCode(null);
        assertEquals("REQUEST_STATE_UNAVAILABLE", service.requestStatus(RID, 9L, "shop", 11L).getErrorCode());
    }

    @Test
    void originalFailedAndRejectedOutcomesAreReturnedForCleanup() {
        NpcChatRequest row = stored(-11L, "FAILED"); row.setErrorCode("UPSTREAM_FAILED");
        assertEquals("FAILED", service.requestStatus(RID, 9L, "shop", 11L).getOutcomeStatus());
        row.setStatus("REJECTED"); row.setSafetyDecision("BLOCKED");
        assertEquals("REJECTED", service.requestStatus(RID, 9L, "shop", 11L).getOutcomeStatus());
    }

    @Test
    void roamRequiresCurrentOwnedActiveSessionAndCannotReadShopRow() {
        RoamSession session = new RoamSession(); session.setMemberId(9L); session.setStatus(0);
        when(roam.selectSessionById(11L)).thenReturn(session);
        NpcChatRequest row = stored(11L, "SUCCEEDED");
        assertEquals("SUCCEEDED", service.requestStatus(RID, 9L, "roam", 11L).getOutcomeStatus());
        row.setSessionId(-11L);
        assertEquals("REQUEST_NOT_FOUND", service.requestStatus(RID, 9L, "roam", 11L).getErrorCode());
        session.setStatus(1);
        assertEquals("REQUEST_NOT_FOUND", service.requestStatus(RID, 9L, "roam", 11L).getErrorCode());
        session.setStatus(0); session.setMemberId(10L);
        assertEquals("REQUEST_NOT_FOUND", service.requestStatus(RID, 9L, "roam", 11L).getErrorCode());
        verify(requests, times(2)).selectByUserAndRequestId(9L, RID);
        verifyNoMoreInteractions(requests);
    }
}
