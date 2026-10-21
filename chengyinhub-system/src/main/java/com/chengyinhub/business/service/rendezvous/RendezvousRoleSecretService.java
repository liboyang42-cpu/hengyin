package com.chengyinhub.business.service.rendezvous;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleContentSource.*;

/**
 * Self-only role content. Issued only after the in-transaction guard passes and an
 * authoritative publication still has a body for the caller's role. Not a Spring bean.
 * {@code consent.read} is not used: that would open a second transaction.
 */
public final class RendezvousRoleSecretService {
    public interface Store { <T> T transaction(Function<Tx, T> work); }
    public interface Tx extends Transaction {
        /** Legacy correlation slot. Not role text. Production stores must not serve it as a clue. */
        String clue(String ticketId);
        void insertClue(String ticketId, long actor, String matchId, String clue);
        default Binding binding(String matchId) { throw new Rejection("CONTENT_UNAVAILABLE"); }
        default String publicationState(String goalId, String publishVersion) { throw new Rejection("CONTENT_UNAVAILABLE"); }
        default String roleBody(String goalId, String publishVersion, String roleKey) { throw new Rejection("CONTENT_UNAVAILABLE"); }
        default Grant grantByTicket(String ticketId) { throw new Rejection("CONTENT_UNAVAILABLE"); }
        default void insertGrant(Grant grant) { throw new Rejection("CONTENT_UNAVAILABLE"); }
    }
    /** Caller projection. {@code roleClue} is the published body. {@code secretRef} is not a clue. */
    public static final class Projection {
        public final String ticketId, roleClue, goalId, publishVersion, roleKey, secretRef;
        public final boolean willLead;
        public Projection(String ticketId, String roleClue, boolean willLead, String goalId,
                          String publishVersion, String roleKey, String secretRef) {
            this.ticketId = ticketId; this.roleClue = roleClue; this.willLead = willLead;
            this.goalId = goalId; this.publishVersion = publishVersion; this.roleKey = roleKey; this.secretRef = secretRef;
        }
        public String toString() {
            return "Projection{ticketId=" + ticketId + ", willLead=" + willLead + ", goalId=" + goalId
                    + ", publishVersion=" + publishVersion + ", roleKey=" + roleKey + "}";
        }
    }
    private final RendezvousConsentService consent;
    private final Store store;
    private final RendezvousRoleContentGuard guard;
    private final RendezvousRoleContentSource source;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public RendezvousRoleSecretService(RendezvousConsentService consent, Store store) {
        this(consent, store, RendezvousRoleContentGuard.Closed.INSTANCE, RendezvousRoleContentSource.Closed.INSTANCE, Clock.systemUTC());
    }
    public RendezvousRoleSecretService(RendezvousConsentService consent, Store store, RendezvousRoleContentGuard guard,
                                       RendezvousRoleContentSource source, Clock clock) {
        this.consent = Objects.requireNonNull(consent); // boundary must exist; read() does not call consent.read
        this.store = Objects.requireNonNull(store);
        this.guard = Objects.requireNonNull(guard);
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
    }
    public Projection read(long actor, String ticketId) {
        if (consent == null) throw new Rejection("SECRET_UNAVAILABLE");
        if (actor <= 0) throw new Rejection("AUTH_REQUIRED");
        if (ticketId == null || ticketId.trim().isEmpty() || ticketId.length() > 128) throw new Rejection("INVALID_INPUT");
        return store.transaction(tx -> {
            Ticket ticket = tx.ticket(ticketId);
            if (ticket == null || ticket.actor != actor) throw new Rejection("NOT_FOUND");
            long now = clock.millis();
            guard.recheck(tx, actor, ticketId, now);
            Match match = RendezvousRoleContentGuardAdapter.requireOwnedMutual(tx, actor, ticketId, now);
            ticket = tx.ticket(ticketId);
            boolean first = match.firstTicket.equals(ticket.id);
            Issued issued;
            try {
                issued = source.resolve(tx, match.id, first, now);
            } catch (Rejection rejected) {
                throw rejected;
            } catch (RuntimeException failed) {
                throw new Rejection("CONTENT_UNAVAILABLE");
            }
            if (issued == null || issued.body == null || issued.body.trim().isEmpty()
                    || issued.goalId == null || issued.publishVersion == null || issued.roleKey == null) {
                throw new Rejection("CONTENT_UNAVAILABLE");
            }
            Grant grant = storeGrant(tx, ticket, match, issued);
            if (!same(grant, ticket.actor, match.id, issued)) throw new Rejection("SECRET_UNAVAILABLE");
            return new Projection(ticket.id, issued.body, first, issued.goalId, issued.publishVersion, issued.roleKey, grant.secretRef);
        });
    }
    private Grant storeGrant(Tx tx, Ticket ticket, Match match, Issued issued) {
        Grant existing = quiet(tx, ticket.id);
        if (existing != null) return existing;
        for (int attempt = 0; attempt < 2; attempt++) {
            Grant created = new Grant(ticket.id, ticket.actor, match.id, issued.goalId, issued.publishVersion, issued.roleKey, newRef());
            try {
                tx.insertGrant(created);
                Grant stored = quiet(tx, ticket.id);
                return stored == null ? created : stored;
            } catch (RuntimeException ignored) {
                Grant stored = quiet(tx, ticket.id);
                if (stored != null) return stored;
            }
        }
        return null;
    }
    private static Grant quiet(Tx tx, String ticketId) {
        try { return tx.grantByTicket(ticketId); }
        catch (RuntimeException ignored) { return null; }
    }
    private static boolean same(Grant grant, long actor, String matchId, Issued issued) {
        return grant != null && grant.actor == actor && matchId.equals(grant.matchId)
                && issued.goalId.equals(grant.goalId) && issued.publishVersion.equals(grant.publishVersion)
                && issued.roleKey.equals(grant.roleKey) && correlation(grant.secretRef) && !grant.secretRef.equals(issued.body);
    }
    /** Internal correlation only. The shape is fixed so a prose body cannot be stored as the ref. */
    private String newRef() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return "r." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static boolean correlation(String ref) { return ref != null && ref.matches("r\\.[A-Za-z0-9_-]{22}"); }
}
