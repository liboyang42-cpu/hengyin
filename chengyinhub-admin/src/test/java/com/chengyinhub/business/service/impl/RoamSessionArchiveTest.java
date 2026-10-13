package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.RoamSessionRecordMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoamSessionArchiveTest {
    private RoamMapper sessions;
    private RoamSessionRecordMapper archives;
    private RoamSessionWriteService writer;
    private RoamSession session;
    @BeforeEach void setup() {
        sessions = mock(RoamMapper.class); archives = mock(RoamSessionRecordMapper.class);
        writer = new RoamSessionWriteService();
        ReflectionTestUtils.setField(writer, "roamMapper", sessions);
        ReflectionTestUtils.setField(writer, "recordMapper", archives);
        session = new RoamSession(); session.setId(9L); session.setMemberId(77L); session.setStatus(1);
        session.setStartedAt(new Date(1000)); session.setFinishedAt(new Date(3000));
        session.setClientSessionKey("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        session.setXpAwarded(7); session.setTilesRevealed(1); session.setDistanceM(120);
        when(sessions.selectOwnedSessionForUpdate(9L, 77L)).thenReturn(session);
        when(archives.selectOriginalRecords(anyLong(), anyLong(), anyList())).thenReturn(Collections.emptyList());
    }
    private String record(long time) { return "[{\"tile\":\"wtw3sjq\",\"collectedAt\":" + time + "}]"; }
    @Test void archiveAndRetryPreserveOriginalSettlementAndUseOnlyArchiveMapper() {
        assertEquals(1, writer.archiveFinishedRecords(77L, 9L, session.getClientSessionKey(), record(2000)));
        assertEquals(1, writer.archiveFinishedRecords(77L, 9L, session.getClientSessionKey(), record(2000)));
        verify(sessions, times(2)).selectOwnedSessionForUpdate(9L, 77L);
        verifyNoMoreInteractions(sessions);
        verify(archives, times(2)).insertRecords(eq(77L), eq(9L), anyList(), any(Date.class));
        assertEquals(7, session.getXpAwarded()); assertEquals(120, session.getDistanceM()); assertEquals(1, session.getTilesRevealed());
    }
    @Test void originalRecordCollisionCannotRewriteTime() {
        Map<String, Object> original = new HashMap<>(); original.put("tile", "wtw3sjq"); original.put("collectedAt", new Date(2000));
        when(archives.selectOriginalRecords(anyLong(), anyLong(), anyList())).thenReturn(Collections.singletonList(original));
        assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(77L, 9L, null, record(2500)));
        verify(archives, never()).insertRecords(anyLong(), anyLong(), anyList(), any());
    }
    @Test void strangerWrongSidKeyAndActiveCannotArchive() {
        assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(88L, 9L, null, record(2000)));
        assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(77L, 10L, null, record(2000)));
        assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(77L, 9L, "wrong", record(2000)));
        session.setStatus(0);
        assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(77L, 9L, null, record(2000)));
        verifyNoInteractions(archives);
    }
    @Test void entireBatchValidatesBeforeAnyInsert() {
        for (String payload : Arrays.asList("[{\"tile\":\"wtw3sjq\",\"collectedAt\":2000},{\"tile\":\"bad\",\"collectedAt\":2000}]",
                record(999), record(3001), "[]", "{}", "null", "[null]",
                "[{\"tile\":\"wtw3sjq\",\"collectedAt\":\"2000\"}]",
                "[{\"tile\":\"wtw3sjq\",\"collectedAt\":2000.5}]",
                "[{\"tile\":\"wtw3sjq\",\"collectedAt\":2000,\"extra\":1}]")) {
            assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(77L, 9L, null, payload), payload);
        }
        verifyNoInteractions(archives);
    }
    @Test void inclusiveBoundariesDedupAndFutureTime() {
        assertEquals(1, RoamSessionWriteService.validateArchive(record(1000), new Date(1000), new Date(3000), new Date(3000)).size());
        assertEquals(1, RoamSessionWriteService.validateArchive(record(3000), new Date(1000), new Date(3000), new Date(3000)).size());
        assertEquals(1, RoamSessionWriteService.validateArchive("[{\"tile\":\"wtw3sjq\",\"collectedAt\":2000},{\"tile\":\"wtw3sjq\",\"collectedAt\":2000}]",
                new Date(1000), new Date(3000), new Date(3000)).size());
        assertThrows(ServiceException.class, () -> RoamSessionWriteService.validateArchive(record(2500), new Date(1000), new Date(3000), new Date(2000)));
        assertThrows(ServiceException.class, () -> RoamSessionWriteService.validateArchive("[{\"tile\":\"wtw3sjq\",\"collectedAt\":2000},{\"tile\":\"wtw3sjq\",\"collectedAt\":2500}]",
                new Date(1000), new Date(3000), new Date(3000)));
    }
}
