package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Match;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.MatchState;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Ticket;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.TicketState;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Transaction;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.CurrentTermination;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.HandoffState;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.HandoffStateQuery;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.LaunchReceipt;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.OngoingVerdict;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.Phase;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.SoloResult;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.Termination;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.ExitRow;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.Mode;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.Store;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.Tx;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.View;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory doubles for A, C, and the missing solo launcher. Not a production wiring. */
class RendezvousSafeExitServiceTest {
    Memory memory;
    Ports ports;
    RendezvousSafeExitService service;

    @BeforeEach void setup() {
        memory = new Memory();
        ports = new Ports();
        service = service(ports);
    }

    @Test void waitingOneSidedAndMutualExitReleaseOnlyTheCaller() {
        View waiting = exit(Phase.WAITING, null, HandoffState.NOT_STARTED);
        assertEquals(0, ports.handoffReads);
        assertEquals("NOT_STARTED", waiting.handoff);
        fresh();
        exit(Phase.ONE_SIDED, "match-1", HandoffState.NOT_STARTED);
        assertEquals("match-1", ports.queriedMatchId);
        fresh();
        View mutual = exit(Phase.MUTUAL, "match-2", HandoffState.NOT_STARTED);
        assertEquals("EXIT", mutual.choice);
        assertFalse(mutual.completed);
        assertEquals(0, ports.launches);
        assertNull(mutual.goalRef);
        assertEquals("match-2", ports.queriedMatchId);
        assertEquals(0, ports.mintedRequestIds);
    }

    @Test void missingGoalAndLauncherStillExitsWithoutCompletion() {
        ports.goal = null;
        ports.launcherPresent = false;
        service = service(ports);
        code("SOLO_GOAL_UNAVAILABLE", () -> service.choose(1, "ticket-1", "SOLO"));
        assertEquals(0, memory.rows.size());
        assertEquals(0, ports.releases);
        assertEquals(0, ports.launches);
        View exited = service.choose(1, "ticket-1", "EXIT");
        assertTrue(exited.decided);
        assertEquals("NOT_REQUESTED", exited.soloState);
        assertFalse(exited.completed);
        assertEquals(1, ports.releases);
        View again = service.choose(1, "ticket-1", "EXIT");
        assertEquals(exited.ticketId, again.ticketId);
        assertEquals(1, ports.releases);
        assertEquals(0, ports.deductions);
    }

    @Test void unknownAndUnconfirmedAndCompleteBlockSoloWithoutANewRequest() {
        blockSolo(HandoffState.UNKNOWN, "HANDOFF_UNKNOWN");
        fresh();
        blockSolo(HandoffState.CREATED_JOIN_UNCONFIRMED, "HANDOFF_JOIN_UNCONFIRMED");
        fresh();
        blockSolo(HandoffState.COMPLETE, "HANDOFF_COMPLETE");
        View exited = service.choose(1, "ticket-1", "EXIT");
        assertEquals("COMPLETE", exited.handoff);
        assertEquals("NOT_REQUESTED", exited.soloState);
        assertFalse(exited.completed);
        assertEquals(0, ports.launches);
        assertEquals(1, ports.releases);
        assertEquals("match-1", ports.queriedMatchId);
        assertEquals(0, ports.mintedRequestIds);
    }

    @Test void nullHandoffAnswerIsNotNotStarted() {
        ports.verdict = verdict(Phase.MUTUAL, "match-1");
        ports.goal = "published-solo-1";
        ports.answer = null;
        code("HANDOFF_UNKNOWN", () -> service.choose(1, "ticket-1", "SOLO"));
        assertEquals(0, ports.launches);
        assertEquals(0, memory.rows.size());
    }

    @Test void meetingRaceToUnknownBlocksSolo() {
        ports.verdict = verdict(Phase.MUTUAL, "match-1");
        ports.answer = HandoffState.NOT_STARTED;
        ports.goal = "published-solo-1";
        ports.flipTo = HandoffState.UNKNOWN;
        code("HANDOFF_UNKNOWN", () -> service.choose(1, "ticket-1", "SOLO"));
        assertEquals(0, ports.launches);
        View exited = service.choose(1, "ticket-1", "EXIT");
        assertEquals("UNKNOWN", exited.handoff);
        assertFalse(exited.completed);
        assertEquals(0, ports.launches);
        assertEquals(1, ports.releases);
        assertEquals(0, ports.mintedRequestIds);
    }

