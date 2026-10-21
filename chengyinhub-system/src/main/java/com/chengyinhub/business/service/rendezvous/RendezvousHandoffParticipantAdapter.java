package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Match;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Ticket;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Transaction;

/**
 * Maps Group A's frozen call at {@code 37b4b345}:
 * {@code verifyParticipants(tx, actor, ticketId, matchId, peer, requireMutualAccepted, now)}.
 * {@code OngoingVerdict} is not in this tree, so the delegate returns {@link BoundVerdict}.
 * Passes only when {@code safe}, {@code owned} and {@code mutual} are all explicitly true.
 * {@code requireMutualAccepted} is always {@code true}. This class does not open a transaction
 * and does not call {@code read} or {@code current}.
 */
public final class RendezvousHandoffParticipantAdapter implements RendezvousHandoffSafety {
    /** Explicit flags from A's verdict. Missing or false on any of the three is closed. */
    public static final class BoundVerdict {
        public final boolean safe, owned, mutual;
        public BoundVerdict(boolean safe, boolean owned, boolean mutual) {
            this.safe = safe; this.owned = owned; this.mutual = mutual;
        }
    }

    public interface Participants {
        BoundVerdict verifyParticipants(Transaction tx, long actor, String ticketId, String matchId,
                                        long peer, boolean requireMutualAccepted, long now);
    }

    private final Participants participants;

    public RendezvousHandoffParticipantAdapter(Participants participants) { this.participants = participants; }

    /** No open transaction, so A's call cannot be made. Fail closed. */
    public boolean verifyCurrent(String matchId, String ticketId, long actor, long peer, long now) { return false; }

    public boolean verifyCurrent(Transaction tx, String matchId, String ticketId, long actor, long peer, long now) {
        if (participants == null || tx == null || blank(matchId) || blank(ticketId) || actor <= 0 || peer <= 0 || actor == peer) {
            return false;
        }
        BoundVerdict verdict;
        try { verdict = participants.verifyParticipants(tx, actor, ticketId, matchId, peer, true, now); }
        catch (RuntimeException denied) { return false; }
        if (verdict == null || !verdict.safe || !verdict.owned || !verdict.mutual) return false;
        try {
            Ticket ticket = tx.ticket(ticketId);
            Match match = tx.match(matchId);
            if (ticket == null || match == null || ticket.actor != actor || !matchId.equals(ticket.matchId)) return false;
            boolean first = ticketId.equals(match.firstTicket);
            if (first == ticketId.equals(match.secondTicket)) return false;
            Ticket other = tx.ticket(first ? match.secondTicket : match.firstTicket);
            return other != null && other.actor == peer && matchId.equals(other.matchId);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
