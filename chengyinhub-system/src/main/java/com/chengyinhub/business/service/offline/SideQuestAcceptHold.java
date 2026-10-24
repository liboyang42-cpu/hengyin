package com.chengyinhub.business.service.offline;

/**
 * C05 独占：独立支线 发现合规地点 → 容量预占 → 到场证明 → 完成/失败/退出 → 原记录恢复。
 * 迷雾与模拟坐标永远不是到场。缺 T41 计分 writer 标 WAITING_SOURCE，不重写。
 */
public final class SideQuestAcceptHold {

    public enum Failure {
        WAITING_SOURCE,
        NOT_COMPLIANT,
        HIDDEN_COORDINATE_SHIPPED,
        CAPACITY_UNAVAILABLE,
        HOLD_REQUIRED,
        PROOF_REQUIRED,
        FAKE_GPS_REJECTED,
        FOG_IS_NOT_PRESENCE,
        NOT_HELD,
        ALREADY_TERMINAL,
        ORIGINAL_RECORD_UNKNOWN,
        STALE_HOLD
    }

    public enum Phase {
        IDLE, DISCOVERED, HOLD_ACCEPTED, PROOF_PENDING, COMPLETED, FAILED, EXITED, RECOVERED, WAITING_SOURCE
    }

    public enum ProofKind {
        GPS, QR, PHOTO, AR, APPROVED_ALTERNATIVE
    }

    public enum Terminal {
        COMPLETED, FAILED, EXITED
    }

    public static final String PLACE_CAPACITY_SOURCE = "sideQuest.placeCapacitySource";
    public static final String APPROVED_ALTERNATIVE_AUTHORITY = "sideQuest.approvedAlternativeAuthority";

    private Phase phase = Phase.IDLE;
    private Place place;
    private CapacityHold hold;
    private Proof proof;
    private OriginalRecord original;
    private Failure failure;
    private String waitingCapability;
    private final PlaceCapacitySource capacitySource;

    public SideQuestAcceptHold() {
        this(null);
    }

    public SideQuestAcceptHold(PlaceCapacitySource capacitySource) {
        this.capacitySource = capacitySource;
    }

    public Phase phase() {
        return phase;
    }

    public CapacityHold hold() {
        return hold;
    }

    public OriginalRecord original() {
        return original;
    }

    public Failure failure() {
        return failure;
    }

    public void discover(Place next) {
        if (original != null) {
            throw fail(Failure.ALREADY_TERMINAL);
        }
        if (!next.compliant) {
            throw fail(Failure.NOT_COMPLIANT);
        }
        if (next.hiddenCoordinateShipped) {
            throw fail(Failure.HIDDEN_COORDINATE_SHIPPED);
        }
        if (next.capacity <= 0) {
            throw fail(Failure.CAPACITY_UNAVAILABLE);
        }
        place = next;
        hold = null;
        proof = null;
        original = null;
        phase = Phase.DISCOVERED;
        failure = null;
        waitingCapability = null;
    }

    public void acceptHold(String holdId, String originalRequestId, int seats) {
        if (phase != Phase.DISCOVERED || place == null) {
            throw fail(Failure.NOT_COMPLIANT);
        }
        if (isBlank(holdId) || holdId.length() > 64 || isBlank(originalRequestId) || originalRequestId.length() > 64
                || seats <= 0 || seats > place.capacity) {
            throw fail(Failure.CAPACITY_UNAVAILABLE);
        }
        if (capacitySource == null) {
            throw new SideQuestException(Failure.WAITING_SOURCE, PLACE_CAPACITY_SOURCE);
        }
        if (!capacitySource.reserve(originalRequestId, place.placeId, seats)) {
            throw fail(Failure.CAPACITY_UNAVAILABLE);
        }
        hold = new CapacityHold(holdId, originalRequestId, place.placeId, place.questId, seats);
        phase = Phase.HOLD_ACCEPTED;
        failure = null;
    }

    public void submitProof(Proof next) {
        if ((phase != Phase.HOLD_ACCEPTED && phase != Phase.PROOF_PENDING) || hold == null) {
            throw fail(Failure.HOLD_REQUIRED);
        }
        proof = next;
        phase = Phase.PROOF_PENDING;
        failure = null;
    }

    public void rejectSimulatedGps() {
        throw fail(Failure.FAKE_GPS_REJECTED);
    }

    public void rejectFogAsPresence() {
        throw fail(Failure.FOG_IS_NOT_PRESENCE);
    }

    public void complete() {
        finish(Terminal.COMPLETED, true);
    }

    public void failQuest() {
        finish(Terminal.FAILED, true);
    }

    public void exit() {
        if (phase != Phase.HOLD_ACCEPTED && phase != Phase.PROOF_PENDING && phase != Phase.DISCOVERED) {
            throw fail(Failure.ALREADY_TERMINAL);
        }
        finish(Terminal.EXITED, false);
    }

