package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Match;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.MatchState;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Ticket;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.TicketState;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Transaction;
import com.chengyinhub.business.service.rendezvous.RendezvousHandoffParticipantAdapter.BoundVerdict;
import com.chengyinhub.business.service.rendezvous.RendezvousHandoffRequest.Phase;
import com.chengyinhub.business.service.rendezvous.RendezvousMeetingService.HandoffState;
import com.chengyinhub.business.service.rendezvous.RendezvousMeetingService.State;
import com.chengyinhub.business.service.rendezvous.RendezvousMeetingServiceTest.Fixture;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import static org.junit.jupiter.api.Assertions.*;

/** Crash points, query-first recovery, evidence refusal, and the fail-closed A adapter. */
class RendezvousHandoffRecoveryTest {
    @Test void lostCreateResponseReturnsTheOriginalTeamWithoutCreatingAgain() {
        Fixture f = ready();
        RendezvousHandoffRequest claimed = request(f, "req-lost", true, null);
        f.db.save(claimed);
        f.db.save(f.db.meeting(f.match).state(State.IN_PROGRESS));
        f.team.created.put("req-lost", 77L);
        f.team.joined.add("req-lost");
        assertEquals(State.COMPLETED, f.service.handoff(1, f.first).state);
        assertEquals(77L, f.service.result(2, f.second).teamId.longValue());
        assertEquals(0, f.attempts());
        assertEquals(0, f.team.joins);
        assertEquals("req-lost", f.db.handoffRequest(f.match).requestId);
        assertEquals(HandoffState.COMPLETE, f.service.handoffStateFor(f.match));
    }

    @Test void knownCreateRetriesJoinOnTheSameRequest() {
        Fixture f = ready();
        f.team.failJoin = true;
        assertEquals(State.RECOVERY_REQUIRED, f.service.handoff(1, f.first).state);
        assertEquals(HandoffState.CREATED_JOIN_UNCONFIRMED, f.service.handoffStateFor(f.match));
        String requestId = f.db.handoffRequest(f.match).requestId;
        assertEquals(77L, f.db.meeting(f.match).teamId.longValue());
        assertNull(f.service.result(1, f.first).teamId);
        f.team.failJoin = false;
        assertEquals(State.COMPLETED, f.service.handoff(2, f.second).state);
        assertEquals(1, f.attempts());
        assertEquals(2, f.team.joins);
        assertEquals(requestId, f.db.handoffRequest(f.match).requestId);
        assertEquals(77L, f.service.result(1, f.first).teamId.longValue());
    }

    @Test void crashBeforeCallAndCrashAfterCallAreDistinguishable() {
        Fixture before = ready();
        request(before, "req-before", false, null);
        before.db.save(before.db.meeting(before.match).state(State.IN_PROGRESS));
        assertEquals(HandoffState.UNKNOWN, before.service.handoffStateFor(before.match));
        assertEquals(State.COMPLETED, before.service.handoff(1, before.first).state);
        assertEquals(1, before.attempts());
        assertEquals("req-before", before.db.handoffRequest(before.match).requestId);

        Fixture after = ready();
        request(after, "req-after", true, null);
        after.db.save(after.db.meeting(after.match).state(State.IN_PROGRESS));
        after.team.created.put("req-after", 88L);
        assertEquals(State.COMPLETED, after.service.handoff(1, after.first).state);
        assertEquals(0, after.attempts());
        assertEquals(1, after.team.joins);
        assertEquals(88L, after.db.meeting(after.match).teamId.longValue());
        assertEquals("req-after", after.db.handoffRequest(after.match).requestId);
    }

