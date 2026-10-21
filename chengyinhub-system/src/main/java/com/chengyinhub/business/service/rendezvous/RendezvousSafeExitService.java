package com.chengyinhub.business.service.rendezvous;

import java.time.Clock;
import java.util.Objects;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.MatchState;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.OngoingVerdict;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Store;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Termination;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.TicketState;

/**
 * Safe exit for one authenticated participant. Not a Spring bean.
 * Seat release and the exit row share the consent guard transaction.
 * C's {@code handoffStateFor} is not in this tree, and no solo launcher is connected.
 * {@code completed} is therefore never true. This service does not touch team rows.
 */
public final class RendezvousSafeExitService {
    public enum Mode { DISABLED, ISOLATED_CONTRACT_EVALUATION }
    public enum Phase { WAITING, ONE_SIDED, MUTUAL }
    /** Not one of C's handoff states. The query is not on this tree. */
    static final String HANDOFF_NOT_PUBLISHED = "NOT_PUBLISHED";

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

    private final Mode mode;
    private final Store guard;
    private final RendezvousConsentService consent;
    private final RendezvousSafeExitStore exits;
    private final Clock clock;

    public RendezvousSafeExitService(Mode mode, Store guard, RendezvousConsentService consent,
                                     RendezvousSafeExitStore exits, Clock clock) {
        this.mode = Objects.requireNonNull(mode);
        this.guard = Objects.requireNonNull(guard);
        this.consent = Objects.requireNonNull(consent);
        this.exits = Objects.requireNonNull(exits);
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
            return open(ticketId, phase(verdict));
        });
    }

    public View choose(long actor, String ticketId, String choice) {
        enabled();
        actor(actor);
        ticket(ticketId);
        if (!"EXIT".equals(choice) && !"SOLO".equals(choice)) throw new Rejection("INVALID_INPUT");
        return guard.transaction(tx -> {
            ExitRow existing = exits.find(ticketId);
            if (existing != null) {
                if (existing.actorId != actor) throw new Rejection("NOT_FOUND");
                if (!choice.equals(existing.choice)) throw new Rejection("EXIT_CONFLICT");
                return view(existing);
            }
            long now = clock.millis();
            OngoingVerdict verdict = consent.verifyParticipants(tx, actor, ticketId, null, null, false, now);
            Phase phase = phase(verdict);
            if ("SOLO".equals(choice)) throw new Rejection("SOLO_LAUNCHER_UNAVAILABLE");
            consent.terminateCurrent(tx, actor, null, ticketId, Termination.EXIT, now);
            ExitRow row = row(ticketId, actor, verdict.matchId, phase, now);
            exits.insert(row);
            return view(row);
        });
    }

    private static View open(String ticketId, Phase phase) {
        return new View(ticketId, false, true, false, null, phase.name(), HANDOFF_NOT_PUBLISHED,
                "NOT_REQUESTED", null, null, null, false, false);
    }

    private static View view(ExitRow row) {
        return new View(row.ticketId, true, false, false, row.choice, row.phase, row.handoff,
                row.soloState, row.soloBlock, row.goalRef, row.launchReceipt, row.resumed == 1, false);
    }

    private static ExitRow row(String ticketId, long actor, String matchId, Phase phase, long now) {
        ExitRow row = new ExitRow();
        row.ticketId = ticketId;
        row.actorId = actor;
        row.matchId = matchId;
        row.choice = "EXIT";
        row.phase = phase.name();
        row.handoff = HANDOFF_NOT_PUBLISHED;
        row.soloState = "NOT_REQUESTED";
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

    private void enabled() {
        if (mode != Mode.ISOLATED_CONTRACT_EVALUATION) throw new Rejection("W16_EXIT_DISABLED");
    }

    private static void actor(long actor) { if (actor <= 0) throw new Rejection("AUTH_REQUIRED"); }

    private static void ticket(String value) {
        if (value == null || value.isEmpty() || value.length() > 128 || !value.trim().equals(value)) {
            throw new Rejection("INVALID_INPUT");
        }
    }
}
