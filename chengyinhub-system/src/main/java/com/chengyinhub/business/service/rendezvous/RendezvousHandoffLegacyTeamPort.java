package com.chengyinhub.business.service.rendezvous;

/**
 * Fail-closed stand-in for {@code IPlayTeamService}. That API has no request id and no
 * authoritative result query, and this class is not allowed to add them. Lookup never
 * reports ABSENT, because absence cannot be proved. Create and join are refused.
 */
public final class RendezvousHandoffLegacyTeamPort implements RendezvousHandoffTeamPort {
    public static final String BLOCKER = "PLAY_TEAM_AUTHORITATIVE_QUERY_MISSING";

    public boolean available() { return false; }

    public Lookup lookup(String requestId) { return Lookup.unknown(BLOCKER); }

    public Created create(String requestId, long leader, long member, long activityId) {
        throw new IllegalStateException("W16_TEAM_PORT_NOT_AUTHORIZED");
    }

    public void join(String requestId) {
        throw new IllegalStateException("W16_TEAM_PORT_NOT_AUTHORIZED");
    }
}
