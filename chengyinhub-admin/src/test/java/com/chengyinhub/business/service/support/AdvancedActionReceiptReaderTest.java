package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdvancedActionReceiptReaderTest {
    @Test void readsOnlyOriginalOwnedLedgerAndNeverRunsStateOrSettlement() {
        GameRuntimeSessionMapper sessions = mock(GameRuntimeSessionMapper.class);
        PlayAttemptLogMapper attempts = mock(PlayAttemptLogMapper.class);
        when(sessions.selectAccessibleById(51L, 7L)).thenReturn(new GameRuntimeSession());
        PlayAttemptLog log = new PlayAttemptLog(); log.setMemberId(7L); log.setAttemptType(8);
        log.setResultJson("{\"sessionId\":51,\"version\":4,\"check\":{\"dice\":[12],\"total\":17,\"breakdown\":{\"hidden\":true}}}");
        when(attempts.selectByIdempotencyKey("original")).thenReturn(log);
        var reader = new AdvancedActionReceiptReader(sessions, attempts, new ObjectMapper());
        var result = reader.read(7L, 51L, "original");
        assertEquals("51", result.get("sessionId")); assertEquals("original", result.get("idempotencyKey"));
        assertTrue(((com.fasterxml.jackson.databind.JsonNode) result.get("view")).path("check").path("total").isNull());
        verify(sessions).selectAccessibleById(51L, 7L);
        verify(attempts, times(3)).selectByIdempotencyKey(anyString());
        verifyNoMoreInteractions(sessions, attempts);
        assertThrows(ServiceException.class, () -> reader.read(8L, 51L, "original"));
        log.setMemberId(8L);
        assertThrows(ServiceException.class, () -> reader.read(7L, 51L, "original"));
        log.setMemberId(7L); log.setResultJson("{\"sessionId\":99}");
        assertThrows(ServiceException.class, () -> reader.read(7L, 51L, "original"));
    }
}