    public OriginalRecord recoverOriginal(String originalRequestId) {
        if (original == null) {
            throw fail(Failure.ORIGINAL_RECORD_UNKNOWN);
        }
        if (!original.originalRequestId.equals(originalRequestId)) {
            throw fail(Failure.STALE_HOLD);
        }
        phase = Phase.RECOVERED;
        failure = null;
        return original;
    }

    public void commitScoringWriter() {
        waitingCapability = OfflinePackOperation.T41_SCORING_WRITER;
        phase = Phase.WAITING_SOURCE;
        failure = Failure.WAITING_SOURCE;
        throw new SideQuestException(Failure.WAITING_SOURCE, OfflinePackOperation.T41_SCORING_WRITER);
    }

    public String waitingCapability() {
        return waitingCapability;
    }

    private void finish(Terminal terminal, boolean proofRequired) {
        if (phase != Phase.HOLD_ACCEPTED && phase != Phase.PROOF_PENDING) {
            throw fail(Failure.HOLD_REQUIRED);
        }
        if (hold == null) {
            throw fail(Failure.NOT_HELD);
        }
        if (proofRequired && proof == null) {
            throw fail(Failure.PROOF_REQUIRED);
        }
        original = new OriginalRecord(hold.originalRequestId, terminal, proof == null ? null : proof.kind);
        if (capacitySource != null) {
            capacitySource.release(hold.originalRequestId);
        }
        switch (terminal) {
            case COMPLETED:
                phase = Phase.COMPLETED;
                break;
            case FAILED:
                phase = Phase.FAILED;
                break;
            case EXITED:
                phase = Phase.EXITED;
                break;
            default:
                break;
        }
        failure = null;
    }

    private SideQuestException fail(Failure code) {
        failure = code;
        if (code == Failure.WAITING_SOURCE) {
            phase = Phase.WAITING_SOURCE;
        }
        return new SideQuestException(code, null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public interface PlaceCapacitySource {
        boolean reserve(String originalRequestId, int placeId, int seats);

        void release(String originalRequestId);
    }

    public static final class Place {
        public final int placeId;
        public final int questId;
        public final boolean compliant;
        public final int capacity;
        public final boolean hiddenCoordinateShipped;

        public Place(int placeId, int questId, boolean compliant, int capacity, boolean hiddenCoordinateShipped) {
            if (placeId <= 0 || questId <= 0 || capacity < 0 || capacity > 10_000) {
                throw new SideQuestException(Failure.NOT_COMPLIANT, null);
            }
            this.placeId = placeId;
            this.questId = questId;
            this.compliant = compliant;
            this.capacity = capacity;
            this.hiddenCoordinateShipped = hiddenCoordinateShipped;
        }
    }

    public static final class CapacityHold {
        public final String holdId;
        public final String originalRequestId;
        public final int placeId;
        public final int questId;
        public final int seats;

        CapacityHold(String holdId, String originalRequestId, int placeId, int questId, int seats) {
            this.holdId = holdId;
            this.originalRequestId = originalRequestId;
            this.placeId = placeId;
            this.questId = questId;
            this.seats = seats;
        }
    }

    public static final class Proof {
        public final ProofKind kind;
        public final boolean attested;

        public static Proof create(ProofKind kind, boolean attested, boolean simulated, String fogTileKey) {
            return create(kind, attested, simulated, fogTileKey, null);
        }

        public static Proof create(ProofKind kind, boolean attested, boolean simulated, String fogTileKey,
                String approvalAuthority) {
            if (simulated || fogTileKey != null) {
                return null;
            }
            if (kind == ProofKind.GPS && !attested) {
                return null;
            }
            if (kind == ProofKind.APPROVED_ALTERNATIVE) {
                if (!attested || approvalAuthority == null || approvalAuthority.isBlank()) {
                    return null;
                }
                return new Proof(kind, true);
            }
            if (!attested) {
                return null;
            }
            return new Proof(kind, true);
        }

        private Proof(ProofKind kind, boolean attested) {
            this.kind = kind;
            this.attested = attested;
        }
    }

    public static final class OriginalRecord {
        public final String originalRequestId;
        public final Terminal terminal;
        public final ProofKind proofKind;

        OriginalRecord(String originalRequestId, Terminal terminal, ProofKind proofKind) {
            this.originalRequestId = originalRequestId;
            this.terminal = terminal;
            this.proofKind = proofKind;
        }
    }

    public static final class SideQuestException extends IllegalStateException {
        public final Failure code;
        public final String capability;

        SideQuestException(Failure code, String capability) {
            super(capability == null ? code.name() : "WAITING_SOURCE:" + capability);
            this.code = code;
            this.capability = capability;
        }
    }
}
