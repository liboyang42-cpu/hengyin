package com.chengyinhub.business.service.rendezvous;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousReportCase.*;

/** Report intake for one rendezvous event the caller already belongs to.
 * Explicitly constructed. Not a Spring bean and not an HTTP API.
 * The production ports are fail-closed: a missing stop or review channel never becomes a successful handoff.
 * A must merge the participant signatures below into the consent guard. This class does not edit Consent. */
public final class RendezvousReportIntake {
    /** Same shape as {@code RendezvousConsentService.OngoingVerdict} at A 37b4b345.
     * Field order is the adapter mapping. This object has no partner id. */
    public static final class OngoingVerdict {
        public final boolean safe, intact, expired, eligible, blocked, placeAllowed, owned, mutual;
        public final String ticketId, matchId;
        public final TicketState ticketState;
        public final MatchState matchState;
        public OngoingVerdict(boolean safe, boolean intact, boolean expired, boolean eligible, boolean blocked,
                              boolean placeAllowed, boolean owned, boolean mutual, String ticketId, String matchId,
                              TicketState ticketState, MatchState matchState) {
            this.safe = safe; this.intact = intact; this.expired = expired; this.eligible = eligible;
            this.blocked = blocked; this.placeAllowed = placeAllowed; this.owned = owned; this.mutual = mutual;
            this.ticketId = ticketId; this.matchId = matchId; this.ticketState = ticketState; this.matchState = matchState;
        }
        /** Shape A returns when the two actors have no shared active match. Not a substitute for an unwired port. */
        public static OngoingVerdict closed(String ticketId) {
            return new OngoingVerdict(false, false, false, false, false, false, false, false, ticketId, null, null, null);
        }
    }

    /** Same names as {@code RendezvousConsentService.Termination}. This line calls only REPORT. */
    public enum Termination { BLOCK, REPORT, EXIT }

    /** Review channel result. {@code RESOLVED} is not a successful submit. Timeout is {@code AMBIGUOUS}, not accepted. */
    public static final class ReviewAnswer {
        public enum Kind { NOT_CONNECTED, ACCEPTED, AMBIGUOUS, RESOLVED }
        public final Kind kind;
        public final String reviewRef, dispositionCode;
        public ReviewAnswer(Kind kind, String reviewRef, String dispositionCode) {
            this.kind = kind; this.reviewRef = reviewRef; this.dispositionCode = dispositionCode;
        }
        public static ReviewAnswer notConnected() { return new ReviewAnswer(Kind.NOT_CONNECTED, null, null); }
        public static ReviewAnswer ambiguous() { return new ReviewAnswer(Kind.AMBIGUOUS, null, null); }
        public static ReviewAnswer accepted(String reviewRef) { return new ReviewAnswer(Kind.ACCEPTED, reviewRef, null); }
        public static ReviewAnswer resolved(String dispositionCode) { return new ReviewAnswer(Kind.RESOLVED, null, dispositionCode); }
    }

/** Ports A and the review owner can implement. The copies in this class do not reach them.
     * Adapter, once Consent from cursor/w16-a-eligibility @ 37b4b345 is on the classpath:
     * {@code consent.verifyParticipants(tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now)}
     * and {@code consent.terminateCurrent(tx, actor, peer, ticketId, RendezvousConsentService.Termination.REPORT, now)}.
     * Copy {@code OngoingVerdict} fields in constructor order. Do not open another transaction. */
    public interface ParticipantPort {
        OngoingVerdict verifyParticipants(Transaction tx, long actor, String ticketId, String expectedMatchId,
                                          Long expectedPeer, boolean requireMutualAccepted, long now);
        OngoingVerdict terminateCurrent(Transaction tx, long actor, Long peer, String ticketId, Termination reason, long now);
    }
    public interface ReviewPort {
        /** Hand-off after the local receipt has committed. Arguments are case id and request id only. */
        ReviewAnswer submit(String caseId, String requestId);
        ReviewAnswer status(String caseId, String requestId);
    }

    private final Mode mode;
    private final Clock clock;
    private final ParticipantPort participants;
    private final ReviewPort review;
    private final MyBatisRendezvousConsentStore store;
    private final RendezvousReportCaseMapper cases;

    public static RendezvousReportIntake notConnected(Mode mode, SqlSessionTemplate session,
                                                      DataSourceTransactionManager manager, Clock clock) {
        return new RendezvousReportIntake(mode, session, manager, clock, null, new NotConnectedParticipants(), new NotConnectedReview());
    }

    public RendezvousReportIntake(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager, Clock clock,
                                  ParticipantPort participants, ReviewPort review) {
        this(mode, session, manager, clock, null, participants, review);
    }

