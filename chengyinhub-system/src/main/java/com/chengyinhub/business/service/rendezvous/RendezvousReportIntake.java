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
 * Depends on A's ConsentService at A4 {@code 08f44986}. This class does not modify it.
 * The receipt and {@code Termination.REPORT} are written in the same guard transaction.
 * Review stays unwired: a missing review channel never becomes a successful handoff. */
public final class RendezvousReportIntake {
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

    /** Review handoff. Production uses {@link NotConnectedReview}. Not an HTTP controller. */
    public interface ReviewPort {
        /** Hand-off after the local receipt has committed. Arguments are case id and request id only. */
        ReviewAnswer submit(String caseId, String requestId);
        ReviewAnswer status(String caseId, String requestId);
    }

    private final Mode mode;
    private final Clock clock;
    private final RendezvousConsentService consent;
    private final ReviewPort review;
    private final MyBatisRendezvousConsentStore store;
    private final RendezvousReportCaseMapper cases;

    /** No consent service is attached. A new case is refused and nothing is written. */
    public static RendezvousReportIntake notConnected(Mode mode, SqlSessionTemplate session,
                                                      DataSourceTransactionManager manager, Clock clock) {
        return new RendezvousReportIntake(mode, session, manager, clock, null, null, new NotConnectedReview());
    }

    /** Real stop, review still not connected. {@code consent} is A's service and is not wrapped. */
    public static RendezvousReportIntake connected(Mode mode, SqlSessionTemplate session,
                                                   DataSourceTransactionManager manager, Clock clock,
                                                   RendezvousConsentService consent) {
        return new RendezvousReportIntake(mode, session, manager, clock, null, Objects.requireNonNull(consent), new NotConnectedReview());
    }

    public RendezvousReportIntake(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager, Clock clock,
                                  RendezvousConsentService consent, ReviewPort review) {
        this(mode, session, manager, clock, null, consent, review);
    }

    /** {@code cases} replaces the session mapper in tests that have to fail a write. Production passes null.
     * {@code consent} null means the stop is not connected. */
    RendezvousReportIntake(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager, Clock clock,
                           RendezvousReportCaseMapper cases, RendezvousConsentService consent, ReviewPort review) {
        this.mode = Objects.requireNonNull(mode);
        this.clock = Objects.requireNonNull(clock);
        this.consent = consent;
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
        if (State.UNKNOWN.name().equals(stored.state)) return applyStatus(reporter, stored);
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

    /** One explicit review read. Does not schedule itself and does not invent a deadline. Does not submit. */
    public Receipt refresh(long reporter, String requestId) {
        return applyStatus(reporter, read(reporter, requestId));
    }

    /** Status only. Used for refresh and for a replay whose submit was already attempted. */
    private Receipt applyStatus(long reporter, Receipt current) {
        ReviewAnswer answer;
        try {
            answer = review.status(current.caseId, current.requestId);
        } catch (RuntimeException failed) {
            return current;
        }
        if (answer == null || answer.kind == null || answer.kind == ReviewAnswer.Kind.NOT_CONNECTED) return current;
        if (answer.kind == ReviewAnswer.Kind.AMBIGUOUS) {
            if (!awaitingAccept(current.state)) return current;
            return storeReview(reporter, current, current.state, State.UNKNOWN.name(),
                    ReviewConnection.AMBIGUOUS.name(), null, null);
        }
        if (answer.kind == ReviewAnswer.Kind.ACCEPTED) {
            if (!awaitingAccept(current.state) || !refOk(answer.reviewRef)) return current;
            return storeReview(reporter, current, current.state, State.SENT_TO_REVIEW.name(),
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
        if (consent == null) throw new Rejection("CONTACT_STOP_NOT_CONNECTED");
        long now = clock.millis();
        Long reported = counterpart;
        OngoingVerdict verdict = consent.verifyParticipants(tx, reporter, ticketId, ticket.matchId, reported, false, now);
        if (verdict == null || !verdict.owned) throw new Rejection("NOT_FOUND");
        if (!verdict.intact || verdict.matchId == null || !verdict.matchId.equals(ticket.matchId)) throw new Rejection("NOT_ASSOCIATED");
        Match before = tx.match(ticket.matchId);
        if (before == null) throw new Rejection("NOT_ASSOCIATED");
        boolean wasActive = before.active();
        MatchState previous = before.state;
        consent.terminateCurrent(tx, reporter, reported, ticketId, Termination.REPORT, now);
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
            return markUnknown(reporter, current);
        }
        if (answer == null || answer.kind == null || answer.kind == ReviewAnswer.Kind.NOT_CONNECTED) return current;
        if (answer.kind == ReviewAnswer.Kind.ACCEPTED && refOk(answer.reviewRef)) {
            Receipt stored = storeReview(reporter, current, State.LOCAL_INTAKE.name(), State.SENT_TO_REVIEW.name(),
                    ReviewConnection.ACCEPTED.name(), answer.reviewRef, null);
            if (State.SENT_TO_REVIEW.name().equals(stored.state)) return stored;
            return markUnknown(reporter, current);
        }
        return markUnknown(reporter, current, ReviewConnection.AMBIGUOUS.name());
    }

    /** Records that submit already ran. A later intake of this case checks status and does not submit. */
    private Receipt markUnknown(long reporter, Receipt current) {
        return markUnknown(reporter, current, ReviewConnection.UNKNOWN.name());
    }

    private Receipt markUnknown(long reporter, Receipt current, String connection) {
        Receipt marked = storeReview(reporter, current, State.LOCAL_INTAKE.name(), State.UNKNOWN.name(), connection, null, null);
        return State.UNKNOWN.name().equals(marked.state) ? marked : current;
    }

    private static boolean awaitingAccept(String state) {
        return State.LOCAL_INTAKE.name().equals(state) || State.UNKNOWN.name().equals(state);
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

    /** Production review port. Never returns a reference or a disposition. */
    private static final class NotConnectedReview implements ReviewPort {
        public ReviewAnswer submit(String caseId, String requestId) { return ReviewAnswer.notConnected(); }
        public ReviewAnswer status(String caseId, String requestId) { return ReviewAnswer.notConnected(); }
    }
}