    @Test void dispatchedWithoutAuthoritativeResultStaysUnknown() {
        Fixture f = ready();
        request(f, "req-unknown", true, null);
        f.db.save(f.db.meeting(f.match).state(State.IN_PROGRESS));
        assertEquals(State.RECOVERY_REQUIRED, f.service.handoff(1, f.first).state);
        assertEquals(0, f.attempts());
        assertEquals(0, f.team.joins);
        assertEquals("CALL_RESULT_UNKNOWN", f.db.handoffRequest(f.match).blocker);
        assertEquals("req-unknown", f.db.handoffRequest(f.match).requestId);
        assertEquals(HandoffState.UNKNOWN, f.service.handoffStateFor(f.match));
        assertNull(f.db.meeting(f.match).teamId);
    }

    @Test void queryTimeoutDoesNotAllocateAnotherRequest() {
        Fixture f = ready();
        f.team.lookupThrows = true;
        assertEquals(State.RECOVERY_REQUIRED, f.service.handoff(1, f.first).state);
        String requestId = f.db.handoffRequest(f.match).requestId;
        assertEquals(0, f.attempts());
        assertEquals("TEAM_QUERY_TIMEOUT", f.db.handoffRequest(f.match).blocker);
        f.team.lookupThrows = false;
        assertEquals(State.COMPLETED, f.service.handoff(2, f.second).state);
        assertEquals(1, f.attempts());
        assertEquals(requestId, f.db.handoffRequest(f.match).requestId);
        assertEquals(1, f.db.handoffs.size());
    }

    @Test void twoCallersShareOneRequestAndOneTeam() throws Exception {
        Fixture f = ready();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = workers.submit(() -> { start.await(); f.service.handoff(1, f.first); return null; });
            Future<?> second = workers.submit(() -> { start.await(); f.service.handoff(2, f.second); return null; });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertEquals(1, f.attempts());
        assertEquals(1, f.db.handoffs.size());
        assertEquals(State.COMPLETED, f.service.result(1, f.first).state);
        assertEquals(77L, f.service.result(2, f.second).teamId.longValue());
    }

    @Test void revocationDuringRecoveryDoesNotClaimRollback() {
        Fixture f = ready();
        f.team.failJoin = true;
        f.service.handoff(1, f.first);
        assertEquals(77L, f.db.meeting(f.match).teamId.longValue());
        f.service.cancel(2, f.second);
        f.team.failJoin = false;
        assertEquals(State.RECOVERY_REQUIRED, f.service.handoff(1, f.first).state);
        assertEquals("CANCELLED_NOT_ROLLED_BACK", f.db.handoffRequest(f.match).blocker);
        assertEquals(77L, f.db.meeting(f.match).teamId.longValue());
        assertEquals(1, f.attempts());
        assertNull(f.service.result(1, f.first).teamId);

        Fixture blocked = ready();
        blocked.team.failJoin = true;
        blocked.service.handoff(1, blocked.first);
        blocked.blocked = true;
        blocked.team.failJoin = false;
        assertNotEquals(State.COMPLETED, blocked.service.handoff(1, blocked.first).state);
        assertEquals("SAFETY_REVOKED_NOT_ROLLED_BACK", blocked.db.handoffRequest(blocked.match).blocker);
        assertEquals(77L, blocked.db.meeting(blocked.match).teamId.longValue());
        assertEquals(1, blocked.attempts());
    }

    @Test void closedSafetyAndUnapprovedEvidenceNeverBecomeReady() {
        Fixture closed = ready();
        closed.ongoing = false;
        assertEquals(State.SAFETY_STOPPED, closed.service.handoff(1, closed.first).state);
        assertEquals(0, closed.attempts());
        assertNull(closed.db.handoffRequest(closed.match));
        assertEquals(HandoffState.NOT_STARTED, closed.service.handoffStateFor(closed.match));
        assertFalse(new RendezvousHandoffSafety.Closed().verifyCurrent("m", "t", 1, 2, 1));

        Fixture scan = new Fixture();
        scan.source = "SCAN";
        Rejection rejected = assertThrows(Rejection.class, () -> scan.service.issue(1, scan.first));
        assertEquals("GOAL_UNAVAILABLE", rejected.getCode());
        scan.source = "DOUBLE_CLICK";
        assertThrows(Rejection.class, () -> scan.service.issue(1, scan.first));
        Fixture blank = new Fixture();
        blank.ruleVersion = "";
        assertThrows(Rejection.class, () -> blank.service.issue(1, blank.first));
        assertEquals(0, scan.attempts());
        assertEquals(HandoffState.NOT_STARTED, scan.service.handoffStateFor(" "));
    }