    /** {@code cases} replaces the session mapper in tests that have to fail a write. Production passes null. */
    RendezvousReportIntake(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager, Clock clock,
                           RendezvousReportCaseMapper cases, ParticipantPort participants, ReviewPort review) {
        this.mode = Objects.requireNonNull(mode);
        this.clock = Objects.requireNonNull(clock);
        this.participants = Objects.requireNonNull(participants);
        this.review = Objects.requireNonNull(review);
        if (mode != Mode.ISOLATED_PERSISTENCE_EVALUATION) {
            this.store = null;
            this.cases = null;
            return;
        }
        this.store = new MyBatisRendezvousConsentStore(mode, session, manager);
        this.cases = cases != null ? cases : session.getMapper(RendezvousReportCaseMapper.class);
    }

    /** Accept a report for the caller's own shared ticket. Same request id and payload reads the original case back. */
    public Receipt intake(long reporter, String requestId, String ticketId, String evidenceKind) {
        requireEnabled();
        actor(reporter);
        text(requestId);
        text(ticketId);
        if (!EVIDENCE_TICKET_REF.equals(evidenceKind)) throw new Rejection("INVALID_INPUT");
        Receipt stored = store.transaction(tx -> persist(tx, reporter, requestId, ticketId, evidenceKind));
        if (!State.LOCAL_INTAKE.name().equals(stored.state)) return stored;
        return attemptSubmit(reporter, stored);
    }

    /** Owner read-back. Does not stop the pair and does not call review. */
    public Receipt read(long reporter, String requestId) {
        requireEnabled();
        actor(reporter);
        text(requestId);
        return store.transaction(tx -> {
            Row row = cases.find(reporter, requestId);
            if (row == null) throw new Rejection("NOT_FOUND");
            return receipt(row);
        });
    }

    /** One explicit review read. Does not schedule itself and does not invent a deadline. */
    public Receipt refresh(long reporter, String requestId) {
        Receipt current = read(reporter, requestId);
        ReviewAnswer answer;
        try {
            answer = review.status(current.caseId, current.requestId);
        } catch (RuntimeException failed) {
            return current;
        }
        if (answer == null || answer.kind == null || answer.kind == ReviewAnswer.Kind.NOT_CONNECTED) return current;
        if (answer.kind == ReviewAnswer.Kind.AMBIGUOUS) {
            if (!State.LOCAL_INTAKE.name().equals(current.state)) return current;
            return storeReview(reporter, current, State.LOCAL_INTAKE.name(), State.LOCAL_INTAKE.name(),
                    ReviewConnection.AMBIGUOUS.name(), null, null);
        }
        if (answer.kind == ReviewAnswer.Kind.ACCEPTED) {
            if (!State.LOCAL_INTAKE.name().equals(current.state) || !refOk(answer.reviewRef)) return current;
            return storeReview(reporter, current, State.LOCAL_INTAKE.name(), State.SENT_TO_REVIEW.name(),
                    ReviewConnection.ACCEPTED.name(), answer.reviewRef, null);
        }
        if (answer.kind == ReviewAnswer.Kind.RESOLVED
                && State.SENT_TO_REVIEW.name().equals(current.state) && codeOk(answer.dispositionCode)) {
            return storeReview(reporter, current, State.SENT_TO_REVIEW.name(), State.RESOLVED.name(),
                    ReviewConnection.ACCEPTED.name(), current.reviewRef, answer.dispositionCode);
        }
        return current;
    }

    private Receipt persist(Transaction tx, long reporter, String requestId, String ticketId, String evidenceKind) {
        Ticket ticket = tx.ticket(ticketId);
        if (ticket == null || ticket.actor != reporter) throw new Rejection("NOT_FOUND");
        long counterpart = counterpart(tx, ticket);
        if (counterpart <= 0) throw new Rejection("NOT_ASSOCIATED");
        String hash = payloadHash(ticketId, evidenceKind, ticket.matchId, counterpart);
        Row existing = cases.find(reporter, requestId);
        if (existing != null) {
            if (!hash.equals(existing.payloadHash)) throw new Rejection("CONFLICT");
            return receipt(existing);
        }
        long now = clock.millis();
        Long reported = counterpart;
        OngoingVerdict verdict = participants.verifyParticipants(tx, reporter, ticketId, ticket.matchId, reported, false, now);
        if (verdict == null) throw new Rejection("CONTACT_STOP_FAILED");
        if (!verdict.owned) throw new Rejection("NOT_FOUND");
        if (!verdict.intact || verdict.matchId == null || !verdict.matchId.equals(ticket.matchId)) throw new Rejection("NOT_ASSOCIATED");
        Match before = tx.match(ticket.matchId);
        if (before == null) throw new Rejection("NOT_ASSOCIATED");
        boolean wasActive = before.active();
        MatchState previous = before.state;
        participants.terminateCurrent(tx, reporter, reported, ticketId, Termination.REPORT, now);
        Match after = tx.match(ticket.matchId);
        if (after == null || (wasActive ? after.state != MatchState.SAFETY_STOPPED : after.state != previous)) {
            throw new Rejection("CONTACT_STOP_FAILED");
        }
        Row row = new Row();
        row.caseId = UUID.randomUUID().toString();
        row.reporterId = reporter;
        row.requestId = requestId;
        row.ticketId = ticketId;
        row.matchId = ticket.matchId;
        row.payloadHash = hash;
        row.evidenceKind = evidenceKind;
        row.state = State.LOCAL_INTAKE.name();
        row.reviewConnection = ReviewConnection.NOT_CONNECTED.name();
        row.createdAt = now;
        if (cases.insert(row) != 1) throw new IllegalStateException("INTAKE_NOT_STORED");
        return receipt(row);
    }

