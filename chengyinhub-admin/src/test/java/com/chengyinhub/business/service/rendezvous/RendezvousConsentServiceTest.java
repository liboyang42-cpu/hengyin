package com.chengyinhub.business.service.rendezvous;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

class RendezvousConsentServiceTest {
    MemoryStore store;
    MutableClock clock;
    SafetyPolicy safety;
    RendezvousConsentService service;
    @BeforeEach void setup() {
        store = new MemoryStore(); clock = new MutableClock(); safety = new SafetyPolicy();
        service = new RendezvousConsentService(store, safety, clock);
    }
    View join(long actor) { return service.join(actor, "public-pool", "reviewed-v1", 1000); }
    View[] pair() {
        View a = join(1), b = join(2);
        service.propose(a.ticketId, b.ticketId, "approved-public-place");
        return new View[] { service.read(1, a.ticketId), service.read(2, b.ticketId) };
    }
    void code(String code, Runnable work) { assertEquals(code, assertThrows(Rejection.class, work::run).getCode()); }

    @Test void oneAcceptanceCannotStandForBothPeople() {
        View[] p = pair();
        View first = service.accept(1, p[0].ticketId, p[0].version);
        assertEquals(MatchState.PROPOSED, first.matchState);
        assertTrue(first.acceptedByMe); assertFalse(first.acceptedByOther);
        View other = service.read(2, p[1].ticketId);
        assertFalse(other.acceptedByMe); assertTrue(other.acceptedByOther);
        View both = service.accept(2, other.ticketId, other.version);
        assertEquals(MatchState.MUTUAL_ACCEPTED, both.matchState);
        assertTrue(both.acceptedByMe); assertTrue(both.acceptedByOther);
        assertEquals(2, store.tickets.size()); assertEquals(1, store.matches.size());
    }
    @Test void repeatedAcceptanceKeepsOriginalReceipt() {
        View[] p = pair(); View first = service.accept(1, p[0].ticketId, p[0].version);
        Long receipt = store.matches.get(first.matchId).firstAcceptedAt;
        clock.now += 20;
        View again = service.accept(1, p[0].ticketId, p[0].version);
        assertEquals(first.version, again.version); assertEquals(receipt, store.matches.get(first.matchId).firstAcceptedAt);
    }
    @Test void staleUnacceptedReviewRequiresReadback() {
        View[] p = pair(); service.accept(1, p[0].ticketId, 1);
        code("STALE_PROPOSAL", () -> service.accept(2, p[1].ticketId, 1));
        assertFalse(service.read(2, p[1].ticketId).acceptedByMe);
    }
    @Test void strangerCannotReadAcceptOrCancelAndCannotDistinguishMissingTicket() {
        View[] p = pair();
        code("NOT_FOUND", () -> service.read(3, p[0].ticketId));
        code("NOT_FOUND", () -> service.accept(3, p[0].ticketId, 1));
        code("NOT_FOUND", () -> service.cancel(3, p[0].ticketId));
        code("NOT_FOUND", () -> service.read(3, "missing"));
        assertEquals(MatchState.PROPOSED, service.read(1, p[0].ticketId).matchState);
    }
    @Test void declineReleasesBothReservationsWithoutImplicitRequeueOrPenalty() {
        View[] p = pair(); service.cancel(2, p[1].ticketId);
        assertNull(store.activeTicket(1)); assertNull(store.activeTicket(2));
        assertEquals(MatchState.CANCELLED, service.read(1, p[0].ticketId).matchState);
        assertNull(service.read(1, p[0].ticketId).publicPlace);
        assertNotEquals(p[0].ticketId, join(1).ticketId);
        assertNotEquals(p[1].ticketId, join(2).ticketId);
    }
    @Test void withdrawalAfterMutualAcceptanceIsStillAllowed() {
        View[] p = pair(); service.accept(1, p[0].ticketId, 1);
        service.accept(2, p[1].ticketId, 2);
        View closed = service.cancel(1, p[0].ticketId);
        assertEquals(MatchState.CANCELLED, closed.matchState);
        assertEquals(closed.version, service.cancel(1, p[0].ticketId).version);
        assertEquals(MatchState.CANCELLED, service.accept(2, p[1].ticketId, 3).matchState);
    }
    @Test void expiryAtExactBoundaryCommitsBothReleasesEvenOnAccept() {
        View[] p = pair(); clock.now += 1000;
        assertEquals(MatchState.EXPIRED, service.accept(1, p[0].ticketId, 1).matchState);
        assertNull(store.activeTicket(1)); assertNull(store.activeTicket(2));
        assertNull(service.read(2, p[1].ticketId).publicPlace);
    }
    @Test void blockBeforeAcceptanceStopsAndRedactsProposal() {
        View[] p = pair(); safety.blocked = true;
        View stopped = service.accept(1, p[0].ticketId, 1);
        assertEquals(MatchState.SAFETY_STOPPED, stopped.matchState);
        assertFalse(stopped.acceptedByMe); assertNull(stopped.publicPlace);
        assertNull(store.activeTicket(2));
    }
    @Test void authoritativeSafetyEventStopsWithoutWaitingForRead() {
        View[] p = pair(); service.stopForSafety(2);
        assertNull(store.activeTicket(1)); assertNull(store.activeTicket(2));
        assertEquals(MatchState.SAFETY_STOPPED, store.matches.get(p[0].matchId).state);
    }
    @Test void placeClosureAndEligibilityRevocationStopExistingConsent() {
        View[] p = pair(); safety.placeAllowed = false;
        assertEquals(MatchState.SAFETY_STOPPED, service.read(1, p[0].ticketId).matchState);
        setup(); p = pair(); safety.eligible = false;
        assertEquals(MatchState.SAFETY_STOPPED, service.read(2, p[1].ticketId).matchState);
    }
    @Test void rejectImplicitOptInSelfPairDifferentPoolAndUnsafePlace() {
        View a = join(1); View b = service.join(2, "other-pool", "reviewed-v1", 1000);
        code("PAIR_UNAVAILABLE", () -> service.propose(a.ticketId, "missing", "approved-public-place"));
        code("PAIR_UNAVAILABLE", () -> service.propose(a.ticketId, a.ticketId, "approved-public-place"));
        code("PAIR_UNAVAILABLE", () -> service.propose(a.ticketId, b.ticketId, "approved-public-place"));
        service.cancel(2, b.ticketId); View c = join(2); safety.placeAllowed = false;
        code("PAIR_UNAVAILABLE", () -> service.propose(a.ticketId, c.ticketId, "private-home"));
        assertTrue(store.matches.isEmpty());
    }
    @Test void duplicateJoinDoesNotExtendExpiryOrOverwritePool() {
        View a = join(1); clock.now += 100;
        assertEquals(a.ticketId, join(1).ticketId); assertEquals(a.expiresAt, join(1).expiresAt);
        code("ACTIVE_TICKET_EXISTS", () -> service.join(1, "other-pool", "reviewed-v1", 1000));
        clock.now += 900; assertNotEquals(a.ticketId, join(1).ticketId);
    }
    @Test void rejectUnauthenticatedAndUnreviewedEligibility() {
        code("AUTH_REQUIRED", () -> join(0)); code("INVALID_EXPIRY", () -> service.join(1, "pool", "v1", 0));
        safety.eligible = false; code("NOT_ELIGIBLE", () -> join(1)); assertTrue(store.tickets.isEmpty());
    }
    @Test void restartUsesStoreAndDoesNotInventConsent() {
        View[] p = pair(); service.accept(1, p[0].ticketId, 1);
        RendezvousConsentService restarted = new RendezvousConsentService(store, safety, clock);
        View restored = restarted.read(2, p[1].ticketId);
        assertFalse(restored.acceptedByMe); assertTrue(restored.acceptedByOther);
        assertEquals(MatchState.PROPOSED, restored.matchState);
    }
    @Test void concurrentPairingAcrossServiceInstancesReservesEachActorOnce() throws Exception {
        View a = join(1), b = join(2), c = join(3);
        RendezvousConsentService other = new RendezvousConsentService(store, safety, clock);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> one = () -> { start.await(); try { service.propose(a.ticketId, b.ticketId, "approved-public-place"); return true; } catch (Rejection e) { return false; } };
            Callable<Boolean> two = () -> { start.await(); try { other.propose(a.ticketId, c.ticketId, "approved-public-place"); return true; } catch (Rejection e) { return false; } };
            Future<Boolean> x = executor.submit(one), y = executor.submit(two); start.countDown();
            assertNotEquals(x.get(2, TimeUnit.SECONDS), y.get(2, TimeUnit.SECONDS));
            assertEquals(1, store.matches.size());
            assertEquals(2, store.tickets.values().stream().filter(t -> t.state == TicketState.RESERVED).count());
        } finally { executor.shutdownNow(); }
    }
    @Test void rejectedRejoinCommitsRevokedEligibilityCleanupForBothActors() {
        View[] p = pair(); safety.eligible = false;
        code("NOT_ELIGIBLE", () -> join(1));
        assertNull(store.activeTicket(1)); assertNull(store.activeTicket(2));
        assertEquals(MatchState.SAFETY_STOPPED, store.matches.get(p[0].matchId).state);
    }
    @Test void acceptExpiredWaitingTicketCommitsExpiryInsteadOfRollingItBack() {
        View waiting = join(1); clock.now += 1000;
        assertEquals(TicketState.EXPIRED, service.accept(1, waiting.ticketId, 0).ticketState);
        assertNull(store.activeTicket(1));
    }
    @Test void incompatiblePreferencesCannotReserveAndChangesStopProposal() {
        View a = join(1), b = join(2); safety.compatible = false;
        code("PAIR_UNAVAILABLE", () -> service.propose(a.ticketId, b.ticketId, "approved-public-place"));
        assertTrue(store.matches.isEmpty()); safety.compatible = true;
        service.propose(a.ticketId, b.ticketId, "approved-public-place"); safety.compatible = false;
        assertEquals(MatchState.SAFETY_STOPPED, service.read(1, a.ticketId).matchState);
    }
    @Test void projectionContainsNoPartnerIdentityOrSecretFields() {
        Set<String> fields = new HashSet<>();
        for (java.lang.reflect.Field f : View.class.getFields()) fields.add(f.getName());
        assertEquals(new HashSet<>(Arrays.asList("ticketId", "matchId", "publicPlace", "ticketState", "matchState", "acceptedByMe", "acceptedByOther", "expiresAt", "version")), fields);
    }
    static final class MutableClock extends Clock {
        long now = 10000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    static final class SafetyPolicy implements Safety {
        boolean eligible = true, blocked, placeAllowed = true, compatible = true;
        public boolean eligible(long actor, String pool, String version, long now) { return eligible; }
        public boolean compatible(long a, long b, String pool, long now) { return compatible; }
        public boolean blocked(long a, long b) { return blocked; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return placeAllowed && place.equals("approved-public-place"); }
    }
    /** Test double only: validates service logic, not database concurrency or durability. */
    static final class MemoryStore implements Store, Transaction {
        Map<String, Ticket> tickets = new HashMap<>(); Map<String, Match> matches = new HashMap<>();
        public synchronized <T> T transaction(Function<Transaction, T> work) {
            Map<String, Ticket> beforeTickets = new HashMap<>(tickets); Map<String, Match> beforeMatches = new HashMap<>(matches);
            try { return work.apply(this); } catch (RuntimeException error) { tickets = beforeTickets; matches = beforeMatches; throw error; }
        }
        public Ticket activeTicket(long actor) { return tickets.values().stream().filter(t -> t.actor == actor && t.active()).findFirst().orElse(null); }
        public Ticket ticket(String id) { return tickets.get(id); }
        public Match match(String id) { return matches.get(id); }
        public void save(Ticket ticket) { tickets.put(ticket.id, ticket); }
        public void save(Match match) { matches.put(match.id, match); }
    }
}
