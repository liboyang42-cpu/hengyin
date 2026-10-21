package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/**
 * Maps {@link RendezvousRoleContentGuard} onto Group A's frozen call.
 * Not wired. {@link RendezvousRoleContentGuard.Closed} stays the service default until merge.
 * The method reference is {@code groupA::verifyParticipants} on the same open transaction.
 */
public final class RendezvousRoleContentGuardAdapter<V> implements RendezvousRoleContentGuard {
    /** Structural target of {@code OngoingVerdict verifyParticipants(Transaction tx, String ticketId)}. */
    @FunctionalInterface
    public interface Participants<V> {
        V verifyParticipants(Transaction tx, String ticketId);
    }

    private final Participants<V> participants;

    public RendezvousRoleContentGuardAdapter(Participants<V> participants) {
        this.participants = Objects.requireNonNull(participants);
    }

    /**
     * Calls A's {@code verifyParticipants(tx, ticketId)} and then applies caller ownership,
     * peer binding, mutual acceptance and expiry. A's frozen call has no actor and no
     * {@code requireMutualAccepted} flag, so those checks stay here. A null verdict or an
     * unexpected failure refuses. {@link Rejection} from A is kept. Verdict fields are not read.
     */
    public void recheck(Transaction tx, long actor, String ticketId, long now) {
        V verdict;
        try {
            verdict = participants.verifyParticipants(tx, ticketId);
        } catch (Rejection rejected) {
            throw rejected;
        } catch (RuntimeException failed) {
            throw new Rejection("SECRET_UNAVAILABLE");
        }
        if (verdict == null) throw new Rejection("SECRET_UNAVAILABLE");
        requireOwnedMutual(tx, actor, ticketId, now);
    }

    /**
     * Caller owns the ticket, the other side is a different actor on the same match,
     * both accepts are present, and {@code now} is before expiry. Does not write.
     */
    public static Match requireOwnedMutual(Transaction tx, long actor, String ticketId, long now) {
        Ticket ticket = tx.ticket(ticketId);
        if (ticket == null || ticket.actor != actor) throw new Rejection("NOT_FOUND");
        Match match = ticket.matchId == null ? null : tx.match(ticket.matchId);
        boolean first = match != null && match.firstTicket.equals(ticket.id);
        boolean second = match != null && match.secondTicket.equals(ticket.id);
        if (match == null || match.state != MatchState.MUTUAL_ACCEPTED || first == second
                || match.firstAcceptedAt == null || match.secondAcceptedAt == null || match.expiresAt <= now) {
            throw new Rejection("SECRET_UNAVAILABLE");
        }
        Ticket peer = tx.ticket(first ? match.secondTicket : match.firstTicket);
        if (peer == null || peer.actor == actor || peer.matchId == null || !match.id.equals(peer.matchId)) {
            throw new Rejection("SECRET_UNAVAILABLE");
        }
        return match;
    }
}
