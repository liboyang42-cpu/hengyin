package com.chengyinhub.business.service.rendezvous;

/** One durable create identity for a match. Not a team-table row and not a client token. */
public final class RendezvousHandoffRequest {
    public enum Phase { UNKNOWN, TEAM_CREATED, COMPLETED }

    public final String requestId;
    public final String matchId;
    public final long firstActor;
    public final long secondActor;
    public final long activityId;
    public final Phase phase;
    public final Long teamId;
    public final String blocker;
    public final boolean dispatched;
    public final long createdAt;
    public final long updatedAt;

    public RendezvousHandoffRequest(String requestId, String matchId, long firstActor, long secondActor,
            long activityId, Phase phase, Long teamId, String blocker, boolean dispatched,
            long createdAt, long updatedAt) {
        this.requestId = requestId;
        this.matchId = matchId;
        this.firstActor = firstActor;
        this.secondActor = secondActor;
        this.activityId = activityId;
        this.phase = phase;
        this.teamId = teamId;
        this.blocker = blocker;
        this.dispatched = dispatched;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public int getDispatchedBit() { return dispatched ? 1 : 0; }

    public RendezvousHandoffRequest blocker(String code, long now) {
        return new RendezvousHandoffRequest(requestId, matchId, firstActor, secondActor, activityId,
                phase, teamId, code, dispatched, createdAt, now);
    }

    public RendezvousHandoffRequest dispatched(long now) {
        return new RendezvousHandoffRequest(requestId, matchId, firstActor, secondActor, activityId,
                phase, teamId, blocker, true, createdAt, now);
    }

    public RendezvousHandoffRequest teamCreated(long team, long now) {
        return new RendezvousHandoffRequest(requestId, matchId, firstActor, secondActor, activityId,
                Phase.TEAM_CREATED, team, null, true, createdAt, now);
    }

    public RendezvousHandoffRequest completed(long now) {
        return new RendezvousHandoffRequest(requestId, matchId, firstActor, secondActor, activityId,
                Phase.COMPLETED, teamId, null, true, createdAt, now);
    }
}
