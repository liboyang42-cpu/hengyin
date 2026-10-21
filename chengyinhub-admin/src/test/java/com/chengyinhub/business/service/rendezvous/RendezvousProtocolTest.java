package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleSecretService.Projection;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic policy and pool. No production grant is inserted. */
class RendezvousProtocolTest {
    @Test void missingRulesStayClosed() {
        MemoryCatalog catalog = new MemoryCatalog();
        RendezvousVersionedPolicy policy = new RendezvousVersionedPolicy(catalog, new Blocks());
        assertFalse(policy.eligible(1, "pool", "v1", 0));
        assertFalse(policy.compatible(1, 2, "pool", 0));
        assertFalse(policy.publicMeetingAllowed("pool", "somewhere", 0));
        assertNull(policy.ticketTtl("v1"));
        assertNull(policy.approvedPlace("pool"));
        catalog.put("eligibility-source", "v1", open());
        catalog.put("age", "v1", open());
        catalog.put("region", "v1", open());
        catalog.put("frequency", "pool", new RendezvousVersionedPolicy.Row("OPEN", null, null, null));
        assertFalse(policy.eligible(1, "pool", "v1", 0));
    }

    @Test void joinReadbackRejectAndSecretStayOnTheCaller() {
        Harness h = new Harness();
        h.allow();
        View joined = h.consent.join(1, "pool", "v1", h.policy.ticketTtl("v1"));
        assertEquals(joined.ticketId, h.consent.current(1).ticketId);
        assertEquals(joined.ticketId, h.consent.join(1, "pool", "v1", h.policy.ticketTtl("v1")).ticketId);
        View other = h.consent.join(2, "pool", "v1", h.policy.ticketTtl("v1"));
        String match = h.scheduler.pairOnce("pool");
        assertNotNull(match);
        assertThrows(Rejection.class, () -> h.scheduler.pairOnce("pool"));
        h.consent.accept(1, joined.ticketId, 1);
        code("SECRET_UNAVAILABLE", () -> h.secrets.read(1, joined.ticketId));
        h.consent.accept(2, other.ticketId, 2);
        Projection mine = h.secrets.read(1, joined.ticketId);
        Projection again = h.secrets.read(1, joined.ticketId);
        Projection theirs = h.secrets.read(2, other.ticketId);
        assertEquals(mine.roleClue, again.roleClue);
        assertNotEquals(mine.roleClue, theirs.roleClue);
        assertTrue(mine.willLead); assertFalse(theirs.willLead);
        code("NOT_FOUND", () -> h.secrets.read(2, joined.ticketId));
        code("NOT_FOUND", () -> h.secrets.read(3, joined.ticketId));
        Set<String> fields = new HashSet<>();
        for (java.lang.reflect.Field f : Projection.class.getFields()) fields.add(f.getName());
        assertEquals(new HashSet<>(Arrays.asList("ticketId", "roleClue", "willLead")), fields);
        View rejected = h.consent.cancel(1, joined.ticketId);
        assertEquals(MatchState.CANCELLED, rejected.matchState);
        code("NOT_FOUND", () -> h.consent.current(1));
    }

    @Test void frequencyCapStopsALaterPairAndReportDoesNotClearAWaitingThird() {
        Harness h = new Harness();
        h.allow();
        h.catalog.put("frequency", "pool", new RendezvousVersionedPolicy.Row("OPEN", 1, null, null));
        View a = h.consent.join(1, "pool", "v1", 1000);
        View b = h.consent.join(2, "pool", "v1", 1000);
        View c = h.consent.join(3, "pool", "v1", 1000);
        h.scheduler.pairOnce("pool");
        h.consent.cancel(1, a.ticketId);
        h.consent.join(1, "pool", "v1", 1000);
        h.consent.join(2, "pool", "v1", 1000);
        code("PAIR_UNAVAILABLE", () -> h.scheduler.pairOnce("pool"));
        assertEquals(TicketState.WAITING, h.consent.current(3).ticketState);
        assertEquals(c.ticketId, h.consent.current(3).ticketId);
        b = h.consent.current(2);
        h.consent.stopForReport(1, 2);
        assertEquals(TicketState.WAITING, h.consent.current(3).ticketState);
        assertNotEquals(MatchState.MUTUAL_ACCEPTED, h.consent.read(2, b.ticketId).matchState);
    }

    @Test void closedPlaceCannotBeInventedByTheScheduler() {
        Harness h = new Harness();
        h.allow();
        h.catalog.rows.remove("meeting-safety\0pool");
        h.consent.join(1, "pool", "v1", 1000);
        h.consent.join(2, "pool", "v1", 1000);
        code("POLICY_CLOSED", () -> h.scheduler.pairOnce("pool"));
        assertTrue(h.db.matches.isEmpty());
    }

    private static void code(String code, Runnable work) {
        assertEquals(code, assertThrows(Rejection.class, work::run).getCode());
    }
    private static RendezvousVersionedPolicy.Row open() {
        return new RendezvousVersionedPolicy.Row("OPEN", null, null, null);
    }

