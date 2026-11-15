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
        ReflectionTestUtils.setField(writer, "locationConsent", mock(RoamLocationConsentService.class));
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
    @Test void jdbcTimestampAndDateForSameMillisAreIdempotent() {
        Map<String, Object> original = new HashMap<>(); original.put("tile", "wtw3sjq"); original.put("collectedAt", new java.sql.Timestamp(2000));
        when(archives.selectOriginalRecords(anyLong(), anyLong(), anyList())).thenReturn(Collections.singletonList(original));
        assertEquals(1, writer.archiveFinishedRecords(77L, 9L, null, record(2000)));
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
    @Test void revokedConsentOrRetentionClearedSessionCannotArchive() {
        RoamLocationConsentService consent = mock(RoamLocationConsentService.class);
        ReflectionTestUtils.setField(writer, "locationConsent", consent);
        doThrow(new ServiceException("revoked")).when(consent).requireCurrentAgreement(77L);
        assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(77L, 9L, null, record(2000)));
        verifyNoInteractions(sessions);
        doNothing().when(consent).requireCurrentAgreement(77L);
        session.setLocationClearedAt(new Date(4000));
        assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(77L, 9L, null, record(2000)));
        verifyNoInteractions(archives);
    }
    @Test void entireBatchValidatesBeforeAnyInsert() {
        // 容差关掉(0 s)时窗口仍是严格的 [startedAt, finishedAt];默认容差见下方 B3 用例。
        ReflectionTestUtils.setField(writer, "collectedAtSkewSeconds", 0L);
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

    /** B3(acceptance-r3):手机时钟慢/快几秒的采集时间在容差内收口到行程边界;超出容差仍拒绝。 */
    @Test void collectedAtToleratesClockSkewAndClampsIntoTheTrip() {
        long tol = 120_000L;
        Date start = new Date(1_000_000L), finish = new Date(2_000_000L), now = new Date(2_000_000L);
        assertEquals(new Date(1_000_000L), RoamSessionWriteService.validateArchive(record(1_000_000L - 6_000L), start, finish, now, tol)
                .get(0).get("collectedAt"), "手机慢 6 s:收口到 startedAt");
        assertEquals(new Date(2_000_000L), RoamSessionWriteService.validateArchive(record(2_000_000L + 6_000L), start, finish, now, tol)
                .get(0).get("collectedAt"), "手机快 6 s:收口到 finishedAt");
        assertEquals(new Date(1_500_000L), RoamSessionWriteService.validateArchive(record(1_500_000L), start, finish, now, tol)
                .get(0).get("collectedAt"), "行程内的时间原样保存");
        assertThrows(ServiceException.class, () -> RoamSessionWriteService.validateArchive(record(1_000_000L - tol - 1), start, finish, now, tol));
        assertThrows(ServiceException.class, () -> RoamSessionWriteService.validateArchive(record(2_000_000L + tol + 1), start, finish, now, tol));
        assertThrows(ServiceException.class, () -> RoamSessionWriteService.validateArchive(record(1_000_000L - 1), start, finish, now),
                "无容差的旧入口保持严格");
    }

    /** B3(acceptance-r3):默认 120 s 容差下,手机慢 1 ms / 快 1 ms 的采集被收口到行程边界入库。 */
    @Test void defaultToleranceArchivesSlightlySkewedTilesAtTheTripBoundary() {
        assertEquals(1, writer.archiveFinishedRecords(77L, 9L, null, record(999)));
        assertEquals(1, writer.archiveFinishedRecords(77L, 9L, null, record(3001)));
        org.mockito.ArgumentCaptor<java.util.List> rows = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(archives, org.mockito.Mockito.times(2)).insertRecords(eq(77L), eq(9L), rows.capture(), any(Date.class));
        assertEquals(new Date(1000), ((java.util.Map<?, ?>) rows.getAllValues().get(0).get(0)).get("collectedAt"));
        assertEquals(new Date(3000), ((java.util.Map<?, ?>) rows.getAllValues().get(1).get(0)).get("collectedAt"));
        assertThrows(ServiceException.class, () -> writer.archiveFinishedRecords(77L, 9L, null, record(3000 + 120_001)));
    }

    /** SMOKE-500-R4:采集时间落在原行程之外是请求数据问题,400 而不是 500,文案不变。 */
    @Test void outOfSessionCollectedAtIsA400() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> RoamSessionWriteService.validateArchive(record(4000), new Date(1000), new Date(3000), new Date(5000)));
        assertEquals("采集时间不属于原行程", e.getMessage());
        assertEquals(Integer.valueOf(400), e.getCode());
    }
}
