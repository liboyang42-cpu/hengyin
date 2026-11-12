package com.chengyinhub.business.service.offline;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * C05 独占：离线包 download/pause/resume-same-operation/corrupt/full-disk/update-keep-old/expire/delete。
 * 答案与隐藏坐标不得下发。T41 完整闭环未证。不碰 20 个原暂停操作。
 */
public final class OfflinePackOperation {

    public static final String QMOD_FINAL_OPS = "qmod.finalOpsContract";
    public static final String VOL12_RESUME_WRITER = "vol12.offlinePackResumeWriter";
    public static final String T41_SCORING_WRITER = "t41.scoringWriter";
    public static final String T41_FULL_LOOP = "t41.fullLoopUnproven";
    public static final String ORIGINAL_PAUSE_OPS_OWNER = "PlayExperienceCoordinator.paused";
    public static final String PACK_PAYLOAD_CONSUMER = "offlinePack.payloadConsumer";

    public enum Failure {
        WAITING_SOURCE,
        ANSWERS_OR_HIDDEN_COORDINATES_FORBIDDEN,
        CORRUPT,
        FULL_DISK,
        EXPIRED,
        OPERATION_MISMATCH,
        NOT_PAUSED,
        NOT_DOWNLOADING,
        NOT_READY,
        ALREADY_DELETED,
        INVALID_MANIFEST,
        PAUSE_OPS_RESERVED
    }

    public enum Phase {
        IDLE, DOWNLOADING, PAUSED, READY, UPDATING, CORRUPT, FULL_DISK, EXPIRED, DELETED, WAITING_SOURCE
    }

    public static boolean qmodFinalOpsPresent() {
        return false;
    }

    public static boolean vol12ResumeWriterPresent() {
        return false;
    }

    public static boolean t41ScoringWriterPresent() {
        return false;
    }

    public static boolean t41FullLoopProven() {
        return false;
    }

    private Phase phase = Phase.IDLE;
    private String operationId;
    private Manifest manifest;
    private Manifest retainedPrior;
    private Failure failure;
    private String waitingCapability;

    public Phase phase() {
        return phase;
    }

    public String operationId() {
        return operationId;
    }

    public Manifest manifest() {
        return manifest;
    }

    public Manifest retainedPrior() {
        return retainedPrior;
    }

    public Failure failure() {
        return failure;
    }

    public String waitingCapability() {
        return waitingCapability;
    }

