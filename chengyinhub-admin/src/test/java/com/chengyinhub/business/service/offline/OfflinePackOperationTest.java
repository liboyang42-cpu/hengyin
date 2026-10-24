package com.chengyinhub.business.service.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class OfflinePackOperationTest {

    private static final Instant NOW = OfflinePackFixtures.NOW;

    private static OfflinePackOperation.Manifest manifest(String operationId) {
        return OfflinePackFixtures.manifest(operationId);
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
        pack.finishDownload(OfflinePackFixtures.PAYLOAD);
        assertEquals("op-class", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        OfflinePackOperation restarted = new OfflinePackOperation();
        assertNull(restarted.operationId());
        assertEquals(OfflinePackOperation.Phase.IDLE, restarted.phase());
    }

    @Test
    void unusedDeleteOfIdleDoesNotOccupyThenUniqueStartThenDeleteApplies() {
        OfflinePackOperation pack = new OfflinePackOperation();
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase());
        assertNull(pack.operationId());
        assertEquals(OfflinePackOperation.Failure.INVALID_MANIFEST,
                assertThrows(OfflinePackOperation.OfflinePackException.class, pack::delete).code);
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase(), "未见下载 delete 观察不得占 DELETED");
        assertNull(pack.operationId());
        pack.startDownload(manifest("op-del"), NOW);
        assertEquals("op-del", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase());
        pack.delete();
        assertEquals(OfflinePackOperation.Phase.DELETED, pack.phase());
        assertEquals("op-del", pack.operationId());
    }

    @Test
    void unusedExpireOfIdleDoesNotOccupyThenUniqueStartThenExpireApplies() {
        OfflinePackOperation pack = new OfflinePackOperation();
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase());
        assertNull(pack.operationId());
        assertEquals(OfflinePackOperation.Failure.INVALID_MANIFEST,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.expire(NOW)).code);
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase(), "未见下载 expire 观察不得占 EXPIRED");
        assertNull(pack.operationId());
        pack.startDownload(manifest("op-exp"), NOW);
        assertEquals("op-exp", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase());
        assertEquals(OfflinePackOperation.Failure.INVALID_MANIFEST,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.expire(NOW)).code);
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase(), "未到期 expire 不得占 EXPIRED");
        pack.expire(OfflinePackFixtures.EXPIRES);
        assertEquals(OfflinePackOperation.Phase.EXPIRED, pack.phase());
        assertEquals("op-exp", pack.operationId());
    }

    @Test
    void unusedRecordCorruptOfIdleDoesNotOccupyThenUniqueStartThenCorruptApplies() {
        OfflinePackOperation pack = new OfflinePackOperation();
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase());
        assertNull(pack.operationId());
        assertEquals(OfflinePackOperation.Failure.INVALID_MANIFEST,
                assertThrows(OfflinePackOperation.OfflinePackException.class, pack::recordCorrupt).code);
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase(), "未见下载 recordCorrupt 观察不得占 CORRUPT");
        assertNull(pack.operationId());
        pack.startDownload(manifest("op-cor"), NOW);
        assertEquals("op-cor", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase());
        assertEquals(OfflinePackOperation.Failure.CORRUPT,
                assertThrows(OfflinePackOperation.OfflinePackException.class, pack::recordCorrupt).code);
        assertEquals(OfflinePackOperation.Phase.CORRUPT, pack.phase());
        assertEquals("op-cor", pack.operationId());
    }

    @Test
    void unusedRecordFullDiskOfIdleDoesNotOccupyThenUniqueStartThenFullDiskApplies() {
        OfflinePackOperation pack = new OfflinePackOperation();
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase());
        assertNull(pack.operationId());
        assertEquals(OfflinePackOperation.Failure.NOT_DOWNLOADING,
                assertThrows(OfflinePackOperation.OfflinePackException.class, pack::recordFullDisk).code);
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase(), "未见下载 recordFullDisk 观察不得占 FULL_DISK");
        assertNull(pack.operationId());
        pack.startDownload(manifest("op-disk"), NOW);
        assertEquals("op-disk", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase());
        assertEquals(OfflinePackOperation.Failure.FULL_DISK,
                assertThrows(OfflinePackOperation.OfflinePackException.class, pack::recordFullDisk).code);
        assertEquals(OfflinePackOperation.Phase.FULL_DISK, pack.phase());
        assertEquals("op-disk", pack.operationId());
    }

    @Test
    void unusedApplyUpdateOfIdleDoesNotOccupyThenUniqueStartFinishThenUpdateApplies() {
        OfflinePackOperation pack = new OfflinePackOperation();
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase());
        assertNull(pack.operationId());
        assertEquals(OfflinePackOperation.Failure.NOT_READY,
                assertThrows(OfflinePackOperation.OfflinePackException.class,
                        () -> pack.applyUpdate(manifest("op-upd"), true, NOW)).code);
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase(), "未见下载 applyUpdate 观察不得占 UPDATING");
        assertNull(pack.operationId());
        pack.startDownload(manifest("op-upd"), NOW);
        pack.finishDownload(OfflinePackFixtures.PAYLOAD);
        assertEquals("op-upd", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        pack.applyUpdate(OfflinePackFixtures.manifest("op-upd", "v2"), true, NOW);
        assertEquals(OfflinePackOperation.Phase.UPDATING, pack.phase());
        assertEquals("op-upd", pack.operationId());
        assertEquals("v2", pack.manifest().version);
    }

    @Test
    void unusedFinishUpdateOfIdleDoesNotOccupyThenUniqueUpdateThenFinishApplies() {
        OfflinePackOperation pack = new OfflinePackOperation();
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase());
        assertNull(pack.operationId());
        assertEquals(OfflinePackOperation.Failure.NOT_DOWNLOADING,
                assertThrows(OfflinePackOperation.OfflinePackException.class, pack::finishUpdate).code);
        assertEquals(OfflinePackOperation.Phase.IDLE, pack.phase(), "未见下载 finishUpdate 观察不得占 READY-after-update");
        assertNull(pack.operationId());
        pack.startDownload(manifest("op-fin"), NOW);
        pack.finishDownload(OfflinePackFixtures.PAYLOAD);
        pack.applyUpdate(OfflinePackFixtures.manifest("op-fin", "v2"), true, NOW);
        assertEquals(OfflinePackOperation.Phase.UPDATING, pack.phase());
        pack.finishUpdate(OfflinePackFixtures.PAYLOAD);
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        assertEquals("op-fin", pack.operationId());
        assertEquals("v2", pack.manifest().version);
    }

    @Test
    void blankAuthorityIsInvalidAndBadUpdateShaRestoresKeptPrior() {
        assertEquals(OfflinePackOperation.Failure.INVALID_MANIFEST,
                assertThrows(OfflinePackOperation.OfflinePackException.class,
                        () -> new OfflinePackOperation.Manifest("  ", "op-blank", "v1",
                                OfflinePackFixtures.PAYLOAD.length, OfflinePackFixtures.CHECKSUM,
                                List.of(11), OfflinePackFixtures.EXPIRES)).code);
        assertEquals(OfflinePackOperation.Failure.INVALID_MANIFEST,
                assertThrows(OfflinePackOperation.OfflinePackException.class,
                        () -> new OfflinePackOperation.Manifest("pack-1", " \t", "v1",
                                OfflinePackFixtures.PAYLOAD.length, OfflinePackFixtures.CHECKSUM,
                                List.of(11), OfflinePackFixtures.EXPIRES)).code);

        OfflinePackOperation pack = new OfflinePackOperation();
        pack.startDownload(manifest("op-bad"), NOW);
        pack.finishDownload(OfflinePackFixtures.PAYLOAD);
        pack.applyUpdate(OfflinePackFixtures.manifest("op-bad", "v2"), true, NOW);
        byte[] wrong = OfflinePackFixtures.PAYLOAD.clone();
        wrong[0] = (byte) (wrong[0] ^ 0xff);
        assertEquals(OfflinePackFixtures.PAYLOAD.length, wrong.length);
        assertEquals(OfflinePackOperation.Failure.CORRUPT,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.finishUpdate(wrong)).code);
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        assertEquals("v1", pack.manifest().version);
        assertEquals("op-bad", pack.operationId());

        pack.applyUpdate(OfflinePackFixtures.manifest("op-bad", "v2"), false, NOW);
        assertEquals(OfflinePackOperation.Failure.CORRUPT,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.finishUpdate(wrong)).code);
        assertEquals(OfflinePackOperation.Phase.CORRUPT, pack.phase());
        assertEquals("v2", pack.manifest().version);
    }
}

final class OfflinePackFixtures {
    static final Instant NOW = Instant.parse("2026-10-23T20:00:00Z");
    static final Instant EXPIRES = Instant.parse("2026-12-01T00:00:00Z");
    static final byte[] PAYLOAD = payload(1024);
    static final String CHECKSUM = OfflinePackOperation.sha256Hex(PAYLOAD);

    static byte[] payload(int byteSize) {
        byte[] data = new byte[byteSize];
        for (int i = 0; i < byteSize; i++) {
            data[i] = (byte) (i & 0xff);
        }
        return data;
    }

    static OfflinePackOperation.Manifest manifest(String operationId) {
        return manifest(operationId, "v1");
    }

    static OfflinePackOperation.Manifest manifest(String operationId, String version) {
        return new OfflinePackOperation.Manifest("pack-1", operationId, version, PAYLOAD.length, CHECKSUM,
                List.of(11, 12), EXPIRES);
    }
}
