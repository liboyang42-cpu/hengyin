package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import java.util.function.Function;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleContentSource.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleSecretService.*;

/** Role grants share the consent guard transaction. Not a Spring bean. Does not write story text. */
public final class MyBatisRendezvousRoleSecretStore implements RendezvousRoleSecretService.Store {
    private final MyBatisRendezvousConsentStore consent;
    private final RendezvousRoleContentMapper content;
    public MyBatisRendezvousRoleSecretStore(MyBatisRendezvousConsentStore consent, RendezvousRoleContentMapper content) {
        this.consent = Objects.requireNonNull(consent);
        this.content = Objects.requireNonNull(content);
    }
    public <T> T transaction(Function<Tx, T> work) {
        return consent.transaction(tickets -> work.apply(new Session(tickets, content)));
    }
    private static final class Session implements Tx {
        private final Transaction tickets;
        private final RendezvousRoleContentMapper content;
        Session(Transaction tickets, RendezvousRoleContentMapper content) {
            this.tickets = tickets; this.content = content;
        }
        public Ticket activeTicket(long actor) { return tickets.activeTicket(actor); }
        public Ticket ticket(String id) { return tickets.ticket(id); }
        public Match match(String id) { return tickets.match(id); }
        public void save(Ticket ticket) { tickets.save(ticket); }
        public void save(Match match) { tickets.save(match); }
        public String clue(String ticketId) { throw new IllegalStateException("W16_ROLE_TOKEN_IS_NOT_CONTENT"); }
        public void insertClue(String ticketId, long actor, String matchId, String clue) {
            throw new IllegalStateException("W16_ROLE_TOKEN_IS_NOT_CONTENT");
        }
        public Binding binding(String matchId) {
            RendezvousRoleContentMapper.BindingRow row = content.binding(matchId);
            return row == null ? null : row.value();
        }
        public String publicationState(String goalId, String publishVersion) { return content.publicationState(goalId, publishVersion); }
        public String roleBody(String goalId, String publishVersion, String roleKey) { return content.roleBody(goalId, publishVersion, roleKey); }
        public Grant grantByTicket(String ticketId) {
            RendezvousRoleContentMapper.GrantRow row = content.grantByTicket(ticketId);
            return row == null ? null : row.value();
        }
        public void insertGrant(Grant grant) {
            Objects.requireNonNull(grant);
            if (content.insertGrant(grant.ticketId, grant.actor, grant.matchId, grant.goalId, grant.publishVersion, grant.roleKey, grant.secretRef) != 1) {
                throw new IllegalStateException("W16_ROLE_GRANT_NOT_STORED");
            }
        }
    }
}
