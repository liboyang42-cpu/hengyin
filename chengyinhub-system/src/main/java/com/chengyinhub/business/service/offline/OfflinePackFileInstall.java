package com.chengyinhub.business.service.offline;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.chengyinhub.business.service.offline.OfflinePackOperation.Failure;
import com.chengyinhub.business.service.offline.OfflinePackOperation.Manifest;
import com.chengyinhub.business.service.offline.OfflinePackOperation.OfflinePackException;

/**
 * 把已经校验过的离线包字节原子写进调用方给出的目录。
 * 没有字节、摘要不对，都不替换已有文件。这里不发起下载，也不编造地址。
 */
public final class OfflinePackFileInstall {
    private OfflinePackFileInstall() {
    }

    public static void install(OfflinePackOperation operation, byte[] payload, Path root) throws IOException {
        if (operation == null || operation.manifest() == null) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        Manifest manifest = operation.manifest();
        if (payload == null) {
            throw new OfflinePackException(Failure.WAITING_SOURCE, OfflinePackOperation.PACK_PAYLOAD_CONSUMER);
        }
        Path part = partFile(root, manifest);
        Path finalFile = packFile(root, manifest);
        Files.write(part, payload);
        byte[] stored = Files.readAllBytes(part);
        boolean same = stored.length == payload.length && stored.length == manifest.byteSize
                && manifest.checksum.equalsIgnoreCase(OfflinePackOperation.sha256Hex(stored));
        if (!same) {
            Files.deleteIfExists(part);
            finish(operation, payload);
            return;
        }
        move(part, finalFile);
        finish(operation, stored);
    }

    public static byte[] readVerified(Path root, Manifest manifest) throws IOException {
        if (manifest == null) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        Path file = packFile(root, manifest);
        if (!Files.isRegularFile(file)) {
            throw new OfflinePackException(Failure.WAITING_SOURCE, OfflinePackOperation.PACK_PAYLOAD_CONSUMER);
        }
        byte[] stored = Files.readAllBytes(file);
        if (stored.length != manifest.byteSize
                || !manifest.checksum.equalsIgnoreCase(OfflinePackOperation.sha256Hex(stored))) {
            throw new OfflinePackException(Failure.CORRUPT, null);
        }
        return stored;
    }

    private static void finish(OfflinePackOperation operation, byte[] payload) {
        if (operation.phase() == OfflinePackOperation.Phase.UPDATING) {
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

    private static Path packFile(Path root, Manifest manifest) {
        return contained(root, fileName(manifest));
    }

    private static Path partFile(Path root, Manifest manifest) {
        return contained(root, fileName(manifest) + ".part");
    }

    private static String fileName(Manifest manifest) {
        String name = manifest.packId + "-" + manifest.version + ".pack";
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        return name;
    }

    private static Path contained(Path root, String name) {
        if (root == null || !Files.isDirectory(root)) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(name).normalize();
        if (!target.startsWith(base)) {
            throw new OfflinePackException(Failure.INVALID_MANIFEST, null);
        }
        return target;
    }
}
