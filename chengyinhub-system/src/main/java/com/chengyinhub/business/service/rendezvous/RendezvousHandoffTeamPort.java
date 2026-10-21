package com.chengyinhub.business.service.rendezvous;

/**
 * Internal team port owned by the handoff ledger. This is not {@code IPlayTeamService}.
 * {@link #lookup} must not create or join. {@link #create} and {@link #join} are idempotent
 * on the persisted request id. A port that cannot prove a result returns {@link Status#UNKNOWN}
 * and must not be treated as a successful team.
 */
public interface RendezvousHandoffTeamPort {
    boolean available();

    Lookup lookup(String requestId);

    Created create(String requestId, long leader, long member, long activityId);

    void join(String requestId);

    enum Status { ABSENT, TEAM_CREATED, JOINED, UNKNOWN }

    final class Lookup {
        public final Status status;
        public final Long teamId;
        public final String blocker;

        private Lookup(Status status, Long teamId, String blocker) {
            this.status = status;
            this.teamId = teamId;
            this.blocker = blocker;
        }

        public static Lookup absent() { return new Lookup(Status.ABSENT, null, null); }
        public static Lookup created(long teamId) { return new Lookup(Status.TEAM_CREATED, teamId, null); }
        public static Lookup joined(long teamId) { return new Lookup(Status.JOINED, teamId, null); }
        public static Lookup unknown(String blocker) { return new Lookup(Status.UNKNOWN, null, blocker); }
    }

    final class Created {
        public final long teamId;
        public Created(long teamId) { this.teamId = teamId; }
    }

    /** The call returned without a confirmed outcome. {@code teamId} is set only when that id is already known. */
    final class Unconfirmed extends RuntimeException {
        public final Long teamId;
        public Unconfirmed(Long teamId) { this.teamId = teamId; }
    }
}
