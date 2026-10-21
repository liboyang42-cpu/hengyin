package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.ExitRow;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.Mode;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.View;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
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
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.MatchState;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.TicketState;
import static com.chengyinhub.business.service.rendezvous.RendezvousSafeExitStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2 executes the real consent session and the exit mapper together. Not MySQL acceptance. */
class RendezvousSafeExitPersistenceTest {
    private RendezvousExitDatabase db;

    @BeforeEach void setup() throws Exception { db = RendezvousExitDatabase.open(); }
    @AfterEach void cleanup() { if (db != null) db.close(); }

    @Test void disabledStoreDoesNotOpenADatabaseCall() {
        assertEquals("W16_EXIT_PERSISTENCE_DISABLED",
                assertThrows(IllegalStateException.class, () -> new RendezvousSafeExitStore().find("ticket-1")).getMessage());
    }

    @Test void exitOutsideTheConsentGuardIsRejected() {
        assertEquals("W16_CONSENT_TRANSACTION_NOT_BOUND",
                assertThrows(IllegalStateException.class, () -> db.exits.find("ticket-1")).getMessage());
    }

    @Test void mismatchedTransactionManagerIsRejected() {
        JdbcDataSource other = new JdbcDataSource();
        other.setURL("jdbc:h2:mem:w16exit_other_" + UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> new RendezvousSafeExitStore(
                ISOLATED_PERSISTENCE_EVALUATION, db.session, new DataSourceTransactionManager(other)));
    }

    @Test void waitingExitCancelsOnlyThatTicket() {
        RendezvousConsentService.View ticket = db.consent.join(1, "pool", "v1", 100_000);
        View exited = db.service.choose(1, ticket.ticketId, "EXIT");
        assertEquals("WAITING", exited.phase);
        assertEquals("NOT_PUBLISHED", exited.handoff);
        assertFalse(exited.soloAllowed);
        assertFalse(exited.completed);
        assertEquals(TicketState.CANCELLED, db.consent.read(1, ticket.ticketId).ticketState);
        assertEquals(0, db.count("w16_match_reservation"));
        assertEquals(1, db.count("w16_safe_exit"));
    }

    @Test void matchedExitReleasesBothReservationsAndARepeatDoesNotWriteAgain() {
        RendezvousConsentService.View[] pair = db.pair();
        assertEquals(2, db.count("w16_match_reservation"));
        assertEquals(2, db.count("w16_contact_event"));
        View exited = db.service.choose(1, pair[0].ticketId, "EXIT");
        assertEquals("ONE_SIDED", exited.phase);
        assertEquals(MatchState.CANCELLED, db.consent.read(1, pair[0].ticketId).matchState);
        assertEquals(MatchState.CANCELLED, db.consent.read(2, pair[1].ticketId).matchState);
        assertEquals(0, db.count("w16_match_reservation"));
        assertEquals(2, db.count("w16_contact_event"));
        Integer version = db.jdbc.queryForObject("SELECT version FROM w16_rendezvous_match", Integer.class);
        View again = db.service.choose(1, pair[0].ticketId, "EXIT");
        assertEquals(exited.ticketId, again.ticketId);
        assertFalse(again.completed);
        assertEquals(version, db.jdbc.queryForObject("SELECT version FROM w16_rendezvous_match", Integer.class));
        assertEquals(1, db.count("w16_safe_exit"));
    }

    @Test void mutualExitCancelsRatherThanSafetyStopping() {
        RendezvousConsentService.View[] pair = db.pair();
        db.consent.accept(1, pair[0].ticketId, 1);
        db.consent.accept(2, pair[1].ticketId, 2);
        View exited = db.service.choose(1, pair[0].ticketId, "EXIT");
        assertEquals("MUTUAL", exited.phase);
        assertEquals(MatchState.CANCELLED, db.consent.read(2, pair[1].ticketId).matchState);
        assertEquals(0, db.count("w16_match_reservation"));
    }

    @Test void pastDueMatchExpiresInsideTheSameExit() {
        RendezvousConsentService.View[] pair = db.pair();
        db.clock.now = db.jdbc.queryForObject("SELECT expires_at FROM w16_rendezvous_match", Long.class);
        db.service.choose(1, pair[0].ticketId, "EXIT");
        assertEquals(MatchState.EXPIRED, db.consent.read(1, pair[0].ticketId).matchState);
        assertEquals(TicketState.EXPIRED, db.consent.read(2, pair[1].ticketId).ticketState);
        assertEquals(0, db.count("w16_match_reservation"));
    }

