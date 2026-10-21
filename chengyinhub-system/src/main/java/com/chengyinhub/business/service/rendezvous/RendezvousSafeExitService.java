package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.HandoffState;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.HandoffStateQuery;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.LaunchReceipt;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.CurrentTermination;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.OngoingVerdict;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.ParticipantVerification;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.Phase;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.Termination;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.SoloGoalCatalog;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.SoloLauncher;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.SoloResult;
import com.chengyinhub.business.service.rendezvous.RendezvousSoloContinuation.Plan;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.MatchState;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.TicketState;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Transaction;

/**
 * Safe exit for one authenticated participant. Not a Spring bean.
 * Solo start runs only when C's {@code handoffStateFor} returns NOT_STARTED and both the
 * published solo goal and the authoritative launcher were supplied. Completion is never inferred.
 * No team lifecycle change, reward, penalty, or difficulty decision.
 */
public final class RendezvousSafeExitService {
    public enum Mode { DISABLED, ISOLATED_CONTRACT_EVALUATION }

    public interface Store {
        <T> T transaction(Function<Tx, T> work);
    }

    /** Consent reads and writes fail closed until A's guard transaction is bound. */
    public interface Tx extends Transaction {
        ExitRow find(String ticketId);
        void insert(ExitRow row);
        void markLaunch(ExitRow row);
    }

    public static final class ExitRow {
        public String ticketId;
        public long actorId;
        public String matchId;
        public String choice;
        public String phase;
        public String handoff;
        public String soloState;
        public String soloBlock;
        public String goalRef;
        public String launchReceipt;
        public int resumed;
        public long createdAt;

        public ExitRow copy() {
            ExitRow next = new ExitRow();
            next.ticketId = ticketId;
            next.actorId = actorId;
            next.matchId = matchId;
            next.choice = choice;
            next.phase = phase;
            next.handoff = handoff;
            next.soloState = soloState;
            next.soloBlock = soloBlock;
            next.goalRef = goalRef;
            next.launchReceipt = launchReceipt;
            next.resumed = resumed;
            next.createdAt = createdAt;
            return next;
        }
    }

    public static final class View {
        public final String ticketId, choice, phase, handoff, soloState, soloBlock, goalRef, launchReceipt;
        public final boolean decided, exitAllowed, soloAllowed, resumed, completed;
        View(String ticketId, boolean decided, boolean exitAllowed, boolean soloAllowed, String choice,
             String phase, String handoff, String soloState, String soloBlock, String goalRef,
             String launchReceipt, boolean resumed, boolean completed) {
            this.ticketId = ticketId;
            this.decided = decided;
            this.exitAllowed = exitAllowed;
            this.soloAllowed = soloAllowed;
            this.choice = choice;
            this.phase = phase;
            this.handoff = handoff;
            this.soloState = soloState;
            this.soloBlock = soloBlock;
            this.goalRef = goalRef;
            this.launchReceipt = launchReceipt;
            this.resumed = resumed;
            this.completed = completed;
        }
    }

    private static final class Claim {
        final ExitRow row;
        final boolean launch;
        Claim(ExitRow row, boolean launch) { this.row = row; this.launch = launch; }
    }

    private final Mode mode;
    private final Store store;
    private final ParticipantVerification participants;
    private final CurrentTermination termination;
    private final HandoffStateQuery handoff;
    private final SoloGoalCatalog catalog;
    private final SoloLauncher launcher;
    private final SoloResult results;
    private final Clock clock;

    public RendezvousSafeExitService(Mode mode, Store store, ParticipantVerification participants,
                                     CurrentTermination termination, HandoffStateQuery handoff,
                                     SoloGoalCatalog catalog, SoloLauncher launcher, SoloResult results, Clock clock) {
        this.mode = Objects.requireNonNull(mode);
        this.store = Objects.requireNonNull(store);
        this.participants = Objects.requireNonNull(participants);
        this.termination = Objects.requireNonNull(termination);
        this.handoff = handoff;
        this.catalog = catalog;
        this.launcher = launcher;
        this.results = results;
        this.clock = Objects.requireNonNull(clock);
    }

