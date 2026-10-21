package com.chengyinhub.business.service.rendezvous;

/**
 * Fail-closed ports for W16.4 safe exit. No production binding.
 * Field names match {@code RendezvousConsentService.OngoingVerdict} at {@code 37b4b345}
 * so an adapter copies them straight across. This tree does not contain that type.
 * A null answer fails closed. J never sends BLOCK, REPORT, or a non-null peer.
 */
public final class RendezvousSafeExitPorts {
    private RendezvousSafeExitPorts() {}

    public enum Phase { WAITING, ONE_SIDED, MUTUAL, TERMINAL, ABSENT }

    /** Mirror of A's OngoingVerdict. No partner id, clue, place, or contact count. */
    public static final class OngoingVerdict {
        public final boolean safe, intact, expired, eligible, blocked, placeAllowed, owned, mutual;
        public final String ticketId, matchId;
        public final RendezvousConsentService.TicketState ticketState;
        public final RendezvousConsentService.MatchState matchState;
        public OngoingVerdict(boolean safe, boolean intact, boolean expired, boolean eligible, boolean blocked,
                              boolean placeAllowed, boolean owned, boolean mutual, String ticketId, String matchId,
                              RendezvousConsentService.TicketState ticketState, RendezvousConsentService.MatchState matchState) {
            this.safe = safe; this.intact = intact; this.expired = expired; this.eligible = eligible;
            this.blocked = blocked; this.placeAllowed = placeAllowed; this.owned = owned; this.mutual = mutual;
            this.ticketId = ticketId; this.matchId = matchId; this.ticketState = ticketState; this.matchState = matchState;
        }
    }

    /** Same names as A's {@code RendezvousConsentService.Termination}. J calls only EXIT. */
    public enum Termination { BLOCK, REPORT, EXIT }

    /**
     * A, inside the caller's already-open consent guard transaction.
     * Maps to {@code verifyParticipants(tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now)}.
     * J passes a null match id, a null peer, and {@code requireMutualAccepted=false}.
     */
    public interface ParticipantVerification {
        OngoingVerdict verifyParticipants(RendezvousConsentService.Transaction tx, long actor, String ticketId,
                                          String expectedMatchId, Long expectedPeer, boolean requireMutualAccepted, long now);
    }

    /**
     * A, same open transaction. Maps to
     * {@code terminateCurrent(tx, actor, peer, ticketId, reason, now)}.
     * J calls {@code terminateCurrent(tx, actor, null, ticketId, Termination.EXIT, now)}.
     */
    public interface CurrentTermination {
        OngoingVerdict terminateCurrent(RendezvousConsentService.Transaction tx, long actor, Long peer, String ticketId,
                                        Termination reason, long now);
    }

    /**
     * C's frozen read-only query, mirrored here because this tree's
     * {@code RendezvousMeetingService} does not yet declare it:
     * {@code RendezvousMeetingService.HandoffState handoffStateFor(String matchId)}.
     * No transaction argument, no side effects, no new request id.
     * Solo may continue only when the result is {@code NOT_STARTED}.
     */
    public enum HandoffState { NOT_STARTED, UNKNOWN, CREATED_JOIN_UNCONFIRMED, COMPLETE }

    public interface HandoffStateQuery {
        HandoffState handoffStateFor(String matchId);
    }

    /** Published goals explicitly allowed as solo. Null means none. Team activity id is not enough. */
    public interface SoloGoalCatalog {
        String publishedSoloGoal(RendezvousConsentService.Transaction tx, long actor, String matchId);
    }

    /** Authoritative one-actor launcher. A blank receipt is not a start. */
    public interface SoloLauncher {
        LaunchReceipt startOrResume(long actor, String goalRef, String attemptId);
    }

    public static final class LaunchReceipt {
        public final String receiptId;
        public final boolean resumed;
        public LaunchReceipt(String receiptId, boolean resumed) {
            this.receiptId = receiptId;
            this.resumed = resumed;
        }
    }

    /** Authoritative result. Anything other than COMPLETED is not completion. */
    public interface SoloResult {
        enum Status { RUNNING, COMPLETED, NOT_FOUND }
        Status read(String receiptId);
    }
}
