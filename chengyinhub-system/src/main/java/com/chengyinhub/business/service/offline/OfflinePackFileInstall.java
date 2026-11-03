package com.chengyinhub.business.service.offline;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.chengyinhub.business.service.offline.OfflinePackOperation.Failure;
import com.chengyinhub.business.service.offline.OfflinePackOperation.Manifest;
import com.chengyinhub.business.service.offline.OfflinePackOperation.OfflinePackException;
import com.chengyinhub.business.service.offline.OfflinePackOperation.Phase;

/**
 * 把已经校验过的离线包字节原子写进调用方给出的目录。
 * 阶段不对、摘要不对、或目标是链接时，都不改已有文件。这里不发起下载。
 */
public final class OfflinePackFileInstall {
    private OfflinePackFileInstall() {
    }

    public static void install(OfflinePackOperation operation, byte[] payload, Path root) throws IOException {
        if (operation == null || operation.manifest() == null) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        Phase phase = operation.phase();
        if (phase != Phase.DOWNLOADING && phase != Phase.UPDATING) {
            throw new OfflinePackException(Failure.NOT_DOWNLOADING, null);
        }
        if (payload == null) {
            throw new OfflinePackException(Failure.WAITING_SOURCE, OfflinePackOperation.PACK_PAYLOAD_CONSUMER);
        }
        Manifest manifest = operation.manifest();
        Path base = directory(root);
        Path finalFile = base.resolve(fileName(manifest)).normalize();
        if (!finalFile.startsWith(base) || Files.isSymbolicLink(finalFile)) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        Path temp = Files.createTempFile(base, ".offline-part-", ".tmp");
        boolean keepTemp = true;
        try {
            if (Files.isSymbolicLink(temp)) {
                throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
            }
            Files.write(temp, payload);
            byte[] stored = Files.readAllBytes(temp);
            boolean same = stored.length == payload.length && stored.length == manifest.byteSize
                    && manifest.checksum.equalsIgnoreCase(OfflinePackOperation.sha256Hex(stored));
            requireSameOperation(operation, phase, manifest);
            if (!same) {
                finish(operation, payload);
                return;
            }
            if (Files.isSymbolicLink(finalFile)) {
                throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
            }
            requireSameOperation(operation, phase, manifest);
            move(temp, finalFile);
            keepTemp = false;
            finish(operation, stored);
        } finally {
            if (keepTemp) {
                Files.deleteIfExists(temp);
            }
        }
    }

    public static byte[] readVerified(Path root, Manifest manifest) throws IOException {
        if (manifest == null) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        Path file = directory(root).resolve(fileName(manifest)).normalize();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new OfflinePackException(Failure.WAITING_SOURCE, OfflinePackOperation.PACK_PAYLOAD_CONSUMER);
        }
        byte[] stored = Files.readAllBytes(file);
        if (stored.length != manifest.byteSize
                || !manifest.checksum.equalsIgnoreCase(OfflinePackOperation.sha256Hex(stored))) {
            throw new OfflinePackException(Failure.CORRUPT, null);
        }
        return stored;
    }

    static String fileName(Manifest manifest) {
        return bind(manifest.operationId) + bind(manifest.packId) + bind(manifest.version) + "pack";
    }

    private static String bind(String value) {
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.contains("..")) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        return value.length() + "." + value + ".";
    }

    private static void requireSameOperation(OfflinePackOperation operation, Phase phase, Manifest manifest) {
        Manifest current = operation.manifest();
        if (operation.phase() != phase || current == null
                || !manifest.operationId.equals(current.operationId)
                || !manifest.packId.equals(current.packId)
                || !manifest.version.equals(current.version)) {
            throw new OfflinePackException(Failure.OPERATION_MISMATCH, null);
        }
    }

    private static void finish(OfflinePackOperation operation, byte[] payload) {
        if (operation.phase() == Phase.UPDATING) {
            operation.finishUpdate(payload);
            return;
        }
        operation.finishDownload(payload);
    }

    private static void move(Path part, Path finalFile) throws IOException {
        try {
            Files.move(part, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(part, finalFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path directory(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        return root.toAbsolutePath().normalize();
    }
}