    public View options(long actor, String ticketId) {
        enabled();
        actor(actor);
        ticket(ticketId);
        return store.transaction(tx -> {
            ExitRow existing = tx.find(ticketId);
            if (existing != null) {
                if (existing.actorId != actor) throw new Rejection("NOT_FOUND");
                return view(existing);
            }
            return openView(tx, actor, ticketId);
        });
    }

    public View choose(long actor, String ticketId, String choice) {
        enabled();
        actor(actor);
        ticket(ticketId);
        if (!"EXIT".equals(choice) && !"SOLO".equals(choice)) throw new Rejection("INVALID_INPUT");
        Claim claim = store.transaction(tx -> claim(tx, actor, ticketId, choice));
        if (!claim.launch) return view(claim.row);
        return finishLaunch(actor, claim.row);
    }

    private View openView(Tx tx, long actor, String ticketId) {
        OngoingVerdict verdict = participants.verifyParticipants(tx, actor, ticketId, null, null, false, clock.millis());
        Phase phase = phase(verdict);
        String matchId = requiredMatch(phase, verdict.matchId);
        HandoffState state = handoffState(matchId);
        Plan plan = RendezvousSoloContinuation.plan(state, goal(tx, actor, matchId, state), launcher != null);
        return new View(ticketId, false, true, plan.start, null, phase.name(), handoffName(state),
                "NOT_REQUESTED", plan.start ? null : plan.block, plan.start ? plan.goalRef : null,
                null, false, false);
    }

    private Claim claim(Tx tx, long actor, String ticketId, String choice) {
        ExitRow existing = tx.find(ticketId);
        if (existing != null) {
            if (existing.actorId != actor) throw new Rejection("NOT_FOUND");
            if (!choice.equals(existing.choice)) throw new Rejection("EXIT_CONFLICT");
            return new Claim(existing, false);
        }
        long now = clock.millis();
        OngoingVerdict verdict = participants.verifyParticipants(tx, actor, ticketId, null, null, false, now);
        Phase phase = phase(verdict);
        String matchId = requiredMatch(phase, verdict.matchId);
        HandoffState state = handoffState(matchId);
        String goalRef = null;
        if ("SOLO".equals(choice)) {
            Plan plan = RendezvousSoloContinuation.plan(state, goal(tx, actor, matchId, state), launcher != null);
            if (!plan.start) throw new Rejection(plan.rejectionCode);
            goalRef = plan.goalRef;
        }
        // A's exit writes the consent rows. Call it before the exit ledger insert so a NOT_FOUND writes nothing.
        termination.terminateCurrent(tx, actor, null, ticketId, Termination.EXIT, now);
        ExitRow row = row(ticketId, actor, matchId, choice, phase, state,
                "SOLO".equals(choice) ? "STARTING" : "NOT_REQUESTED", null, goalRef, null, 0);
        tx.insert(row);
        return new Claim(row, "SOLO".equals(choice));
    }

    private View finishLaunch(long actor, ExitRow claimed) {
        String receipt = null;
        int resumed = 0;
        boolean unknown = false;
        try {
            LaunchReceipt got = launcher.startOrResume(actor, claimed.goalRef, claimed.ticketId);
            if (got == null || !RendezvousSoloContinuation.ref(got.receiptId)) unknown = true;
            else {
                receipt = got.receiptId;
                resumed = got.resumed ? 1 : 0;
            }
        } catch (RuntimeException failed) {
            unknown = true;
        }
        final boolean unresolved = unknown;
        final String savedReceipt = receipt;
        final int savedResumed = resumed;
        ExitRow updated = store.transaction(tx -> {
            ExitRow current = tx.find(claimed.ticketId);
            if (current == null || current.actorId != actor) throw new IllegalStateException("W16_EXIT_CLAIM_LOST");
            if (!"STARTING".equals(current.soloState)) return current;
            ExitRow next = current.copy();
            next.soloState = unresolved ? "NOT_STARTED" : "STARTED";
            next.soloBlock = unresolved ? "LAUNCH_UNKNOWN" : null;
            next.launchReceipt = unresolved ? null : savedReceipt;
            next.resumed = unresolved ? 0 : savedResumed;
            tx.markLaunch(next);
            return next;
        });
        return view(updated);
    }

    private String goal(Tx tx, long actor, String matchId, HandoffState state) {
        if (catalog == null || state != HandoffState.NOT_STARTED) return null;
        return catalog.publishedSoloGoal(tx, actor, matchId);
    }

