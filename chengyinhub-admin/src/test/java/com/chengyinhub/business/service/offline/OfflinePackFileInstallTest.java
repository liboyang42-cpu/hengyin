package com.chengyinhub.business.service.offline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflinePackFileInstallTest {
    private static final java.time.Instant NOW = OfflinePackFixtures.NOW;

    @TempDir
    Path root;

    @Test
    void missingBytesDoNotCreateAFile() throws Exception {
        OfflinePackOperation pack = downloading("op-miss");
        OfflinePackOperation.OfflinePackException waiting = assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.install(pack, null, root));
        assertEquals(OfflinePackOperation.Failure.WAITING_SOURCE, waiting.code);
        assertEquals(OfflinePackOperation.PACK_PAYLOAD_CONSUMER, waiting.capability);
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase());
        assertEquals(0, Files.list(root).count());
    }

    @Test
    void verifiedBytesInstallAtomicallyAndReadBack() throws Exception {
        OfflinePackOperation pack = downloading("op-file");
        OfflinePackFileInstall.install(pack, OfflinePackFixtures.PAYLOAD, root);
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        Path file = root.resolve(OfflinePackFileInstall.fileName(pack.manifest()));
        assertTrue(Files.isRegularFile(file));
        assertEquals(0, Files.list(root).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
        byte[] read = OfflinePackFileInstall.readVerified(root, pack.manifest());
        assertArrayEquals(OfflinePackFixtures.PAYLOAD, read);

        OfflinePackOperation restarted = downloading("op-file");
        byte[] recovered = OfflinePackFileInstall.readVerified(root, restarted.manifest());
        restarted.finishDownload(recovered);
        assertEquals(OfflinePackOperation.Phase.READY, restarted.phase());
    }

    @Test
    void badUpdateLeavesThePriorFile() throws Exception {
        OfflinePackOperation pack = downloading("op-keep");
        OfflinePackFileInstall.install(pack, OfflinePackFixtures.PAYLOAD, root);
        pack.applyUpdate(OfflinePackFixtures.manifest("op-keep", "v2"), true, NOW);
        byte[] wrong = OfflinePackFixtures.PAYLOAD.clone();
        wrong[0] = (byte) (wrong[0] ^ 0xff);
        assertEquals(OfflinePackOperation.Failure.CORRUPT, assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.install(pack, wrong, root)).code);
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        assertEquals("v1", pack.manifest().version);
        assertTrue(Files.isRegularFile(root.resolve(OfflinePackFileInstall.fileName(
                OfflinePackFixtures.manifest("op-keep", "v1")))));
        assertFalse(Files.exists(root.resolve(OfflinePackFileInstall.fileName(
                OfflinePackFixtures.manifest("op-keep", "v2")))));
        assertArrayEquals(OfflinePackFixtures.PAYLOAD,
                OfflinePackFileInstall.readVerified(root, OfflinePackFixtures.manifest("op-keep", "v1")));
    }

    @Test
    void changedFileIsNotAVerifiedReadAndOutsideNameIsRejected() throws Exception {
        OfflinePackOperation pack = downloading("op-file");
        OfflinePackFileInstall.install(pack, OfflinePackFixtures.PAYLOAD, root);
        Files.write(root.resolve(OfflinePackFileInstall.fileName(pack.manifest())), new byte[] {1, 2, 3});
        assertEquals(OfflinePackOperation.Failure.CORRUPT, assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.readVerified(root, pack.manifest())).code);
        OfflinePackOperation.Manifest escaped = new OfflinePackOperation.Manifest(
                "pack-1", "op-esc", "../x", OfflinePackFixtures.PAYLOAD.length, OfflinePackFixtures.CHECKSUM,
                java.util.List.of(11), OfflinePackFixtures.EXPIRES);
        OfflinePackOperation outsider = new OfflinePackOperation();
        outsider.startDownload(escaped, NOW);
        assertEquals(OfflinePackOperation.Failure.INVALID_MANIFEST, assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.install(outsider, OfflinePackFixtures.PAYLOAD, root)).code);
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, outsider.phase());
    }

    @Test
    void wrongPhaseDoesNotCreateAFile() throws Exception {
        OfflinePackOperation paused = downloading("op-pause");
        paused.pause();
        assertEquals(OfflinePackOperation.Failure.NOT_DOWNLOADING, assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.install(paused, OfflinePackFixtures.PAYLOAD, root)).code);
        assertEquals(0, Files.list(root).count());

        OfflinePackOperation ready = downloading("op-ready");
        ready.finishDownload(OfflinePackFixtures.PAYLOAD);
        assertEquals(OfflinePackOperation.Failure.NOT_DOWNLOADING, assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.install(ready, OfflinePackFixtures.PAYLOAD, root)).code);
        OfflinePackOperation deleted = downloading("op-deleted");
        deleted.delete();
        assertEquals(OfflinePackOperation.Failure.NOT_DOWNLOADING, assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.install(deleted, OfflinePackFixtures.PAYLOAD, root)).code);
        OfflinePackOperation expired = downloading("op-expired");
        expired.finishDownload(OfflinePackFixtures.PAYLOAD);
        expired.expire(OfflinePackFixtures.EXPIRES);
        assertEquals(OfflinePackOperation.Failure.NOT_DOWNLOADING, assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.install(expired, OfflinePackFixtures.PAYLOAD, root)).code);
        assertEquals(0, Files.list(root).count());
    }

    @Test
    void differentPackIdentitiesDoNotShareAFileName() {
        OfflinePackOperation.Manifest left = new OfflinePackOperation.Manifest(
                "pack-a", "op-left", "b", OfflinePackFixtures.PAYLOAD.length, OfflinePackFixtures.CHECKSUM,
                java.util.List.of(11), OfflinePackFixtures.EXPIRES);
        OfflinePackOperation.Manifest right = new OfflinePackOperation.Manifest(
                "pack", "op-right", "a-b", OfflinePackFixtures.PAYLOAD.length, OfflinePackFixtures.CHECKSUM,
                java.util.List.of(11), OfflinePackFixtures.EXPIRES);
        assertFalse(OfflinePackFileInstall.fileName(left).equals(OfflinePackFileInstall.fileName(right)));
    }

    @Test
    void symlinkTargetIsNotReplaced() throws Exception {
        Path sentinel = root.resolve("sentinel");
        Files.write(sentinel, new byte[] {'k'});
        OfflinePackOperation linked = downloading("op-link");
        Path link = root.resolve(OfflinePackFileInstall.fileName(linked.manifest()));
        Files.createSymbolicLink(link, sentinel);
        assertEquals(OfflinePackOperation.Failure.INVALID_MANIFEST, assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackFileInstall.install(linked, OfflinePackFixtures.PAYLOAD, root)).code);
        assertArrayEquals(new byte[] {'k'}, Files.readAllBytes(sentinel));
        assertTrue(Files.isSymbolicLink(link));
    }

    private static OfflinePackOperation downloading(String operationId) {
        OfflinePackOperation pack = new OfflinePackOperation();
        pack.startDownload(OfflinePackFixtures.manifest(operationId), NOW);
        return pack;
    }
}
