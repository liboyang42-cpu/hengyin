package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** Ticket and match writes shared by the consent store and the meeting ledger.
 * Must stay aligned with the reservation rules in MyBatisRendezvousConsentStore. */
final class RendezvousConsentSession implements Transaction {
    private final RendezvousConsentMapper mapper;
    private final Thread owner = Thread.currentThread();
    private boolean open = true;

    RendezvousConsentSession(RendezvousConsentMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    void close() { open = false; }

    private void check() {
        if (!open || owner != Thread.currentThread() || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("W16_TRANSACTION_ACCESS_EXPIRED");
        }
    }

    public Ticket activeTicket(long actor) {
        check();
        RendezvousConsentMapper.TicketRow row = mapper.activeTicket(actor);
        return row == null ? null : row.value();
    }

    public Ticket ticket(String id) {
        check();
        RendezvousConsentMapper.TicketRow row = mapper.ticket(id);
        return row == null ? null : row.value();
    }

    public Match match(String id) {
        check();
        RendezvousConsentMapper.MatchRow row = mapper.match(id);
        return row == null ? null : row.value();
    }

    public void save(Ticket ticket) {
        check();
        Objects.requireNonNull(ticket);
        Ticket before = ticket(ticket.id);
        if (before == null) changed(mapper.insertTicket(ticket));
        else changed(mapper.updateTicket(ticket));
        String reserved = mapper.reservation(ticket.id);
        boolean needsReservation = ticket.state == TicketState.RESERVED || ticket.state == TicketState.ACCEPTED;
        if (needsReservation) {
            if (ticket.matchId == null) throw new IllegalStateException("W16_RESERVATION_REQUIRED");
            if (reserved == null) changed(mapper.insertReservation(ticket.id, ticket.matchId));
            else if (!reserved.equals(ticket.matchId)) throw new IllegalStateException("W16_TICKET_ALREADY_RESERVED");
        } else if (reserved != null) {
            if (!reserved.equals(ticket.matchId)) throw new IllegalStateException("W16_RESERVATION_MISMATCH");
            changed(mapper.deleteReservation(ticket.id, reserved));
        }
    }

    public void save(Match match) {
        check();
        Objects.requireNonNull(match);
        if (match.firstTicket.equals(match.secondTicket)) throw new IllegalStateException("W16_DISTINCT_TICKETS_REQUIRED");
        String first = mapper.reservation(match.firstTicket), second = mapper.reservation(match.secondTicket);
        if (match.active()) {
            if (!match.id.equals(first) || !match.id.equals(second)) throw new IllegalStateException("W16_BILATERAL_RESERVATION_REQUIRED");
        } else if (first != null || second != null) throw new IllegalStateException("W16_RESERVATION_NOT_RELEASED");
        Match before = match(match.id);
        if (before == null) {
            if (match.version != 1) throw new IllegalStateException("W16_INITIAL_VERSION_REQUIRED");
            changed(mapper.insertMatch(match));
        } else changed(mapper.updateMatch(match));
    }

    private static void changed(int count) {
        if (count != 1) throw new IllegalStateException("W16_STALE_OR_INVALID_WRITE");
    }
}