    @Test void publishedSoloGoalStartsOnceAndDoesNotCompleteUntilAuthoritativeResult() {
        readySolo();
        View started = service.choose(1, "ticket-1", "SOLO");
        assertEquals("STARTED", started.soloState);
        assertEquals("published-solo-1", started.goalRef);
        assertEquals("rcpt-1", started.launchReceipt);
        assertFalse(started.completed);
        assertEquals(1L, ports.launchedActor);
        assertEquals("ticket-1", ports.attemptId);
        assertEquals("match-1", ports.queriedMatchId);
        View replay = service.choose(1, "ticket-1", "SOLO");
        assertEquals(1, ports.launches);
        assertEquals(1, ports.releases);
        assertEquals(1, ports.handoffReads);
        assertFalse(replay.completed);
        ports.status = SoloResult.Status.COMPLETED;
        View done = service.options(1, "ticket-1");
        assertTrue(done.completed);
        assertEquals("COMPLETED", done.soloState);
        assertEquals(1, ports.launches);
        code("EXIT_CONFLICT", () -> service.choose(1, "ticket-1", "EXIT"));
        assertEquals(1, ports.launches);
    }

    @Test void concurrentSoloClaimsCallTheLauncherOnce() throws Exception {
        readySolo();
        ports.blockLauncher = true;
        AtomicReference<View> first = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try { first.set(service.choose(1, "ticket-1", "SOLO")); }
            catch (Throwable error) { failure.set(error); }
        });
        starter.start();
        assertTrue(ports.entered.await(5, TimeUnit.SECONDS));
        View second = service.choose(1, "ticket-1", "SOLO");
        ports.releaseLauncher.countDown();
        starter.join(5000);
        assertNull(failure.get());
        assertNotNull(first.get());
        assertEquals(1, ports.launches);
        assertFalse(second.completed);
        assertFalse(first.get().completed);
        assertEquals(0, ports.mintedRequestIds);
    }

    @Test void lostLauncherReceiptIsNotCompletionAndIsNotRetried() {
        readySolo();
        ports.receipt = null;
        View unknown = service.choose(1, "ticket-1", "SOLO");
        assertEquals("NOT_STARTED", unknown.soloState);
        assertEquals("LAUNCH_UNKNOWN", unknown.soloBlock);
        assertFalse(unknown.completed);
        service.choose(1, "ticket-1", "SOLO");
        assertEquals(1, ports.launches);
        assertEquals(0, ports.deductions);
    }

    @Test void strangerAndTerminalAndPeerAreNotEnrolled() {
        ports.verdict = verdict(Phase.WAITING, null, false);
        code("NOT_FOUND", () -> service.choose(1, "ticket-1", "EXIT"));
        ports.verdict = verdict(Phase.TERMINAL, "match-1");
        code("EXIT_UNAVAILABLE", () -> service.choose(1, "ticket-1", "EXIT"));
        ports.verdict = verdict(Phase.ABSENT, null);
        code("NOT_FOUND", () -> service.options(1, "ticket-1"));
        assertEquals(0, ports.releases);
        assertEquals(0, ports.launches);
        readySolo();
        service.choose(1, "ticket-1", "SOLO");
        code("NOT_FOUND", () -> service.options(2, "ticket-1"));
        assertEquals(1L, ports.launchedActor);
        assertNotEquals(2L, ports.releasedActor);
    }

    @Test void disabledServiceAndBadChoiceDoNothing() {
        RendezvousSafeExitService disabled = new RendezvousSafeExitService(Mode.DISABLED, memory, ports, ports, ports, ports, ports, ports, ports.clock);
        code("W16_EXIT_DISABLED", () -> disabled.choose(1, "ticket-1", "EXIT"));
        code("INVALID_INPUT", () -> service.choose(1, "ticket-1", "exit"));
        assertEquals(0, ports.releases);
    }

    @Test void restartReadDoesNotAskForANewVerdictOrHandoff() {
        View exited = service.choose(1, "ticket-1", "EXIT");
        ports.failVerification = true;
        int reads = ports.handoffReads;
        RendezvousSafeExitService restarted = service(ports);
        View read = restarted.options(1, "ticket-1");
        assertEquals(exited.choice, read.choice);
        assertEquals(exited.phase, read.phase);
        assertFalse(read.completed);
        assertEquals(1, ports.verifications);
        assertEquals(reads, ports.handoffReads);
    }

    @Test void consentMethodsOnTheExitTransactionFailClosed() {
        assertEquals("W16_CONSENT_TRANSACTION_NOT_BOUND", assertThrows(IllegalStateException.class,
                () -> memory.transaction(tx -> { tx.ticket("ticket-1"); return null; })).getMessage());
    }

    @Test void projectionHasNoPenaltyDifficultyRewardPeerOrTeam() {
        for (Field field : View.class.getFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("penal") || name.contains("difficult") || name.contains("reward")
                    || name.contains("peer") || name.contains("team") || name.contains("secret"));
        }
    }

    private void blockSolo(HandoffState state, String code) {
        ports.verdict = verdict(Phase.MUTUAL, "match-1");
        ports.answer = state;
        ports.goal = "published-solo-1";
        code(code, () -> service.choose(1, "ticket-1", "SOLO"));
        assertEquals(0, ports.launches);
        assertEquals(0, ports.releases);
        assertEquals(0, memory.rows.size());
        assertEquals("match-1", ports.queriedMatchId);
        assertEquals(0, ports.mintedRequestIds);
    }

    private View exit(Phase phase, String matchId, HandoffState state) {
        ports.verdict = verdict(phase, matchId);
        ports.answer = state;
        View view = service.choose(1, "ticket-1", "EXIT");
        assertTrue(view.decided);
        assertEquals(phase.name(), view.phase);
        assertEquals(state.name(), view.handoff);
        assertFalse(view.soloAllowed);
        assertFalse(view.completed);
        assertEquals(1L, ports.releasedActor);
        assertNull(ports.peer);
        assertEquals(Termination.EXIT, ports.reason);
        assertNull(ports.expectedPeer);
        assertFalse(ports.requireMutual);
        assertNull(ports.expectedMatch);
        assertEquals(5_000L, ports.seenNow);
        return view;
    }

    private void fresh() {
        memory = new Memory();
        ports = new Ports();
        service = service(ports);
    }

    private void readySolo() {
        ports.verdict = verdict(Phase.ONE_SIDED, "match-1");
        ports.answer = HandoffState.NOT_STARTED;
        ports.goal = "published-solo-1";
        ports.launcherPresent = true;
        service = service(ports);
    }

    static OngoingVerdict verdict(Phase phase, String matchId) { return verdict(phase, matchId, true); }

    static OngoingVerdict verdict(Phase phase, String matchId, boolean owned) {
        TicketState ticket = null;
        MatchState match = null;
        boolean mutual = false;
        switch (phase) {
            case WAITING: ticket = TicketState.WAITING; break;
            case ONE_SIDED: ticket = TicketState.RESERVED; match = MatchState.PROPOSED; break;
            case MUTUAL: ticket = TicketState.ACCEPTED; match = MatchState.MUTUAL_ACCEPTED; mutual = true; break;
            case TERMINAL: ticket = TicketState.CANCELLED; match = MatchState.CANCELLED; break;
            case ABSENT: owned = false; break;
            default: break;
        }
        return new OngoingVerdict(false, match != null, false, false, false, false, owned, mutual, "ticket-1", matchId, ticket, match);
    }

    private RendezvousSafeExitService service(Ports wired) {
        SoloLauncher launcher = wired.launcherPresent ? wired : null;
        return new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION, memory, wired, wired, wired, wired, launcher, wired, wired.clock);
    }

    private static void code(String expected, Runnable work) {
        assertEquals(expected, assertThrows(Rejection.class, work::run).getCode());
    }

    interface SoloLauncher extends RendezvousSafeExitPorts.SoloLauncher {}

    static final class Ports implements RendezvousSafeExitPorts.ParticipantVerification, CurrentTermination,
            HandoffStateQuery, RendezvousSafeExitPorts.SoloGoalCatalog, SoloLauncher, SoloResult {
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch releaseLauncher = new CountDownLatch(1);
        OngoingVerdict verdict = verdict(Phase.WAITING, null);
        HandoffState answer = HandoffState.NOT_STARTED;
        HandoffState flipTo;
        String goal;
        LaunchReceipt receipt = new LaunchReceipt("rcpt-1", false);
        Status status = Status.RUNNING;
        boolean launcherPresent = true;
        boolean blockLauncher;
        boolean failVerification;
        boolean failRelease;
        int releases;
        int launches;
        int verifications;
        int handoffReads;
        int deductions;
        int mintedRequestIds;
        long releasedActor = -1;
        long launchedActor = -1;
        Long peer = Long.valueOf(0);
        Termination reason;
        String expectedMatch;
        Long expectedPeer = Long.valueOf(0);
        boolean requireMutual = true;
        long seenNow = -1;
        String attemptId;
        String queriedMatchId;
        public OngoingVerdict verifyParticipants(Transaction tx, long actor, String ticketId, String expectedMatchId,
                                                  Long expectedPeer, boolean requireMutualAccepted, long now) {
            verifications++;
            this.expectedMatch = expectedMatchId;
            this.expectedPeer = expectedPeer;
            this.requireMutual = requireMutualAccepted;
            this.seenNow = now;
            if (failVerification) throw new IllegalStateException("verification must not run again");
            return verdict;
        }
        public OngoingVerdict terminateCurrent(Transaction tx, long actor, Long peer, String ticketId, Termination reason, long now) {
            if (failRelease) throw new IllegalStateException("RELEASE_FAILED");
            releases++;
            releasedActor = actor;
            this.peer = peer;
            this.reason = reason;
            this.seenNow = now;
            return verdict;
        }
        public HandoffState handoffStateFor(String matchId) {
            handoffReads++;
            queriedMatchId = matchId;
            return flipTo != null ? flipTo : answer;
        }
        public String publishedSoloGoal(Transaction tx, long actor, String matchId) { return goal; }
        public LaunchReceipt startOrResume(long actor, String goalRef, String attemptId) {
            launches++;
            launchedActor = actor;
            this.attemptId = attemptId;
            if (blockLauncher) {
                entered.countDown();
                try { assertTrue(releaseLauncher.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            }
            return receipt;
        }
        public Status read(String receiptId) { return "rcpt-1".equals(receiptId) ? status : Status.NOT_FOUND; }
    }

    static final class Memory implements Store {
        final Map<String, ExitRow> rows = new HashMap<>();
        public <T> T transaction(Function<Tx, T> work) {
            synchronized (rows) { return work.apply(new MemTx(rows)); }
        }
    }

    static final class MemTx implements Tx {
        final Map<String, ExitRow> rows;
        MemTx(Map<String, ExitRow> rows) { this.rows = rows; }
        public ExitRow find(String ticketId) {
            ExitRow row = rows.get(ticketId);
            return row == null ? null : row.copy();
        }
        public void insert(ExitRow row) {
            if (rows.putIfAbsent(row.ticketId, row.copy()) != null) throw new IllegalStateException("W16_STALE_OR_INVALID_WRITE");
        }
        public void markLaunch(ExitRow row) {
            ExitRow stored = rows.get(row.ticketId);
            if (stored == null || stored.actorId != row.actorId || !"STARTING".equals(stored.soloState))
                throw new IllegalStateException("W16_STALE_OR_INVALID_WRITE");
            rows.put(row.ticketId, row.copy());
        }
        public Ticket activeTicket(long actor) { throw new IllegalStateException("W16_CONSENT_TRANSACTION_NOT_BOUND"); }
        public Ticket ticket(String id) { throw new IllegalStateException("W16_CONSENT_TRANSACTION_NOT_BOUND"); }
        public Match match(String id) { throw new IllegalStateException("W16_CONSENT_TRANSACTION_NOT_BOUND"); }
        public void save(Ticket ticket) { throw new IllegalStateException("W16_CONSENT_TRANSACTION_NOT_BOUND"); }
        public void save(Match match) { throw new IllegalStateException("W16_CONSENT_TRANSACTION_NOT_BOUND"); }
    }
}