    private Receipt attemptSubmit(long reporter, Receipt current) {
        ReviewAnswer answer;
        try {
            answer = review.submit(current.caseId, current.requestId);
        } catch (RuntimeException failed) {
            return current;
        }
        if (answer == null || answer.kind == null || answer.kind == ReviewAnswer.Kind.NOT_CONNECTED) return current;
        if (answer.kind == ReviewAnswer.Kind.ACCEPTED && refOk(answer.reviewRef)) {
            return storeReview(reporter, current, State.LOCAL_INTAKE.name(), State.SENT_TO_REVIEW.name(),
                    ReviewConnection.ACCEPTED.name(), answer.reviewRef, null);
        }
        return storeReview(reporter, current, State.LOCAL_INTAKE.name(), State.LOCAL_INTAKE.name(),
                ReviewConnection.AMBIGUOUS.name(), null, null);
    }

    private Receipt storeReview(long reporter, Receipt current, String expectedState, String nextState,
                                String connection, String reviewRef, String disposition) {
        try {
            return store.transaction(tx -> {
                Row row = cases.find(reporter, current.requestId);
                if (row == null || !current.caseId.equals(row.caseId) || !expectedState.equals(row.state)) return current;
                int written = cases.updateReview(row.caseId, reporter, expectedState, nextState, connection, reviewRef, disposition);
                if (written != 1) return current;
                Row stored = cases.find(reporter, current.requestId);
                return stored == null ? current : receipt(stored);
            });
        } catch (RuntimeException failed) {
            return current;
        }
    }

    private static long counterpart(Transaction tx, Ticket ticket) {
        if (ticket.matchId == null) return 0;
        Match match = tx.match(ticket.matchId);
        if (match == null) return 0;
        String otherId = match.firstTicket.equals(ticket.id) ? match.secondTicket
                : match.secondTicket.equals(ticket.id) ? match.firstTicket : null;
        if (otherId == null) return 0;
        Ticket other = tx.ticket(otherId);
        if (other == null || other.actor <= 0 || other.actor == ticket.actor) return 0;
        return other.actor;
    }

    private void requireEnabled() {
        if (mode != Mode.ISOLATED_PERSISTENCE_EVALUATION || store == null || cases == null) {
            throw new IllegalStateException("W16_PERSISTENCE_DISABLED");
        }
    }

    private static boolean refOk(String ref) {
        if (ref == null || ref.length() == 0 || ref.length() > 128) return false;
        for (int i = 0; i < ref.length(); i++) {
            char value = ref.charAt(i);
            if (value <= ' ' || value == 0x7f) return false;
        }
        return true;
    }

    private static boolean codeOk(String code) {
        if (code == null || code.length() < 1 || code.length() > 32) return false;
        for (int i = 0; i < code.length(); i++) {
            char value = code.charAt(i);
            if (value != '_' && (value < '0' || value > '9') && (value < 'A' || value > 'Z')) return false;
        }
        return true;
    }

    private static void actor(long actor) { if (actor <= 0) throw new Rejection("AUTH_REQUIRED"); }
    private static void text(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 128) throw new Rejection("INVALID_INPUT");
    }

    private static final class NotConnectedParticipants implements ParticipantPort {
        public OngoingVerdict verifyParticipants(Transaction tx, long actor, String ticketId, String expectedMatchId,
                                                 Long expectedPeer, boolean requireMutualAccepted, long now) {
            throw new Rejection("CONTACT_STOP_NOT_CONNECTED");
        }
        public OngoingVerdict terminateCurrent(Transaction tx, long actor, Long peer, String ticketId, Termination reason, long now) {
            throw new Rejection("CONTACT_STOP_NOT_CONNECTED");
        }
    }

    private static final class NotConnectedReview implements ReviewPort {
        public ReviewAnswer submit(String caseId, String requestId) { return ReviewAnswer.notConnected(); }
        public ReviewAnswer status(String caseId, String requestId) { return ReviewAnswer.notConnected(); }
    }
}