    @Test void insertFailureRollsBackTheSeatRelease() {
        RendezvousConsentService.View[] pair = db.pair();
        RendezvousSafeExitMapper real = db.session.getMapper(RendezvousSafeExitMapper.class);
        RendezvousSafeExitStore failing = new RendezvousSafeExitStore(ISOLATED_PERSISTENCE_EVALUATION, db.session, db.manager,
                new RendezvousSafeExitMapper() {
                    public Integer lockGuard() { return real.lockGuard(); }
                    public ExitRow find(String ticketId) { return real.find(ticketId); }
                    public int insert(ExitRow row) {
                        real.insert(row);
                        throw new IllegalStateException("injected rollback");
                    }
                    public int markLaunch(ExitRow row) { return real.markLaunch(row); }
                });
        RendezvousSafeExitService service = new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION,
                db.guard, db.consent, failing, db.clock);
        assertThrows(IllegalStateException.class, () -> service.choose(1, pair[0].ticketId, "EXIT"));
        assertEquals(2, db.count("w16_match_reservation"));
        assertEquals(0, db.count("w16_safe_exit"));
        assertEquals(TicketState.RESERVED, db.consent.read(1, pair[0].ticketId).ticketState);
        assertEquals(TicketState.RESERVED, db.consent.read(2, pair[1].ticketId).ticketState);
        assertEquals(MatchState.PROPOSED, db.consent.read(1, pair[0].ticketId).matchState);
    }

    @Test void soloDoesNotReleaseOrCompleteWhileHandoffAndLauncherAreUnpublished() {
        RendezvousConsentService.View[] pair = db.pair();
        assertEquals("SOLO_LAUNCHER_UNAVAILABLE",
                assertThrows(Rejection.class, () -> db.service.choose(1, pair[0].ticketId, "SOLO")).getCode());
        assertEquals(2, db.count("w16_match_reservation"));
        assertEquals(0, db.count("w16_safe_exit"));
        assertEquals(TicketState.RESERVED, db.consent.read(1, pair[0].ticketId).ticketState);
        View options = db.service.options(1, pair[0].ticketId);
        assertFalse(options.soloAllowed);
        assertFalse(options.completed);
        assertEquals("NOT_PUBLISHED", options.handoff);
        assertTrue(options.exitAllowed);
    }

    @Test void strangerAndDisabledExitWriteNothing() {
        RendezvousConsentService.View[] pair = db.pair();
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> db.service.choose(9, pair[0].ticketId, "EXIT")).getCode());
        RendezvousSafeExitService disabled = new RendezvousSafeExitService(Mode.DISABLED, db.guard, db.consent, db.exits, db.clock);
        assertEquals("W16_EXIT_DISABLED", assertThrows(Rejection.class, () -> disabled.choose(1, pair[0].ticketId, "EXIT")).getCode());
        assertEquals(2, db.count("w16_match_reservation"));
        assertEquals(0, db.count("w16_safe_exit"));
        assertEquals("EXIT_CONFLICT", assertThrows(Rejection.class, () -> {
            db.service.choose(1, pair[0].ticketId, "EXIT");
            db.service.choose(1, pair[0].ticketId, "SOLO");
        }).getCode());
    }
}

/** One SqlSession and one transaction manager for consent and the exit ledger. */
final class RendezvousExitDatabase {
    final JdbcDataSource dataSource;
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager manager;
    final SqlSessionTemplate session;
    final MyBatisRendezvousConsentStore guard;
    final RendezvousConsentService consent;
    final RendezvousSafeExitStore exits;
    final RendezvousSafeExitService service;
    final RendezvousConsentServiceTest.MutableClock clock;

    private RendezvousExitDatabase(JdbcDataSource dataSource, JdbcTemplate jdbc, DataSourceTransactionManager manager,
                                   SqlSessionTemplate session, MyBatisRendezvousConsentStore guard,
                                   RendezvousConsentService consent, RendezvousSafeExitStore exits,
                                   RendezvousSafeExitService service, RendezvousConsentServiceTest.MutableClock clock) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.manager = manager;
        this.session = session;
        this.guard = guard;
        this.consent = consent;
        this.exits = exits;
        this.service = service;
        this.clock = clock;
    }

    static RendezvousExitDatabase open() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16exit_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Path root = sourceRoot();
        try (Connection connection = dataSource.getConnection()) {
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_eligibility_contact_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_safe_exit_solo_candidate.sql"))));
        }
        Configuration config = new Configuration(new Environment("w16-exit", new SpringManagedTransactionFactory(), dataSource));
        for (String path : new String[]{
                "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml",
                "chengyinhub-system/src/main/resources/mapper/business/RendezvousProtocolMapper.xml",
                "chengyinhub-system/src/main/resources/mapper/business/RendezvousContactMapper.xml",
                "chengyinhub-system/src/main/resources/mapper/business/RendezvousSafeExitMapper.xml"}) {
            try (InputStream input = Files.newInputStream(root.resolve(path))) {
                new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        MyBatisRendezvousConsentStore guard = new MyBatisRendezvousConsentStore(
                MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        RendezvousContactLedger ledger = new RendezvousContactLedger(session.getMapper(RendezvousContactMapper.class));
        MyBatisRendezvousWaiting admission = new MyBatisRendezvousWaiting(session.getMapper(RendezvousProtocolMapper.class), ledger);
        RendezvousConsentServiceTest.MutableClock clock = new RendezvousConsentServiceTest.MutableClock();
        RendezvousConsentService consent = new RendezvousConsentService(guard, new RendezvousConsentServiceTest.SafetyPolicy(), clock, admission);
        RendezvousSafeExitStore exits = new RendezvousSafeExitStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        RendezvousSafeExitService service = new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION, guard, consent, exits, clock);
        jdbc.update("INSERT INTO w16_contact_quota(pool, scope, window_millis, max_contacts, decision) VALUES ('pool','POOL',100000,4,'OPEN')");
        return new RendezvousExitDatabase(dataSource, jdbc, manager, session, guard, consent, exits, service, clock);
    }

    RendezvousConsentService.View[] pair() {
        RendezvousConsentService.View first = consent.join(1, "pool", "v1", 100_000);
        RendezvousConsentService.View second = consent.join(2, "pool", "v1", 100_000);
        consent.propose(first.ticketId, second.ticketId, "approved-public-place");
        return new RendezvousConsentService.View[]{consent.read(1, first.ticketId), consent.read(2, second.ticketId)};
    }

    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    void close() { jdbc.execute("SHUTDOWN"); }

    private static Path sourceRoot() {
        for (Path root = Paths.get("").toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_w16_safe_exit_solo_candidate.sql"))
                    && Files.isRegularFile(root.resolve("chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml"))) {
                return root;
            }
        }
        throw new IllegalStateException("W16_EXIT_SOURCE_MISSING");
    }
}
