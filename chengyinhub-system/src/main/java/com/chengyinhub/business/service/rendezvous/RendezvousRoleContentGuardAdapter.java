package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/**
 * Maps {@code recheck(tx, actor, ticketId, now)} onto Group A's frozen
 * {@code verifyParticipants(tx, actor, ticketId, null, null, true, now)}.
 * Not wired. {@link RendezvousRoleContentGuard.Closed} stays the service default.
 * Publication authority stays NOT_CONNECTED and is not invoked here.
 */
public final class RendezvousRoleContentGuardAdapter implements RendezvousRoleContentGuard {
    /** Target of {@code OngoingVerdict verifyParticipants(Transaction, long, String, String, Long, boolean, long)}. */
    @FunctionalInterface
    public interface Participants {
        OngoingVerdict verifyParticipants(Transaction tx, long actor, String ticketId, String expectedMatchId,
                                          Long expectedPeer, boolean requireMutualAccepted, long now);
    }

    private final Participants participants;

    public RendezvousRoleContentGuardAdapter(Participants participants) {
        this.participants = Objects.requireNonNull(participants);
    }

    /**
     * Only an {@link OngoingVerdict} with {@code safe}, {@code owned} and {@code mutual} all true passes.
     * Null, a non-verdict, or any other combination is {@code SECRET_UNAVAILABLE}.
     * A's missing or stranger result ({@code safe=false}, {@code owned=false}) is not rewritten as {@code NOT_FOUND}.
     * {@link Rejection} from A is kept. Any other failure drops its cause.
     */
    public void recheck(Transaction tx, long actor, String ticketId, long now) {
        OngoingVerdict verdict;
        try {
            verdict = participants.verifyParticipants(tx, actor, ticketId, null, null, true, now);
        } catch (Rejection rejected) {
            throw rejected;
        } catch (RuntimeException failed) {
            throw new Rejection("SECRET_UNAVAILABLE");
        }
        if (!allows(verdict)) throw new Rejection("SECRET_UNAVAILABLE");
    }

    /** Explicit SAFE. Unknown types and partial flags fail closed. */
    static boolean allows(Object verdict) {
        if (!(verdict instanceof OngoingVerdict ongoing)) return false;
        return ongoing.safe && ongoing.owned && ongoing.mutual;
    }

    /**
     * Caller owns the ticket, the other side is a different actor on the same match,
     * both accepts are present, and {@code now} is before expiry. Does not write.
     * Used by the content read after the guard, so a SAFE verdict still needs a live match row.
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
