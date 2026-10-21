package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Match;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Ticket;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Transaction;

/**
 * Builds this group's port on Group A's frozen call
 * {@code OngoingVerdict verifyParticipants(Transaction tx, String ticketId)}.
 * A's type is not in this tree, so the delegate returns {@link Verdict} and the merge
 * maps {@code OngoingVerdict} onto {@link Verdict#SAFE} only when that verdict explicitly
 * says the participants are still safe. Any other value, null, or exception is closed.
 *
 * <p>The frozen call does not take matchId, actor, or peer. Those checks happen here on the
 * same {@code tx} instance. This class does not call {@code read}/{@code current} and does
 * not open a transaction. Actor/peer binding, {@code requireMutualAccepted}, and terminate
 * are not frozen yet; mutual acceptance stays in the meeting service's own guard.
 */
public final class RendezvousHandoffParticipantAdapter implements RendezvousHandoffSafety {
    public enum Verdict { SAFE, CLOSED }

    public interface Participants {
        Verdict verifyParticipants(Transaction tx, String ticketId);
    }

    private final Participants participants;

    public RendezvousHandoffParticipantAdapter(Participants participants) { this.participants = participants; }

    /** No open transaction, so A's call cannot be made. Fail closed. */
    public boolean verifyCurrent(String matchId, String ticketId, long actor, long peer, long now) { return false; }

    public boolean verifyCurrent(Transaction tx, String matchId, String ticketId, long actor, long peer, long now) {
        if (participants == null || tx == null || blank(matchId) || blank(ticketId) || actor <= 0 || peer <= 0 || actor == peer) {
            return false;
        }
        Verdict verdict;
        try { verdict = participants.verifyParticipants(tx, ticketId); }
        catch (RuntimeException denied) { return false; }
        if (verdict != Verdict.SAFE) return false;
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