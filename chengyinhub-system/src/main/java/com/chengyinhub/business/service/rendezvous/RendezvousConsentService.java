package com.chengyinhub.business.service.rendezvous;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * W16 opt-in and bilateral consent. Deliberately not a Spring bean or an HTTP API.
 * No matching, consent or meeting receipt can create a team through this service.
 * Callers must derive actor from the existing authenticated member context.
 */
public final class RendezvousConsentService {
    public enum TicketState { WAITING, RESERVED, ACCEPTED, CANCELLED, EXPIRED }
    public enum MatchState { PROPOSED, MUTUAL_ACCEPTED, CANCELLED, EXPIRED, SAFETY_STOPPED }

    /** Durable implementations must serialize ALL operations in one database transaction,
     * enforce one active ticket per actor and one active reservation per ticket, and roll
     * back on exceptions. Callbacks must not be retried after an ambiguous commit.
     * In-memory implementations belong only in tests. Reads must be primary/locking reads.
     */
    public interface Store {
        <T> T transaction(Function<Transaction, T> operation);
    }
    public interface Transaction {
        Ticket activeTicket(long actor);
        Ticket ticket(String id);
        Match match(String id);
        void save(Ticket ticket);
        void save(Match match);
    }
    /** Server-owned policy: bans, reviewed age eligibility, approved region/time window,
     * preferences and contact-rate limits. Unknown policy MUST fail closed.
     * blocks must query the existing bidirectional IM block authority, not client input.
     */
    public interface Safety {
        boolean eligible(long actor, String pool, String eligibilityVersion, long now);
        boolean blocked(long firstActor, long secondActor);
        boolean compatible(long firstActor, long secondActor, String pool, long now);
        boolean publicMeetingAllowed(String pool, String publicPlace, long now);
    }
    public static final class Rejection extends IllegalStateException {
        private final String code;
        Rejection(String code) { super(code); this.code = code; }
        public String getCode() { return code; }
    }
    /** Internal persistence record. Never return or serialize as a client response. */
    public static final class Ticket {
        public final String id, pool, eligibilityVersion, matchId;
        public final long actor, expiresAt;
        public final TicketState state;
        Ticket(String id, long actor, String pool, String eligibilityVersion, long expiresAt,
               TicketState state, String matchId) {
            this.id = id; this.actor = actor; this.pool = pool; this.eligibilityVersion = eligibilityVersion;
            this.expiresAt = expiresAt; this.state = state; this.matchId = matchId;
        }
        Ticket transition(TicketState state, String matchId) {
            return new Ticket(id, actor, pool, eligibilityVersion, expiresAt, state, matchId);
        }
        public boolean active() { return state == TicketState.WAITING || state == TicketState.RESERVED || state == TicketState.ACCEPTED; }
    }
    /** Internal record. Separate accept timestamps are receipts, never a single shared flag. */
    public static final class Match {
        public final String id, firstTicket, secondTicket, publicPlace;
        public final long expiresAt, version;
        public final Long firstAcceptedAt, secondAcceptedAt;
        public final MatchState state;
        Match(String id, String firstTicket, String secondTicket, String publicPlace, long expiresAt,
              long version, Long firstAcceptedAt, Long secondAcceptedAt, MatchState state) {
            this.id = id; this.firstTicket = firstTicket; this.secondTicket = secondTicket;
            this.publicPlace = publicPlace; this.expiresAt = expiresAt; this.version = version;
            this.firstAcceptedAt = firstAcceptedAt; this.secondAcceptedAt = secondAcceptedAt; this.state = state;
        }
        public boolean active() { return state == MatchState.PROPOSED || state == MatchState.MUTUAL_ACCEPTED; }
    }
    /** Participant-only projection: no partner identifier, clue, contact or coordinates. */
    public static final class View {
        public final String ticketId, matchId, publicPlace;
        public final TicketState ticketState;
        public final MatchState matchState;
        public final boolean acceptedByMe, acceptedByOther;
        public final long expiresAt, version;
        View(Ticket t, Match m) {
            ticketId = t.id; ticketState = t.state; matchId = m == null ? null : m.id;
            matchState = m == null ? null : m.state;
            boolean first = m != null && m.firstTicket.equals(t.id);
            acceptedByMe = m != null && (first ? m.firstAcceptedAt : m.secondAcceptedAt) != null;
            acceptedByOther = m != null && (first ? m.secondAcceptedAt : m.firstAcceptedAt) != null;
            publicPlace = m != null && m.active() ? m.publicPlace : null;
            expiresAt = m == null ? t.expiresAt : m.expiresAt; version = m == null ? 0 : m.version;
        }
    }
    private static final class Reply {
        final View view; final String rejection;
        Reply(View view, String rejection) { this.view = view; this.rejection = rejection; }
    }
    private final Store store;
    private final Safety safety;
    private final Clock clock;
    public RendezvousConsentService(Store store, Safety safety, Clock clock) {
        this.store = Objects.requireNonNull(store); this.safety = Objects.requireNonNull(safety); this.clock = Objects.requireNonNull(clock);
    }
    /** Explicit user opt-in. TTL comes from approved server policy, never a request body. */
    public View join(long actor, String pool, String eligibilityVersion, long ttlMillis) {
        actor(actor); text(pool); text(eligibilityVersion);
        if (ttlMillis <= 0) throw new Rejection("INVALID_EXPIRY");
        Reply reply = store.transaction(tx -> {
            long now = clock.millis();
            long expiresAt = Math.addExact(now, ttlMillis);
            Ticket existing = tx.activeTicket(actor);
            if (existing != null) {
                refresh(tx, existing, now);
                existing = tx.activeTicket(actor);
                if (existing != null) {
                    if (!pool.equals(existing.pool) || !eligibilityVersion.equals(existing.eligibilityVersion)) throw new Rejection("ACTIVE_TICKET_EXISTS");
                    return new Reply(view(tx, existing), null);
                }
            }
            if (!safety.eligible(actor, pool, eligibilityVersion, now)) return new Reply(null, "NOT_ELIGIBLE");
            Ticket t = new Ticket(UUID.randomUUID().toString(), actor, pool, eligibilityVersion, expiresAt, TicketState.WAITING, null);
            tx.save(t); return new Reply(new View(t, null), null);
        });
        if (reply.rejection != null) throw new Rejection(reply.rejection);
        return reply.view;
    }
    /** Internal scheduler operation only. It cannot opt either person into the pool. */
    public String propose(String firstTicket, String secondTicket, String publicPlace) {
        text(firstTicket); text(secondTicket); text(publicPlace);
        return store.transaction(tx -> {
            long now = clock.millis();
            Ticket a = tx.ticket(firstTicket), b = tx.ticket(secondTicket);
            if (a == null || b == null || a.actor == b.actor || !a.pool.equals(b.pool)) throw new Rejection("PAIR_UNAVAILABLE");
            if (a.state != TicketState.WAITING || b.state != TicketState.WAITING || a.expiresAt <= now || b.expiresAt <= now) throw new Rejection("PAIR_UNAVAILABLE");
            if (!safe(a, b, publicPlace, now)) throw new Rejection("PAIR_UNAVAILABLE");
            String id = UUID.randomUUID().toString();
            Match m = new Match(id, a.id, b.id, publicPlace, Math.min(a.expiresAt, b.expiresAt), 1, null, null, MatchState.PROPOSED);
            tx.save(a.transition(TicketState.RESERVED, id)); tx.save(b.transition(TicketState.RESERVED, id)); tx.save(m);
            return id;
        });
    }
    public View read(long actor, String ticketId) {
        actor(actor); text(ticketId);
        return store.transaction(tx -> { Ticket t = owned(tx, actor, ticketId); refresh(tx, t, clock.millis()); return view(tx, tx.ticket(t.id)); });
    }
    /** Repeated acceptance is idempotent. A stale unaccepted review cannot grant consent. */
    public View accept(long actor, String ticketId, long expectedVersion) {
        actor(actor); text(ticketId);
        return store.transaction(tx -> {
            Ticket t = owned(tx, actor, ticketId); long now = clock.millis(); refresh(tx, t, now);
            t = tx.ticket(t.id); Match m = t.matchId == null ? null : tx.match(t.matchId);
            if (m == null) {
                if (!t.active()) return new View(t, null);
                throw new Rejection("NO_PROPOSAL");
            }
            if (!m.active()) return new View(t, m);
            boolean first = m.firstTicket.equals(t.id);
            if ((first ? m.firstAcceptedAt : m.secondAcceptedAt) != null) return new View(t, m);
            if (m.version != expectedVersion) throw new Rejection("STALE_PROPOSAL");
            Long a = first ? Long.valueOf(now) : m.firstAcceptedAt;
            Long b = first ? m.secondAcceptedAt : Long.valueOf(now);
            Match next = new Match(m.id, m.firstTicket, m.secondTicket, m.publicPlace, m.expiresAt, m.version + 1, a, b,
                    a != null && b != null ? MatchState.MUTUAL_ACCEPTED : MatchState.PROPOSED);
            t = t.transition(TicketState.ACCEPTED, m.id); tx.save(t); tx.save(next); return new View(t, next);
        });
    }
    /** Both decline and withdrawal end the proposal without a penalty or implicit requeue.
     * Either participant may also withdraw after mutual acceptance, before meeting handoff.
     */
    public View cancel(long actor, String ticketId) {
        actor(actor); text(ticketId);
        return store.transaction(tx -> {
            Ticket t = owned(tx, actor, ticketId); refresh(tx, t, clock.millis()); t = tx.ticket(t.id);
            if (t.active()) {
                if (t.matchId != null) stop(tx, tx.match(t.matchId), MatchState.CANCELLED);
                else tx.save(t.transition(TicketState.CANCELLED, null));
            }
            return view(tx, tx.ticket(t.id));
        });
    }
    /** Call from the authoritative block/ban event handler, with participant identity.
     * No delivery/notification side effect; terminated proposals reveal no meeting place.
     */
    public void stopForSafety(long actor) {
        actor(actor);
        store.transaction(tx -> {
            Ticket t = tx.activeTicket(actor);
            if (t != null) {
                if (t.matchId == null) tx.save(t.transition(TicketState.CANCELLED, null));
                else stop(tx, tx.match(t.matchId), MatchState.SAFETY_STOPPED);
            }
            return null;
        });
    }
    /** Internal, same-transaction cleanup after a database-confirmed IM block.
     * Does not cancel unrelated matches or remove either actor from a waiting pool.
     */
    static void stopBlockedPair(Transaction tx, long first, long second) {
        Ticket a = tx.activeTicket(first), b = tx.activeTicket(second);
        if (a == null || b == null || a.matchId == null || !a.matchId.equals(b.matchId)) return;
        Match match = tx.match(a.matchId);
        if (match == null || !((match.firstTicket.equals(a.id) && match.secondTicket.equals(b.id))
                || (match.firstTicket.equals(b.id) && match.secondTicket.equals(a.id)))) {
            throw new Rejection("CORRUPT_RESERVATION");
        }
        stop(tx, match, MatchState.SAFETY_STOPPED);
    }
    private void refresh(Transaction tx, Ticket t, long now) {
        if (!t.active()) return;
        if (t.matchId == null) {
            if (t.expiresAt <= now) tx.save(t.transition(TicketState.EXPIRED, null));
            else if (!safety.eligible(t.actor, t.pool, t.eligibilityVersion, now)) tx.save(t.transition(TicketState.CANCELLED, null));
            return;
        }
        Match m = tx.match(t.matchId);
        if (m == null) throw new Rejection("CORRUPT_RESERVATION");
        if (!m.active()) return;
        Ticket a = tx.ticket(m.firstTicket), b = tx.ticket(m.secondTicket);
        if (m.expiresAt <= now) stop(tx, m, MatchState.EXPIRED);
        else if (a == null || b == null || !safe(a, b, m.publicPlace, now)) stop(tx, m, MatchState.SAFETY_STOPPED);
    }
    private boolean safe(Ticket a, Ticket b, String place, long now) {
        return safety.eligible(a.actor, a.pool, a.eligibilityVersion, now)
                && safety.eligible(b.actor, b.pool, b.eligibilityVersion, now)
                && !safety.blocked(a.actor, b.actor) && safety.compatible(a.actor, b.actor, a.pool, now)
                && safety.publicMeetingAllowed(a.pool, place, now);
    }
    private static void stop(Transaction tx, Match m, MatchState state) {
        if (!m.active()) return;
        Ticket a = tx.ticket(m.firstTicket), b = tx.ticket(m.secondTicket);
        if (a == null || b == null) throw new Rejection("CORRUPT_RESERVATION");
        TicketState ts = state == MatchState.EXPIRED ? TicketState.EXPIRED : TicketState.CANCELLED;
        tx.save(a.transition(ts, m.id)); tx.save(b.transition(ts, m.id));
        tx.save(new Match(m.id, m.firstTicket, m.secondTicket, m.publicPlace, m.expiresAt, m.version + 1, m.firstAcceptedAt, m.secondAcceptedAt, state));
    }
    private View view(Transaction tx, Ticket t) { return new View(t, t.matchId == null ? null : tx.match(t.matchId)); }
    private Ticket owned(Transaction tx, long actor, String id) {
        Ticket t = tx.ticket(id);
        if (t == null || t.actor != actor) throw new Rejection("NOT_FOUND");
        return t;
    }
    private static void actor(long actor) { if (actor <= 0) throw new Rejection("AUTH_REQUIRED"); }
    private static void text(String value) { if (value == null || value.trim().isEmpty() || value.length() > 128) throw new Rejection("INVALID_INPUT"); }
}