    public static void inspect(Map<String, String> object) {
        if (object == null) {
            throw failStatic(Failure.INVALID_MANIFEST);
        }
        Set<String> forbidden = Set.of("answer", "answers", "hiddenCoordinate", "hiddenCoordinates",
                "hiddenLat", "hiddenLng", "hiddenLatitude", "hiddenLongitude", "spoilerAnswer");
        for (String key : object.keySet()) {
            if (forbidden.contains(key)) {
                throw failStatic(Failure.ANSWERS_OR_HIDDEN_COORDINATES_FORBIDDEN);
            }
        }
        for (String value : object.values()) {
            if (value == null) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("hidden-coord") || lower.contains("\"answer\"")) {
                throw failStatic(Failure.ANSWERS_OR_HIDDEN_COORDINATES_FORBIDDEN);
            }
        }
    }

    public static Manifest decodeManifest(Map<String, String> object, List<Integer> publicPlaceIds,
            Instant expiresAt) {
        inspect(object);
        try {
            int byteSize = Integer.parseInt(object.get("byteSize"));
            return new Manifest(object.get("packID"), object.get("operationID"), object.get("version"),
                    byteSize, object.get("checksum"), publicPlaceIds, expiresAt);
        } catch (RuntimeException ex) {
            throw failStatic(Failure.INVALID_MANIFEST);
        }
    }

    public void startDownload(Manifest next, Instant now) {
        inspect(Map.of("packID", next.packId, "operationID", next.operationId, "version", next.version,
                "checksum", next.checksum, "byteSize", String.valueOf(next.byteSize)));
        if (phase != Phase.IDLE && phase != Phase.DELETED && phase != Phase.EXPIRED) {
            throw fail(Failure.INVALID_MANIFEST);
        }
        if (!next.expiresAt.isAfter(now)) {
            throw fail(Failure.EXPIRED);
        }
        operationId = next.operationId;
        manifest = next;
        retainedPrior = null;
        phase = Phase.DOWNLOADING;
        failure = null;
        waitingCapability = null;
    }

    public void pause() {
        if (phase != Phase.DOWNLOADING) {
            throw fail(Failure.NOT_DOWNLOADING);
        }
        phase = Phase.PAUSED;
        failure = null;
    }

    public void resume(String sameOperationId) {
        if (phase != Phase.PAUSED) {
            throw fail(Failure.NOT_PAUSED);
        }
        if (sameOperationId == null || sameOperationId.isEmpty() || !sameOperationId.equals(operationId)) {
            throw fail(Failure.OPERATION_MISMATCH);
        }
        phase = Phase.DOWNLOADING;
        failure = null;
    }

    public void finishDownload() {
        if (phase != Phase.DOWNLOADING) {
            throw fail(Failure.NOT_DOWNLOADING);
        }
        throw new OfflinePackException(Failure.WAITING_SOURCE, PACK_PAYLOAD_CONSUMER);
    }

    public void finishDownload(byte[] payload) {
        if (phase != Phase.DOWNLOADING) {
            throw fail(Failure.NOT_DOWNLOADING);
        }
        if (payload == null) {
            throw waiting(PACK_PAYLOAD_CONSUMER);
        }
        verifyPayload(payload);
        phase = Phase.READY;
        failure = null;
    }

    public void recordCorrupt() {
        if (phase != Phase.DOWNLOADING && phase != Phase.UPDATING && phase != Phase.READY) {
            throw fail(Failure.INVALID_MANIFEST);
        }
        if (retainedPrior != null && phase == Phase.UPDATING) {
            restorePrior(retainedPrior);
            return;
        }
        phase = Phase.CORRUPT;
        failure = Failure.CORRUPT;
        throw failStatic(Failure.CORRUPT);
    }

    public void recordFullDisk() {
        if (phase != Phase.DOWNLOADING && phase != Phase.UPDATING) {
            throw fail(Failure.NOT_DOWNLOADING);
        }
        if (retainedPrior != null && phase == Phase.UPDATING) {
            restorePrior(retainedPrior);
            return;
        }
        phase = Phase.FULL_DISK;
        failure = Failure.FULL_DISK;
        throw failStatic(Failure.FULL_DISK);
    }

    public void applyUpdate(Manifest next, boolean keepOld, Instant now) {
        if (phase != Phase.READY || manifest == null) {
            throw fail(Failure.NOT_READY);
        }
        if (!next.operationId.equals(manifest.operationId) || !next.packId.equals(manifest.packId)) {
            throw fail(Failure.OPERATION_MISMATCH);
        }
        if (!next.expiresAt.isAfter(now)) {
            throw fail(Failure.EXPIRED);
        }
        inspect(Map.of("packID", next.packId, "operationID", next.operationId, "version", next.version,
                "checksum", next.checksum));
        retainedPrior = keepOld ? manifest : null;
        manifest = next;
        phase = Phase.UPDATING;
        failure = null;
    }

    public void finishUpdate() {
        if (phase != Phase.UPDATING) {
            throw fail(Failure.NOT_DOWNLOADING);
        }
        throw new OfflinePackException(Failure.WAITING_SOURCE, PACK_PAYLOAD_CONSUMER);
    }

    public void finishUpdate(byte[] payload) {
        if (phase != Phase.UPDATING) {
            throw fail(Failure.NOT_DOWNLOADING);
        }
        if (payload == null) {
            throw waiting(PACK_PAYLOAD_CONSUMER);
        }
        verifyPayload(payload);
        phase = Phase.READY;
        failure = null;
    }

    public void expire(Instant now) {
        if (manifest == null) {
            throw fail(Failure.INVALID_MANIFEST);
        }
        if (now == null || now.isBefore(manifest.expiresAt)) {
            throw fail(Failure.INVALID_MANIFEST);
        }
        phase = Phase.EXPIRED;
        failure = Failure.EXPIRED;
    }

    public void delete() {
        if (phase == Phase.DELETED) {
            throw fail(Failure.ALREADY_DELETED);
        }
        if (phase == Phase.IDLE) {
            throw fail(Failure.INVALID_MANIFEST);
        }
        phase = Phase.DELETED;
        retainedPrior = null;
        failure = null;
    }

    public void commitOriginalWriter() {
        throw waiting(QMOD_FINAL_OPS);
    }

    public void scoreT41() {
        throw waiting(T41_SCORING_WRITER);
    }

    public void proveT41FullLoop() {
        throw waiting(T41_FULL_LOOP);
    }

    public void resumeViaVol12Writer() {
        throw waiting(VOL12_RESUME_WRITER);
    }

    public void hijackOriginalPauseOps() {
        throw fail(Failure.PAUSE_OPS_RESERVED);
    }

    private void verifyPayload(byte[] payload) {
        if (manifest == null || payload.length != manifest.byteSize
                || !manifest.checksum.equalsIgnoreCase(sha256Hex(payload))) {
            if (retainedPrior != null && phase == Phase.UPDATING) {
                restorePrior(retainedPrior);
                throw failStatic(Failure.CORRUPT);
            }
            phase = Phase.CORRUPT;
            failure = Failure.CORRUPT;
            throw failStatic(Failure.CORRUPT);
        }
    }

    static String sha256Hex(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw failStatic(Failure.INVALID_MANIFEST);
        }
    }

    private void restorePrior(Manifest prior) {
        manifest = prior;
        phase = Phase.READY;
        failure = null;
    }

    private OfflinePackException fail(Failure code) {
        failure = code;
        if (code == Failure.WAITING_SOURCE) {
            phase = Phase.WAITING_SOURCE;
        }
        return failStatic(code);
    }

    private OfflinePackException waiting(String capability) {
        waitingCapability = capability;
        phase = Phase.WAITING_SOURCE;
        failure = Failure.WAITING_SOURCE;
        return new OfflinePackException(Failure.WAITING_SOURCE, capability);
    }

    private static OfflinePackException failStatic(Failure code) {
        return new OfflinePackException(code, null);
    }

    public static final class OfflinePackException extends IllegalStateException {
        public final Failure code;
        public final String capability;

        OfflinePackException(Failure code, String capability) {
            super(capability == null ? code.name() : "WAITING_SOURCE:" + capability);
            this.code = code;
            this.capability = capability;
        }
    }

    public static final class Manifest {
        public final String packId;
        public final String operationId;
        public final String version;
        public final int byteSize;
        public final String checksum;
        public final List<Integer> publicPlaceIds;
        public final Instant expiresAt;

        public Manifest(String packId, String operationId, String version, int byteSize, String checksum,
                List<Integer> publicPlaceIds, Instant expiresAt) {
            if (isBlank(packId) || packId.length() > 64 || isBlank(operationId) || operationId.length() > 64
                    || !versionOk(version) || byteSize <= 0 || byteSize > 500_000_000 || checksum == null
                    || checksum.length() != 64 || !checksum.chars().allMatch(OfflinePackOperation::isHex)
                    || publicPlaceIds == null || publicPlaceIds.size() > 256 || expiresAt == null) {
                throw failStatic(Failure.INVALID_MANIFEST);
            }
            Set<Integer> unique = new LinkedHashSet<Integer>();
            for (Integer place : publicPlaceIds) {
                if (place == null || place.intValue() <= 0 || !unique.add(place)) {
                    throw failStatic(Failure.INVALID_MANIFEST);
                }
            }
            this.packId = packId;
            this.operationId = operationId;
            this.version = version;
            this.byteSize = byteSize;
            this.checksum = checksum;
            this.publicPlaceIds = Collections.unmodifiableList(new ArrayList<Integer>(publicPlaceIds));
            this.expiresAt = expiresAt;
        }
    }

    static boolean versionOk(String value) {
        return value != null && !value.isEmpty() && value.length() <= 64 && value.equals(value.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isHex(int ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }
}