    @Test void cancelWhileLookupIsPausedDoesNotDispatch() throws Exception {
        pausedRevocation(this::fresh, f -> f.service.cancel(2, f.second), "CANCELLED_NOT_ROLLED_BACK");
        pausedRevocation(this::createdButUnjoined, f -> f.service.cancel(2, f.second), "CANCELLED_NOT_ROLLED_BACK");
    }

    @Test void blockWhileLookupIsPausedDoesNotDispatch() throws Exception {
        pausedRevocation(this::fresh, f -> f.blocked = true, "SAFETY_REVOKED_NOT_ROLLED_BACK");
        pausedRevocation(this::createdButUnjoined, f -> f.blocked = true, "SAFETY_REVOKED_NOT_ROLLED_BACK");
    }

    @Test void expiryWhileLookupIsPausedDoesNotDispatch() throws Exception {
        pausedRevocation(this::fresh, f -> f.time.now = f.db.meeting(f.match).expiresAt, "EXPIRED_NOT_ROLLED_BACK");
        pausedRevocation(this::createdButUnjoined, f -> f.time.now = f.db.meeting(f.match).expiresAt, "EXPIRED_NOT_ROLLED_BACK");
    }

    @Test void participantAdapterUsesTheOpenTransactionAndLocalBinding() {
        Ticket first = new Ticket("t1", 1, "pool", "v1", 50_000, TicketState.ACCEPTED, "m1");
        Ticket second = new Ticket("t2", 2, "pool", "v1", 50_000, TicketState.ACCEPTED, "m1");
        Match match = new Match("m1", "t1", "t2", "public-place", 50_000, 1L, 1L, 2L, MatchState.MUTUAL_ACCEPTED);
        Transaction[] seen = new Transaction[1];
        boolean[] mutual = new boolean[1];
        Stub tx = new Stub(first, second, match);
        RendezvousHandoffParticipantAdapter adapter = new RendezvousHandoffParticipantAdapter(
                (open, actor, ticketId, matchId, peer, requireMutual, now) -> {
            if (seen[0] == null) {
                seen[0] = open;
                mutual[0] = requireMutual;
                assertEquals(1L, actor);
                assertEquals("t1", ticketId);
                assertEquals("m1", matchId);
                assertEquals(2L, peer);
                assertEquals(10L, now);
            }
            return new BoundVerdict(true, true, true);
        });
        assertTrue(adapter.verifyCurrent(tx, "m1", "t1", 1, 2, 10));
        assertSame(tx, seen[0]);
        assertTrue(mutual[0]);
        assertFalse(adapter.verifyCurrent(tx, "m1", "t1", 1, 9, 10));
        assertFalse(adapter.verifyCurrent(tx, "other", "t1", 1, 2, 10));
        assertFalse(adapter.verifyCurrent("m1", "t1", 1, 2, 10));
        assertFalse(new RendezvousHandoffParticipantAdapter((open, actor, ticketId, matchId, peer, requireMutual, now) -> null)
                .verifyCurrent(tx, "m1", "t1", 1, 2, 10));
        assertFalse(new RendezvousHandoffParticipantAdapter((open, actor, ticketId, matchId, peer, requireMutual, now) -> new BoundVerdict(true, false, true))
                .verifyCurrent(tx, "m1", "t1", 1, 2, 10));
        assertFalse(new RendezvousHandoffParticipantAdapter((open, actor, ticketId, matchId, peer, requireMutual, now) -> new BoundVerdict(true, true, false))
                .verifyCurrent(tx, "m1", "t1", 1, 2, 10));
        assertFalse(new RendezvousHandoffParticipantAdapter((open, actor, ticketId, matchId, peer, requireMutual, now) -> new BoundVerdict(false, true, true))
                .verifyCurrent(tx, "m1", "t1", 1, 2, 10));
        RendezvousHandoffParticipantAdapter exploded = new RendezvousHandoffParticipantAdapter((open, actor, ticketId, matchId, peer, requireMutual, now) -> {
            throw new IllegalStateException("A unavailable");
        });
        assertFalse(exploded.verifyCurrent(tx, "m1", "t1", 1, 2, 10));
    }