    /** No match means handoff never started, so C is not asked and no request id is minted.
     *  A missing or unrecognized answer is not NOT_STARTED. */
    private HandoffState handoffState(String matchId) {
        if (matchId == null) return HandoffState.NOT_STARTED;
        if (handoff == null) return null;
        HandoffState state = handoff.handoffStateFor(matchId);
        if (state == HandoffState.NOT_STARTED || state == HandoffState.UNKNOWN
                || state == HandoffState.CREATED_JOIN_UNCONFIRMED || state == HandoffState.COMPLETE) return state;
        return null;
    }

    private static String handoffName(HandoffState state) {
        return state == null ? HandoffState.UNKNOWN.name() : state.name();
    }

    private View view(ExitRow row) {
        String soloState = row.soloState;
        String block = row.soloBlock;
        boolean completed = false;
        if ("STARTING".equals(soloState)) {
            soloState = "NOT_STARTED";
            if (block == null) block = "LAUNCH_UNKNOWN";
        } else if ("STARTED".equals(row.soloState) && RendezvousSoloContinuation.ref(row.launchReceipt) && results != null) {
            try {
                if (RendezvousSoloContinuation.completed(results.read(row.launchReceipt))) {
                    completed = true;
                    soloState = "COMPLETED";
                }
            } catch (RuntimeException ignored) {
                completed = false;
            }
        }
        return new View(row.ticketId, true, false, false, row.choice, row.phase, row.handoff,
                soloState, block, row.goalRef, row.launchReceipt, row.resumed == 1, completed);
    }

    private ExitRow row(String ticketId, long actor, String matchId, String choice, Phase phase,
                        HandoffState state, String soloState, String soloBlock, String goalRef,
                        String launchReceipt, int resumed) {
        ExitRow row = new ExitRow();
        row.ticketId = ticketId;
        row.actorId = actor;
        row.matchId = matchId;
        row.choice = choice;
        row.phase = phase.name();
        row.handoff = handoffName(state);
        row.soloState = soloState;
        row.soloBlock = soloBlock;
        row.goalRef = goalRef;
        row.launchReceipt = launchReceipt;
        row.resumed = resumed;
        row.createdAt = clock.millis();
        return row;
    }

    /** Reads A's verdict. Does not treat {@code safe=false} as a refused exit. */
    private static Phase phase(OngoingVerdict verdict) {
        if (verdict == null || !verdict.owned || verdict.ticketState == null) throw new Rejection("NOT_FOUND");
        if (verdict.ticketState == TicketState.CANCELLED || verdict.ticketState == TicketState.EXPIRED) throw new Rejection("EXIT_UNAVAILABLE");
        if (verdict.ticketState == TicketState.WAITING) {
            if (verdict.matchId != null) throw new Rejection("EXIT_UNAVAILABLE");
            return Phase.WAITING;
        }
        if (verdict.ticketState != TicketState.RESERVED && verdict.ticketState != TicketState.ACCEPTED) throw new Rejection("EXIT_UNAVAILABLE");
        if (verdict.mutual || verdict.matchState == MatchState.MUTUAL_ACCEPTED) return Phase.MUTUAL;
        if (verdict.matchState == MatchState.PROPOSED) return Phase.ONE_SIDED;
        throw new Rejection("EXIT_UNAVAILABLE");
    }

    private static String requiredMatch(Phase phase, String raw) {
        if (raw == null || raw.isEmpty()) {
            if (phase == Phase.WAITING) return null;
            throw new Rejection("EXIT_UNAVAILABLE");
        }
        if (!RendezvousSoloContinuation.ref(raw) || phase == Phase.WAITING) throw new Rejection("EXIT_UNAVAILABLE");
        return raw;
    }

    private void enabled() {
        if (mode != Mode.ISOLATED_CONTRACT_EVALUATION) throw new Rejection("W16_EXIT_DISABLED");
    }

    private static void actor(long actor) { if (actor <= 0) throw new Rejection("AUTH_REQUIRED"); }

    private static void ticket(String value) {
        if (!RendezvousSoloContinuation.ref(value)) throw new Rejection("INVALID_INPUT");
    }
}
