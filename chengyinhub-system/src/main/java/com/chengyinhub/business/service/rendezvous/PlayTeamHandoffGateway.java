package com.chengyinhub.business.service.rendezvous;

/**
 * Real play-team handoff. Default meeting recovery does not install one.
 * {@link #create} and {@link #join} run inside the caller's guard transaction.
 */
public interface PlayTeamHandoffGateway {
    String NAMESPACE = "w16.meeting";

    boolean enabled();

    RendezvousHandoffTeamPort.Lookup lookup(String requestId);

    long create(RendezvousHandoffRequest request);

    /** True only when the peer is currently an active member of a team that has not ended. */
    boolean join(RendezvousHandoffRequest request);

    /** After the create transaction commits and before the join transaction starts. */
    default void betweenCreateAndJoin() {}
}
