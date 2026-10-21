package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import java.util.function.Function;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleSecretService.*;

/** Role clues share the consent guard so a stranger read cannot race a cancel. Not a Spring bean. */
public final class MyBatisRendezvousRoleSecretStore implements RendezvousRoleSecretService.Store {
    private final MyBatisRendezvousConsentStore consent;
    private final RendezvousProtocolMapper protocol;
    public MyBatisRendezvousRoleSecretStore(MyBatisRendezvousConsentStore consent, RendezvousProtocolMapper protocol) {
        this.consent = Objects.requireNonNull(consent);
        this.protocol = Objects.requireNonNull(protocol);
    }
    public <T> T transaction(Function<Tx, T> work) {
        return consent.transaction(tickets -> work.apply(new Session(tickets, protocol)));
    }
    private static final class Session implements Tx {
        private final Transaction tickets;
        private final RendezvousProtocolMapper protocol;
        Session(Transaction tickets, RendezvousProtocolMapper protocol) {
            this.tickets = tickets; this.protocol = protocol;
        }
        public Ticket activeTicket(long actor) { return tickets.activeTicket(actor); }
        public Ticket ticket(String id) { return tickets.ticket(id); }
        public Match match(String id) { return tickets.match(id); }
        public void save(Ticket ticket) { tickets.save(ticket); }
        public void save(Match match) { tickets.save(match); }
        public String clue(String ticketId) { return protocol.roleClue(ticketId); }
        public void insertClue(String ticketId, long actor, String matchId, String clue) {
            try {
                if (protocol.insertRole(ticketId, actor, matchId, clue) != 1) throw new IllegalStateException("W16_SECRET_NOT_STORED");
            } catch (RuntimeException failed) {
                if (protocol.roleClue(ticketId) == null) throw failed;
            }
        }
    }
}
