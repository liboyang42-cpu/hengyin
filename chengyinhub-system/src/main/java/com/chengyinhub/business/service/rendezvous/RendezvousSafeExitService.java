package com.chengyinhub.business.service.rendezvous;

import java.time.Clock;
import java.util.Objects;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.MatchState;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.OngoingVerdict;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Store;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Termination;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.TicketState;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Transaction;

/**
 * Safe exit for one authenticated participant. Not a Spring bean.
 * Seat release and the exit row share the consent guard transaction.
 * Handoff answers are C5 {@code handoffStateFor} names. This type does not declare that enum.
 * The solo launcher is not connected unless the caller supplies one.
 * This service does not touch team rows and does not report a team rollback.
 */
public final class RendezvousSafeExitService {
    public enum Mode { DISABLED, ISOLATED_CONTRACT_EVALUATION }
    public enum Phase { WAITING, ONE_SIDED, MUTUAL }
    /** Launch may already have been sent. Not {@code NOT_STARTED}. Do not send it again. */
    static final String LAUNCH_PENDING = "UNKNOWN";

    /** C5 {@code dd143d03}: {@code RendezvousMeetingService.handoffStateFor(String)}. Returns that enum's name. */
    public interface HandoffStateQuery {
        String handoffStateFor(String matchId);
    }

    /** Published goals explicitly allowed as solo. Null means none. */
    public interface SoloGoalCatalog {
        String publishedSoloGoal(Transaction tx, long actor, String matchId);
    }

    /** One-actor launcher. Not registered in production. A blank receipt is not a start. */
    public interface SoloLauncher {
        LaunchReceipt startOrResume(long actor, String goalRef, String attemptId);
    }

    public static final class LaunchReceipt {
        public final String receiptId;
        public final boolean resumed;
        public LaunchReceipt(String receiptId, boolean resumed) {
            this.receiptId = receiptId;
            this.resumed = resumed;
        }
    }

    /** Authoritative result. Anything other than COMPLETED is not completion. */
    public interface SoloResult {
        enum Status { RUNNING, COMPLETED, NOT_FOUND }
        Status read(String receiptId);
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

