package com.chengyinhub.business.service.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class OfflinePackOperationTest {

    private static final String CHECKSUM = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final Instant NOW = Instant.parse("2026-10-23T20:00:00Z");

    private static OfflinePackOperation.Manifest manifest(String operationId) {
        return new OfflinePackOperation.Manifest("pack-1", operationId, "v1", 1024, CHECKSUM, List.of(11, 12),
                Instant.parse("2026-12-01T00:00:00Z"));
    }

    @Test
    void unusedResumeDoesNotOccupyThenUniqueStartDownloadApplies() {
        OfflinePackOperation pack = new OfflinePackOperation();
        assertNull(pack.operationId());
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase());
        assertEquals(OfflinePackOperation.Failure.NOT_PAUSED,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.resume("op-class-never"))
                        .code);
        assertNull(pack.operationId(), "未见 resume 不得占 operationId");
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase());

        pack.startDownload(manifest("op-class"), NOW);
        assertEquals("op-class", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase());
        pack.pause();
        assertEquals(OfflinePackOperation.Failure.OPERATION_MISMATCH,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.resume("op-other")).code);
        assertEquals("op-class", pack.operationId(), "错误 resume 不得改写原 operationId");
        pack.resume("op-class");
        pack.finishDownload();
        assertEquals("op-class", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        OfflinePackOperation restarted = new OfflinePackOperation();
        assertNull(restarted.operationId());
        assertEquals(OfflinePackOperation.Phase.IDLE, restarted.phase());
    }
}
