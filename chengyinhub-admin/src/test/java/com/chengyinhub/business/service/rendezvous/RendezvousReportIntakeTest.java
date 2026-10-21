package com.chengyinhub.business.service.rendezvous;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.DISABLED;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousReportCase.EVIDENCE_TICKET_REF;
import static com.chengyinhub.business.service.rendezvous.RendezvousReportCase.Receipt;
import static com.chengyinhub.business.service.rendezvous.RendezvousReportIntake.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated H2 for report intake. Not a MySQL acceptance test and not a review-channel test.
 * Stop uses A's 4-arg constructor and {@link RendezvousContactLedger}. Review stays unwired on {@code connected}. */
class RendezvousReportIntakeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC);
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    SqlSessionTemplate session;
    RendezvousConsentService consent;
    ScriptedReview review;
    RendezvousReportIntake intake;

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16report_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        Path root = RendezvousConsentPersistenceTest.sourceRoot();
        try (Connection connection = dataSource.getConnection()) {
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_rendezvous_protocol_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_eligibility_contact_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_report_case_candidate.sql"))));
        }
        jdbc.execute("CREATE TABLE im_block(id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, blocked_member_id BIGINT NOT NULL, create_time TIMESTAMP, UNIQUE(member_id, blocked_member_id))");
        Configuration config = new Configuration(new Environment("w16-report", new SpringManagedTransactionFactory(), dataSource));
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml");
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousContactMapper.xml");
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousReportCaseMapper.xml");
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        manager = new DataSourceTransactionManager(dataSource);
        jdbc.update("INSERT INTO w16_contact_quota(pool, scope, window_millis, max_contacts, decision) VALUES ('pool','POOL',100000,10,'OPEN')");
        RendezvousContactLedger ledger = new RendezvousContactLedger(session.getMapper(RendezvousContactMapper.class));
        consent = new RendezvousConsentService(
                new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager),
                new OpenSafety(), CLOCK, ledger::charge);
        review = new ScriptedReview();
        intake = new RendezvousReportIntake(ISOLATED_PERSISTENCE_EVALUATION, session, manager, CLOCK, consent, review);
    }

    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }

    @Test void disabledIntakeDoesNotCallPorts() {
        ReviewPort channel = new ReviewPort() {
            public ReviewAnswer submit(String caseId, String requestId) { throw new AssertionError("submit"); }
            public ReviewAnswer status(String caseId, String requestId) { throw new AssertionError("status"); }
        };
        RendezvousReportIntake closed = new RendezvousReportIntake(DISABLED, null, null, CLOCK, null, channel);
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> closed.intake(1, "req", "ticket", EVIDENCE_TICKET_REF));
        assertEquals("W16_PERSISTENCE_DISABLED", rejected.getMessage());
        assertEquals("W16_PERSISTENCE_DISABLED", assertThrows(IllegalStateException.class, () -> closed.read(1, "req")).getMessage());
    }

    @Test void strangerOrMissingTicketIsNotFound() {
        String[] ids = mutual();
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> intake.intake(9, "imp", ids[0], EVIDENCE_TICKET_REF)).getCode());
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> intake.intake(1, "missing", "no-such-ticket", EVIDENCE_TICKET_REF)).getCode());
        assertEquals("AUTH_REQUIRED", assertThrows(Rejection.class, () -> intake.intake(0, "req", ids[0], EVIDENCE_TICKET_REF)).getCode());
        assertEquals("INVALID_INPUT", assertThrows(Rejection.class, () -> intake.intake(1, "req", ids[0], "CHAT_LOG")).getCode());
        assertEquals(MatchState.MUTUAL_ACCEPTED, consent.read(1, ids[0]).matchState);
        assertEquals(0, count("w16_report_case"));
        assertEquals(2, count("w16_contact_event"));
    }

    @Test void ownWaitingTicketIsNotAssociated() {
        View alone = consent.join(4, "pool", "v1", 100_000);
        assertEquals("NOT_ASSOCIATED", assertThrows(Rejection.class, () -> intake.intake(4, "alone", alone.ticketId, EVIDENCE_TICKET_REF)).getCode());
        assertEquals(TicketState.WAITING, consent.read(4, alone.ticketId).ticketState);
        assertEquals(0, count("w16_report_case"));
        assertEquals(0, count("w16_contact_event"));
    }

    @Test void unwiredStopRefusesTheCase() {
        String[] ids = mutual();
        RendezvousReportIntake unwired = RendezvousReportIntake.notConnected(ISOLATED_PERSISTENCE_EVALUATION, session, manager, CLOCK);
        assertEquals("CONTACT_STOP_NOT_CONNECTED", assertThrows(Rejection.class,
                () -> unwired.intake(1, "req", ids[0], EVIDENCE_TICKET_REF)).getCode());
        assertEquals(MatchState.MUTUAL_ACCEPTED, consent.read(1, ids[0]).matchState);
        assertNotNull(consent.read(2, ids[1]).publicPlace);
        assertEquals(0, count("w16_report_case"));
        assertEquals(0, count("w16_safety_report"));
        assertEquals(0, count("im_block"));
    }

    @Test void intakeStopsThePairAndStoresALocalReceipt() {
        String[] ids = mutual();
        View outsider = consent.join(8, "pool", "v1", 100_000);
        long version = consent.read(1, ids[0]).version;
        Receipt receipt = intake.intake(1, "req-1", ids[0], EVIDENCE_TICKET_REF);
        assertLocal(receipt, "req-1", ids[0]);
        View stopped = consent.read(1, ids[0]);
        assertEquals(MatchState.SAFETY_STOPPED, stopped.matchState);
        assertEquals(version + 1, stopped.version);
        assertNull(consent.read(2, ids[1]).publicPlace);
        assertEquals(TicketState.CANCELLED, stopped.ticketState);
        assertEquals(TicketState.WAITING, consent.read(8, outsider.ticketId).ticketState);
        assertEquals(1, count("w16_report_case"));
        assertEquals(0, count("w16_match_reservation"));
        assertEquals(2, count("w16_contact_event"));
        assertEquals(0, count("w16_safety_report"));
        assertEquals(0, count("im_block"));
        assertEquals(10_000L, jdbc.queryForObject("SELECT created_at FROM w16_report_case", Long.class).longValue());
        assertEquals(1, review.submits);
        assertHidden(receipt);
    }

    @Test void connectedFactoryStopsAndLeavesReviewNotConnected() {
        String[] ids = mutual();
        RendezvousReportIntake wired = RendezvousReportIntake.connected(ISOLATED_PERSISTENCE_EVALUATION, session, manager, CLOCK, consent);
        Receipt receipt = wired.intake(1, "wired", ids[0], EVIDENCE_TICKET_REF);
        assertLocal(receipt, "wired", ids[0]);
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(1, ids[0]).matchState);
        Receipt refreshed = wired.refresh(1, "wired");
        assertEquals("LOCAL_INTAKE", refreshed.state);
        assertEquals("NOT_CONNECTED", refreshed.reviewConnection);
        assertNull(refreshed.reviewRef);
        assertNull(refreshed.dispositionCode);
    }

    @Test void retryIsStableAndADifferentPayloadConflicts() {
        String[] ids = mutual();
        Receipt first = intake.intake(1, "same", ids[0], EVIDENCE_TICKET_REF);
        long stopped = consent.read(1, ids[0]).version;
        Receipt second = intake.intake(1, "same", ids[0], EVIDENCE_TICKET_REF);
        assertEquals(first.caseId, second.caseId);
        assertEquals(stopped, consent.read(1, ids[0]).version);
        assertEquals(1, count("w16_report_case"));
        RendezvousReportIntake restarted = RendezvousReportIntake.notConnected(ISOLATED_PERSISTENCE_EVALUATION, session, manager, CLOCK);
        assertEquals(first.caseId, restarted.intake(1, "same", ids[0], EVIDENCE_TICKET_REF).caseId);
        assertEquals(first.caseId, restarted.read(1, "same").caseId);
        assertEquals(stopped, consent.read(1, ids[0]).version);
        View again = consent.join(1, "pool", "v1", 100_000);
        View other = consent.join(5, "pool", "v1", 100_000);
        consent.propose(again.ticketId, other.ticketId, "public-place");
        long proposed = consent.read(1, again.ticketId).version;
        assertEquals("CONFLICT", assertThrows(Rejection.class, () -> intake.intake(1, "same", again.ticketId, EVIDENCE_TICKET_REF)).getCode());
        assertEquals(MatchState.PROPOSED, consent.read(1, again.ticketId).matchState);
        assertEquals(proposed, consent.read(1, again.ticketId).version);
        assertEquals(first.caseId, intake.read(1, "same").caseId);
        assertEquals(1, count("w16_report_case"));
        assertEquals(4, count("w16_contact_event"));
    }

    @Test void anotherRequestDoesNotOverwriteTheCase() {
        String[] ids = mutual();
        Receipt first = intake.intake(1, "one", ids[0], EVIDENCE_TICKET_REF);
        long stopped = consent.read(1, ids[0]).version;
        Receipt second = intake.intake(1, "two", ids[0], EVIDENCE_TICKET_REF);
        Receipt counterpart = intake.intake(2, "one", ids[1], EVIDENCE_TICKET_REF);
        assertNotEquals(first.caseId, second.caseId);
        assertNotEquals(first.caseId, counterpart.caseId);
        assertEquals(first.caseId, intake.read(1, "one").caseId);
        assertEquals(second.caseId, intake.read(1, "two").caseId);
        assertEquals(counterpart.caseId, intake.read(2, "one").caseId);
        assertEquals(3, count("w16_report_case"));
        assertEquals(stopped, consent.read(1, ids[0]).version);
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(1, ids[0]).matchState);
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> intake.read(2, "two")).getCode());
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> intake.read(8, "one")).getCode());
    }

    @Test void missingMatchWritesNothing() {
        String[] ids = mutual();
        String matchId = jdbc.queryForObject("SELECT match_id FROM w16_match_ticket WHERE id=?", String.class, ids[0]);
        jdbc.update("DELETE FROM w16_rendezvous_match WHERE id=?", matchId);
        assertEquals("NOT_ASSOCIATED", assertThrows(Rejection.class, () -> intake.intake(1, "boom", ids[0], EVIDENCE_TICKET_REF)).getCode());
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT state FROM w16_match_ticket WHERE id=?", String.class, ids[0]));
        assertEquals(2, count("w16_match_reservation"));
        assertEquals(0, count("w16_report_case"));
        assertEquals(2, count("w16_contact_event"));
        assertEquals(0, review.submits);
    }

    @Test void insertFailureRollsTheStopBack() {
        String[] ids = mutual();
        RendezvousReportCaseMapper real = session.getMapper(RendezvousReportCaseMapper.class);
        RendezvousReportIntake failing = new RendezvousReportIntake(ISOLATED_PERSISTENCE_EVALUATION, session, manager, CLOCK,
                new InsertFails(real), consent, review);
        assertEquals("INTAKE_STORE_FAILED", assertThrows(IllegalStateException.class,
                () -> failing.intake(1, "boom", ids[0], EVIDENCE_TICKET_REF)).getMessage());
        assertEquals(MatchState.MUTUAL_ACCEPTED, consent.read(2, ids[1]).matchState);
        assertNotNull(consent.read(1, ids[0]).publicPlace);
        assertEquals(2, count("w16_match_reservation"));
        assertEquals(0, count("w16_report_case"));
        assertEquals(2, count("w16_contact_event"));
        assertEquals(0, review.submits);
    }

    @Test void ownerCanReadTheReceiptAfterRestartAndOthersCannot() {
        String[] ids = mutual();
        Receipt stored = intake.intake(2, "lost", ids[1], EVIDENCE_TICKET_REF);
        RendezvousReportIntake restarted = RendezvousReportIntake.notConnected(ISOLATED_PERSISTENCE_EVALUATION, session, manager, CLOCK);
        Receipt read = restarted.read(2, "lost");
        assertEquals(stored.caseId, read.caseId);
        assertLocal(read, "lost", ids[1]);
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> restarted.read(1, "lost")).getCode());
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> restarted.read(9, "lost")).getCode());
        assertHidden(read);
    }

    @Test void earlyResolutionDoesNotBecomeResolved() {
        String[] ids = mutual();
        review.submitThrows = true;
        Receipt timedOut = intake.intake(1, "slow", ids[0], EVIDENCE_TICKET_REF);
        assertEquals("UNKNOWN", timedOut.state);
        assertEquals("UNKNOWN", timedOut.reviewConnection);
        assertNull(timedOut.reviewRef);
        review.submitThrows = false;
        review.submitAnswer = ReviewAnswer.ambiguous();
        Receipt ambiguous = intake.intake(1, "unknown", ids[0], EVIDENCE_TICKET_REF);
        assertEquals("UNKNOWN", ambiguous.state);
        assertEquals("AMBIGUOUS", ambiguous.reviewConnection);
        assertNull(ambiguous.reviewRef);
        assertNull(ambiguous.dispositionCode);
        review.submitAnswer = ReviewAnswer.resolved("RECORDED");
        Receipt skipped = intake.intake(1, "skip", ids[0], EVIDENCE_TICKET_REF);
        assertEquals("UNKNOWN", skipped.state);
        assertNull(skipped.dispositionCode);
        review.submitAnswer = ReviewAnswer.accepted(" ");
        Receipt blank = intake.intake(1, "blank", ids[0], EVIDENCE_TICKET_REF);
        assertEquals("UNKNOWN", blank.state);
        assertNull(blank.reviewRef);
        review.statusAnswer = ReviewAnswer.resolved("RECORDED");
        Receipt refreshed = intake.refresh(1, "slow");
        assertEquals("UNKNOWN", refreshed.state);
        assertNull(refreshed.dispositionCode);
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> intake.read(2, "slow")).getCode());
    }

    @Test void replayAfterAmbiguousDoesNotSubmitAgain() {
        String[] ids = mutual();
        review.submitAnswer = ReviewAnswer.ambiguous();
        Receipt first = intake.intake(1, "amb", ids[0], EVIDENCE_TICKET_REF);
        assertEquals("UNKNOWN", first.state);
        assertEquals("AMBIGUOUS", first.reviewConnection);
        assertEquals(1, review.submits);
        review.submitAnswer = ReviewAnswer.accepted("second-side-effect");
        review.statusAnswer = ReviewAnswer.notConnected();
        Receipt again = intake.intake(1, "amb", ids[0], EVIDENCE_TICKET_REF);
        assertEquals(first.caseId, again.caseId);
        assertEquals(1, review.submits);
        assertEquals(1, review.statuses);
        assertEquals("UNKNOWN", again.state);
        assertEquals("AMBIGUOUS", again.reviewConnection);
        assertNull(again.reviewRef);
    }

    @Test void replayAfterTimeoutDoesNotSubmitAgain() {
        String[] ids = mutual();
        review.submitThrows = true;
        Receipt first = intake.intake(1, "slow", ids[0], EVIDENCE_TICKET_REF);
        assertEquals("UNKNOWN", first.state);
        assertEquals("UNKNOWN", first.reviewConnection);
        assertNull(first.reviewRef);
        assertEquals(1, review.submits);
        review.submitThrows = false;
        review.submitAnswer = ReviewAnswer.accepted("late");
        review.statusThrows = true;
        Receipt again = intake.intake(1, "slow", ids[0], EVIDENCE_TICKET_REF);
        assertEquals(first.caseId, again.caseId);
        assertEquals(1, review.submits);
        assertEquals(1, review.statuses);
        assertEquals("UNKNOWN", again.state);
        assertNull(again.reviewRef);
        assertNull(again.dispositionCode);
    }

    @Test void replayAfterLocalAcceptUpdateFailsDoesNotSubmitAgain() {
        String[] ids = mutual();
        RendezvousReportCaseMapper real = session.getMapper(RendezvousReportCaseMapper.class);
        RendezvousReportIntake failing = new RendezvousReportIntake(ISOLATED_PERSISTENCE_EVALUATION, session, manager, CLOCK,
                new SentUpdateFails(real), consent, review);
        review.submitAnswer = ReviewAnswer.accepted("rev-9");
        Receipt first = failing.intake(1, "lost-write", ids[0], EVIDENCE_TICKET_REF);
        assertEquals("UNKNOWN", first.state);
        assertEquals("UNKNOWN", first.reviewConnection);
        assertNull(first.reviewRef);
        assertEquals(1, review.submits);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_report_case WHERE review_ref IS NOT NULL", Integer.class));
        review.submitAnswer = ReviewAnswer.accepted("other");
        review.statusAnswer = ReviewAnswer.notConnected();
        Receipt again = failing.intake(1, "lost-write", ids[0], EVIDENCE_TICKET_REF);
        assertEquals(first.caseId, again.caseId);
        assertEquals(1, review.submits);
        assertEquals(1, review.statuses);
        assertEquals("UNKNOWN", again.state);
        assertNull(again.reviewRef);
    }

    @Test void sentAndResolvedStayDistinctWhenAReviewDoubleAnswers() {
        String[] ids = mutual();
        review.submitAnswer = ReviewAnswer.accepted("rev-9");
        Receipt sent = intake.intake(1, "sent", ids[0], EVIDENCE_TICKET_REF);
        assertEquals("SENT_TO_REVIEW", sent.state);
        assertEquals("ACCEPTED", sent.reviewConnection);
        assertEquals("rev-9", sent.reviewRef);
        assertNull(sent.dispositionCode);
        int submits = review.submits;
        assertEquals(sent.caseId, intake.intake(1, "sent", ids[0], EVIDENCE_TICKET_REF).caseId);
        assertEquals(submits, review.submits);
        review.statusAnswer = ReviewAnswer.resolved("please ban them");
        assertEquals("SENT_TO_REVIEW", intake.refresh(1, "sent").state);
        review.statusAnswer = ReviewAnswer.resolved("NO_ACTION");
        Receipt resolved = intake.refresh(1, "sent");
        assertEquals("RESOLVED", resolved.state);
        assertEquals("NO_ACTION", resolved.dispositionCode);
        assertEquals("rev-9", resolved.reviewRef);
        assertEquals("RESOLVED", intake.read(1, "sent").state);
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> intake.read(2, "sent")).getCode());
        assertEquals(0, count("im_block"));
        assertEquals(0, count("w16_safety_report"));
        assertHidden(resolved);
    }

    @Test void anEndedMatchIsNotRevived() {
        String[] ids = mutual();
        consent.cancel(1, ids[0]);
        long version = consent.read(1, ids[0]).version;
        assertEquals(MatchState.CANCELLED, consent.read(1, ids[0]).matchState);
        Receipt receipt = intake.intake(2, "after", ids[1], EVIDENCE_TICKET_REF);
        assertLocal(receipt, "after", ids[1]);
        assertEquals(MatchState.CANCELLED, consent.read(2, ids[1]).matchState);
        assertEquals(version, consent.read(1, ids[0]).version);
        assertNull(consent.read(1, ids[0]).publicPlace);
    }

    private String[] mutual() {
        View first = consent.join(1, "pool", "v1", 100_000);
        View second = consent.join(2, "pool", "v1", 100_000);
        consent.propose(first.ticketId, second.ticketId, "public-place");
        consent.accept(1, first.ticketId, 1);
        consent.accept(2, second.ticketId, 2);
        return new String[]{first.ticketId, second.ticketId};
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private static void assertLocal(Receipt receipt, String requestId, String ticketId) {
        assertNotNull(receipt.caseId);
        assertEquals(requestId, receipt.requestId);
        assertEquals(ticketId, receipt.ticketId);
        assertEquals("LOCAL_INTAKE", receipt.state);
        assertEquals("NOT_CONNECTED", receipt.reviewConnection);
        assertNull(receipt.reviewRef);
        assertNull(receipt.dispositionCode);
    }

    private static void assertHidden(Receipt receipt) {
        assertEquals(7, Receipt.class.getDeclaredFields().length);
        for (java.lang.reflect.Field field : Receipt.class.getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object value = field.get(receipt);
                if (value != null) assertNotEquals(Long.toString(2L), value.toString());
            } catch (IllegalAccessException failed) {
                throw new AssertionError(failed);
            }
        }
    }

    private static void load(Configuration config, Path root, String path) throws Exception {
        try (InputStream input = new ByteArrayInputStream(Files.readAllBytes(root.resolve(path)))) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
    }

    static final class OpenSafety implements Safety {
        public boolean eligible(long actor, String pool, String version, long now) { return true; }
        public boolean blocked(long first, long second) { return false; }
        public boolean compatible(long first, long second, String pool, long now) { return true; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return "public-place".equals(place); }
    }

    static final class ScriptedReview implements ReviewPort {
        ReviewAnswer submitAnswer = ReviewAnswer.notConnected();
        ReviewAnswer statusAnswer = ReviewAnswer.notConnected();
        boolean submitThrows, statusThrows;
        int submits, statuses;
        public ReviewAnswer submit(String caseId, String requestId) {
            submits++;
            if (submitThrows) throw new IllegalStateException("REVIEW_TIMEOUT");
            return submitAnswer;
        }
        public ReviewAnswer status(String caseId, String requestId) {
            statuses++;
            if (statusThrows) throw new IllegalStateException("STATUS_TIMEOUT");
            return statusAnswer;
        }
    }

    /** The accept write fails. A following UNKNOWN write is allowed through. */
    static final class SentUpdateFails implements RendezvousReportCaseMapper {
        private final RendezvousReportCaseMapper delegate;
        SentUpdateFails(RendezvousReportCaseMapper delegate) { this.delegate = delegate; }
        public RendezvousReportCase.Row find(long reporterId, String requestId) { return delegate.find(reporterId, requestId); }
        public int insert(RendezvousReportCase.Row row) { return delegate.insert(row); }
        public int updateReview(String caseId, long reporterId, String expectedState, String state, String reviewConnection,
                                String reviewRef, String dispositionCode) {
            if ("SENT_TO_REVIEW".equals(state)) throw new IllegalStateException("REVIEW_STORE_FAILED");
            return delegate.updateReview(caseId, reporterId, expectedState, state, reviewConnection, reviewRef, dispositionCode);
        }
    }

    static final class InsertFails implements RendezvousReportCaseMapper {
        private final RendezvousReportCaseMapper delegate;
        InsertFails(RendezvousReportCaseMapper delegate) { this.delegate = delegate; }
        public RendezvousReportCase.Row find(long reporterId, String requestId) { return delegate.find(reporterId, requestId); }
        public int insert(RendezvousReportCase.Row row) { throw new IllegalStateException("INTAKE_STORE_FAILED"); }
        public int updateReview(String caseId, long reporterId, String expectedState, String state, String reviewConnection,
                                String reviewRef, String dispositionCode) {
            return delegate.updateReview(caseId, reporterId, expectedState, state, reviewConnection, reviewRef, dispositionCode);
        }
    }
}