        ExitRow copy() {
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
    private final Store guard;
    private final RendezvousConsentService consent;
    private final RendezvousSafeExitStore exits;
    private final HandoffStateQuery handoff;
    private final SoloGoalCatalog catalog;
    private final SoloLauncher launcher;
    private final SoloResult results;
    private final Clock clock;

    public RendezvousSafeExitService(Mode mode, Store guard, RendezvousConsentService consent,
                                     RendezvousSafeExitStore exits, HandoffStateQuery handoff,
                                     SoloGoalCatalog catalog, SoloLauncher launcher, SoloResult results, Clock clock) {
        this.mode = Objects.requireNonNull(mode);
        this.guard = Objects.requireNonNull(guard);
        this.consent = Objects.requireNonNull(consent);
        this.exits = Objects.requireNonNull(exits);
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
        return guard.transaction(tx -> {
            ExitRow existing = exits.find(ticketId);
            if (existing != null) {
                if (existing.actorId != actor) throw new Rejection("NOT_FOUND");
                return view(existing);
            }
            OngoingVerdict verdict = consent.verifyParticipants(tx, actor, ticketId, null, null, false, clock.millis());
            Phase phase = phase(verdict);
            String matchId = matchId(phase, verdict.matchId);
            String handoffName = handoffName(matchId);
            String goalRef = goal(tx, actor, matchId, handoffName);
            boolean start = soloRejection(handoffName, goalRef) == null;
            return new View(ticketId, false, true, start, null, phase.name(), handoffName, "NOT_REQUESTED",
                    start ? null : soloBlock(handoffName, goalRef), start ? goalRef : null, null, false, false);
        });
    }

    public View choose(long actor, String ticketId, String choice) {
        enabled();
        actor(actor);
        ticket(ticketId);
        if (!"EXIT".equals(choice) && !"SOLO".equals(choice)) throw new Rejection("INVALID_INPUT");
        Claim claim = guard.transaction(tx -> claim(tx, actor, ticketId, choice));
        if (!claim.launch) return view(claim.row);
        return finishLaunch(actor, claim.row);
    }

    private Claim claim(Transaction tx, long actor, String ticketId, String choice) {
        ExitRow existing = exits.find(ticketId);
        if (existing != null) {
            if (existing.actorId != actor) throw new Rejection("NOT_FOUND");
            if (!choice.equals(existing.choice)) throw new Rejection("EXIT_CONFLICT");
            return new Claim(existing, false);
        }
        long now = clock.millis();
        OngoingVerdict verdict = consent.verifyParticipants(tx, actor, ticketId, null, null, false, now);
        Phase phase = phase(verdict);
        String matchId = matchId(phase, verdict.matchId);
        String handoffName = handoffName(matchId);
        String goalRef = null;
        if ("SOLO".equals(choice)) {
            goalRef = goal(tx, actor, matchId, handoffName);
            String rejection = soloRejection(handoffName, goalRef);
            if (rejection != null) throw new Rejection(rejection);
        }
        consent.terminateCurrent(tx, actor, null, ticketId, Termination.EXIT, now);
        ExitRow row = row(ticketId, actor, matchId, choice, phase, handoffName,
                "SOLO".equals(choice) ? "STARTING" : "NOT_REQUESTED", goalRef, now);
        exits.insert(row);
        return new Claim(row, "SOLO".equals(choice));
    }

    private View finishLaunch(long actor, ExitRow claimed) {
        String receipt = null;
        int resumed = 0;
        boolean unknown = false;
        try {
            LaunchReceipt got = launcher.startOrResume(actor, claimed.goalRef, claimed.ticketId);
            if (got == null || !ref(got.receiptId)) unknown = true;
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
        ExitRow updated = guard.transaction(tx -> {
            ExitRow current = exits.find(claimed.ticketId);
            if (current == null || current.actorId != actor) throw new IllegalStateException("W16_EXIT_CLAIM_LOST");
            if (!"STARTING".equals(current.soloState)) return current;
            ExitRow next = current.copy();
            next.soloState = unresolved ? LAUNCH_PENDING : "STARTED";
            next.soloBlock = unresolved ? "LAUNCH_UNKNOWN" : null;
            next.launchReceipt = unresolved ? null : savedReceipt;
            next.resumed = unresolved ? 0 : savedResumed;
            exits.markLaunch(next);
            return next;
        });
        return view(updated);
    }

    /** No match: handoff never started, so C is not asked. A throw, a null, or an unknown name is UNKNOWN. */
    private String handoffName(String matchId) {
        if (matchId == null) return "NOT_STARTED";
        if (handoff == null) return "UNKNOWN";
        try {
            String state = handoff.handoffStateFor(matchId);
            if ("NOT_STARTED".equals(state) || "UNKNOWN".equals(state)
                    || "CREATED_JOIN_UNCONFIRMED".equals(state) || "COMPLETE".equals(state)) return state;
            return "UNKNOWN";
        } catch (RuntimeException failed) {
            return "UNKNOWN";
        }
    }

    private String goal(Transaction tx, long actor, String matchId, String handoffName) {
        if (catalog == null || !"NOT_STARTED".equals(handoffName)) return null;
        return catalog.publishedSoloGoal(tx, actor, matchId);
    }

    private String soloRejection(String handoffName, String goalRef) {
        if (!"NOT_STARTED".equals(handoffName)) {
            if ("CREATED_JOIN_UNCONFIRMED".equals(handoffName)) return "HANDOFF_JOIN_UNCONFIRMED";
            if ("COMPLETE".equals(handoffName)) return "HANDOFF_COMPLETE";
            return "HANDOFF_UNKNOWN";
        }
        if (!ref(goalRef)) return "SOLO_GOAL_UNAVAILABLE";
        if (launcher == null) return "SOLO_LAUNCHER_UNAVAILABLE";
        return null;
    }

    private String soloBlock(String handoffName, String goalRef) {
        String rejection = soloRejection(handoffName, goalRef);
        if ("SOLO_GOAL_UNAVAILABLE".equals(rejection)) return "GOAL_NOT_PUBLISHED";
        if ("SOLO_LAUNCHER_UNAVAILABLE".equals(rejection)) return "LAUNCHER_MISSING";
        return rejection;
    }

    private View view(ExitRow row) {
        String soloState = row.soloState;
        String block = row.soloBlock;
        boolean completed = false;
        if ("STARTING".equals(soloState)) {
            soloState = LAUNCH_PENDING;
            if (block == null) block = "LAUNCH_UNKNOWN";
        } else if ("STARTED".equals(row.soloState) && ref(row.launchReceipt) && results != null) {
            try {
                if (results.read(row.launchReceipt) == SoloResult.Status.COMPLETED) {
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

    private static ExitRow row(String ticketId, long actor, String matchId, String choice, Phase phase,
                               String handoffName, String soloState, String goalRef, long now) {
        ExitRow row = new ExitRow();
        row.ticketId = ticketId;
        row.actorId = actor;
        row.matchId = matchId;
        row.choice = choice;
        row.phase = phase.name();
        row.handoff = handoffName;
        row.soloState = soloState;
        row.goalRef = goalRef;
        row.resumed = 0;
        row.createdAt = now;
        return row;
    }

    /** {@code safe=false} does not refuse the exit. Ownership does. */
    private static Phase phase(OngoingVerdict verdict) {
        if (verdict == null || !verdict.owned || verdict.ticketState == null) throw new Rejection("NOT_FOUND");
        if (verdict.ticketState == TicketState.CANCELLED || verdict.ticketState == TicketState.EXPIRED) {
            throw new Rejection("EXIT_UNAVAILABLE");
        }
        if (verdict.ticketState == TicketState.WAITING) {
            if (verdict.matchId != null) throw new Rejection("EXIT_UNAVAILABLE");
            return Phase.WAITING;
        }
        if (verdict.ticketState != TicketState.RESERVED && verdict.ticketState != TicketState.ACCEPTED) {
            throw new Rejection("EXIT_UNAVAILABLE");
        }
        if (verdict.mutual || verdict.matchState == MatchState.MUTUAL_ACCEPTED) return Phase.MUTUAL;
        if (verdict.matchState == MatchState.PROPOSED) return Phase.ONE_SIDED;
        throw new Rejection("EXIT_UNAVAILABLE");
    }

    private static String matchId(Phase phase, String raw) {
        if (raw == null || raw.isEmpty()) {
            if (phase == Phase.WAITING) return null;
            throw new Rejection("EXIT_UNAVAILABLE");
        }
        if (!ref(raw) || phase == Phase.WAITING) throw new Rejection("EXIT_UNAVAILABLE");
        return raw;
    }

    private static boolean ref(String value) {
        return value != null && !value.isEmpty() && value.length() <= 128 && value.trim().equals(value);
    }

    private void enabled() {
        if (mode != Mode.ISOLATED_CONTRACT_EVALUATION) throw new Rejection("W16_EXIT_DISABLED");
    }

    private static void actor(long actor) { if (actor <= 0) throw new Rejection("AUTH_REQUIRED"); }

    private static void ticket(String value) {
        if (!ref(value)) throw new Rejection("INVALID_INPUT");
    }
}