    static final class Harness {
        final MemoryCatalog catalog = new MemoryCatalog();
        final RendezvousVersionedPolicy policy = new RendezvousVersionedPolicy(catalog, new Blocks());
        final MutableClock clock = new MutableClock();
        final Db db = new Db();
        final ConsentStore consentStore = new ConsentStore(db);
        final RendezvousConsentService consent = new RendezvousConsentService(consentStore, policy, clock, RendezvousConsentServiceTest.permitsPairing);
        final RendezvousRoleSecretService secrets = new RendezvousRoleSecretService(consent, new SecretStore(db));
        final RendezvousPoolScheduler scheduler = new RendezvousPoolScheduler(consent, policy, new Wait(db), catalog, clock);
        void allow() {
            for (String dimension : new String[]{"eligibility-source", "age", "region", "time-window"})
                catalog.put(dimension, "v1", open());
            catalog.put("frequency", "pool", new RendezvousVersionedPolicy.Row("OPEN", 2, null, null));
            catalog.put("ticket-ttl", "v1", new RendezvousVersionedPolicy.Row("OPEN", null, 1000L, null));
            catalog.put("meeting-safety", "pool", new RendezvousVersionedPolicy.Row("OPEN", null, null, "public-place"));
        }
    }
    static final class MemoryCatalog implements RendezvousVersionedPolicy.Catalog, RendezvousPoolScheduler.Contacts {
        final Map<String, RendezvousVersionedPolicy.Row> rows = new HashMap<>();
        final Map<Long, Integer> counts = new HashMap<>();
        void put(String dimension, String version, RendezvousVersionedPolicy.Row row) { rows.put(dimension + "\0" + version, row); }
        public RendezvousVersionedPolicy.Row find(String dimension, String version) { return rows.get(dimension + "\0" + version); }
        public int contacts(long actor) { return counts.getOrDefault(actor, 0); }
        public void record(long first, long second) {
            counts.merge(first, 1, Integer::sum); counts.merge(second, 1, Integer::sum);
        }
    }
    static final class Blocks implements ImBlockMapper {
        public int existsBetween(Long a, Long b) { return 0; }
        public int insertImBlock(ImBlock block) { throw new AssertionError("no block write"); }
        public int deleteImBlock(Long memberId, Long blockedMemberId) { throw new AssertionError("no block write"); }
        public ImBlock selectOne(Long memberId, Long blockedMemberId) { throw new AssertionError("no block write"); }
    }
    static final class MutableClock extends Clock {
        long now = 10_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    static final class Db {
        final Map<String, Ticket> tickets = new HashMap<>();
        final Map<String, Match> matches = new HashMap<>();
        final Map<String, String> clues = new HashMap<>();
    }
    static final class ConsentStore implements Store, Transaction {
        final Db db;
        ConsentStore(Db db) { this.db = db; }
        public synchronized <T> T transaction(Function<Transaction, T> work) {
            Map<String, Ticket> tickets = new HashMap<>(db.tickets);
            Map<String, Match> matches = new HashMap<>(db.matches);
            try { return work.apply(this); }
            catch (RuntimeException error) { db.tickets.clear(); db.tickets.putAll(tickets); db.matches.clear(); db.matches.putAll(matches); throw error; }
        }
        public Ticket activeTicket(long actor) { return db.tickets.values().stream().filter(t -> t.actor == actor && t.active()).findFirst().orElse(null); }
        public Ticket ticket(String id) { return db.tickets.get(id); }
        public Match match(String id) { return db.matches.get(id); }
        public void save(Ticket ticket) { db.tickets.put(ticket.id, ticket); }
        public void save(Match match) { db.matches.put(match.id, match); }
    }
    static final class SecretStore implements RendezvousRoleSecretService.Store, RendezvousRoleSecretService.Tx {
        final Db db;
        SecretStore(Db db) { this.db = db; }
        public synchronized <T> T transaction(Function<RendezvousRoleSecretService.Tx, T> work) { return work.apply(this); }
        public Ticket activeTicket(long actor) { return db.tickets.values().stream().filter(t -> t.actor == actor && t.active()).findFirst().orElse(null); }
        public Ticket ticket(String id) { return db.tickets.get(id); }
        public Match match(String id) { return db.matches.get(id); }
        public void save(Ticket ticket) { throw new AssertionError("secret read does not save tickets"); }
        public void save(Match match) { throw new AssertionError("secret read does not save matches"); }
        public String clue(String ticketId) { return db.clues.get(ticketId); }
        public void insertClue(String ticketId, long actor, String matchId, String clue) { db.clues.putIfAbsent(ticketId, clue); }
    }
    static final class Wait implements RendezvousPoolScheduler.Waiting {
        final Db db;
        Wait(Db db) { this.db = db; }
        public List<String> waitingIds(String pool, long now, int limit) {
            List<String> ids = new ArrayList<>();
            for (Ticket ticket : db.tickets.values()) {
                if (ticket.state == TicketState.WAITING && pool.equals(ticket.pool) && ticket.expiresAt > now) ids.add(ticket.id);
            }
            ids.sort(java.util.Comparator.comparingLong(id -> db.tickets.get(id).actor));
            return ids.size() > limit ? ids.subList(0, limit) : ids;
        }
        public long actorOf(String ticketId) { return db.tickets.get(ticketId).actor; }
    }
}
