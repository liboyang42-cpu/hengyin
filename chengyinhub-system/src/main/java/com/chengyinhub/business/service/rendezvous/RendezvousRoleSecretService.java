package com.chengyinhub.business.service.rendezvous;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** Self-only role clue. Opaque token, not a place, coordinate, partner id, or presence proof.
 * Issued only after mutual acceptance. Not a Spring bean. */
public final class RendezvousRoleSecretService {
    public interface Store { <T> T transaction(java.util.function.Function<Tx, T> work); }
    public interface Tx extends Transaction {
        String clue(String ticketId);
        void insertClue(String ticketId, long actor, String matchId, String clue);
    }
    public static final class Projection {
        public final String ticketId, roleClue;
        public final boolean willLead;
        public Projection(String ticketId, String roleClue, boolean willLead) {
            this.ticketId = ticketId; this.roleClue = roleClue; this.willLead = willLead;
        }
    }
    private final RendezvousConsentService consent;
    private final Store store;
    private final SecureRandom random = new SecureRandom();
    public RendezvousRoleSecretService(RendezvousConsentService consent, Store store) {
        this.consent = Objects.requireNonNull(consent);
        this.store = Objects.requireNonNull(store);
    }
    public Projection read(long actor, String ticketId) {
        View view = consent.read(actor, ticketId);
        if (view.matchState != MatchState.MUTUAL_ACCEPTED) throw new Rejection("SECRET_UNAVAILABLE");
        return store.transaction(tx -> {
            Ticket ticket = tx.ticket(ticketId);
            if (ticket == null || ticket.actor != actor) throw new Rejection("NOT_FOUND");
            Match match = ticket.matchId == null ? null : tx.match(ticket.matchId);
            if (match == null || match.state != MatchState.MUTUAL_ACCEPTED) throw new Rejection("SECRET_UNAVAILABLE");
            String clue = tx.clue(ticket.id);
            if (clue == null) {
                tx.insertClue(ticket.id, actor, match.id, newClue());
                clue = tx.clue(ticket.id);
            }
            if (clue == null) throw new Rejection("SECRET_UNAVAILABLE");
            return new Projection(ticket.id, clue, match.firstTicket.equals(ticket.id));
        });
    }
    private String newClue() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "r." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