    @Test void legacyPortDoesNotCallPlayTeam() {
        RendezvousMeetingServiceTest.Teams teams = new RendezvousMeetingServiceTest.Teams();
        RendezvousHandoffTeamPort port = new ExistingPlayTeamMeetingHandoff(teams.service).recoveryPort();
        assertFalse(port.available());
        assertEquals(RendezvousHandoffLegacyTeamPort.BLOCKER, port.lookup("req").blocker);
        assertThrows(IllegalStateException.class, () -> port.create("req", 1, 2, 99));
        assertThrows(IllegalStateException.class, () -> port.join("req"));
        assertEquals(0, teams.creates);
        assertEquals(0, teams.joins);
    }

    private Fixture fresh() { return ready(); }

    /** Team already stored for this request. A paused lookup must not add another join. */
    private Fixture createdButUnjoined() {
        Fixture f = ready();
        RendezvousHandoffRequest request = new RendezvousHandoffRequest("req-join", f.match, 1, 2, 99,
                Phase.TEAM_CREATED, 77L, null, true, f.time.now, f.time.now);
        f.db.save(request);
        f.db.save(f.db.meeting(f.match).withTeam(77L, State.IN_PROGRESS));
        f.team.created.put("req-join", 77L);
        return f;
    }

    private void pausedRevocation(java.util.function.Supplier<Fixture> setup, Consumer<Fixture> revoke, String blocker) throws Exception {
        Fixture f = setup.get();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        f.team.beforeLookup = () -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("lookup not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RendezvousMeetingService.View> pending = pool.submit(() -> f.service.handoff(1, f.first));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            revoke.accept(f);
            release.countDown();
            RendezvousMeetingService.View view = pending.get(10, TimeUnit.SECONDS);
            assertNotEquals(State.COMPLETED, view.state);
            assertNull(view.teamId);
        } finally { pool.shutdownNow(); }
        assertEquals(0, f.attempts());
        assertEquals(0, f.team.joins);
        assertEquals(blocker, f.db.handoffRequest(f.match).blocker);
        if (f.team.created.containsKey("req-join")) assertEquals(77L, f.db.meeting(f.match).teamId.longValue());
        else assertFalse(f.db.handoffRequest(f.match).dispatched);
    }

    private static Fixture ready() { Fixture f = new Fixture(); f.ready(); return f; }

    private static RendezvousHandoffRequest request(Fixture f, String requestId, boolean dispatched, Long teamId) {
        RendezvousHandoffRequest request = new RendezvousHandoffRequest(requestId, f.match, 1, 2, 99, Phase.UNKNOWN,
                teamId, null, dispatched, f.time.now, f.time.now);
        f.db.save(request);
        return request;
    }

    private static final class Stub implements Transaction {
        final Ticket first, second; final Match match;
        Stub(Ticket first, Ticket second, Match match) { this.first = first; this.second = second; this.match = match; }
        public Ticket activeTicket(long actor) { return actor == 1 ? first : actor == 2 ? second : null; }
        public Ticket ticket(String id) { return "t1".equals(id) ? first : "t2".equals(id) ? second : null; }
        public Match match(String id) { return "m1".equals(id) ? match : null; }
        public void save(Ticket ticket) { throw new AssertionError("adapter must not write"); }
        public void save(Match match) { throw new AssertionError("adapter must not write"); }
    }
}
