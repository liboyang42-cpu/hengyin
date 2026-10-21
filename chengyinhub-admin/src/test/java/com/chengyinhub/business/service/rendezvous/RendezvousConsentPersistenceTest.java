package com.chengyinhub.business.service.rendezvous;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** Real H2/MyBatis/Spring transactions. This is NOT a MySQL or production acceptance test. */
class RendezvousConsentPersistenceTest {
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    MyBatisRendezvousConsentStore store;
    RendezvousConsentService service;
    MutableClock clock;
    Policy policy;
    @org.junit.jupiter.api.io.TempDir Path temporary;
    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        initializeSchema();
        manager = new DataSourceTransactionManager(dataSource);
        store = newStore(); clock = new MutableClock(); policy = new Policy();
        service = new RendezvousConsentService(store, policy, clock);
    }
    void initializeSchema() throws Exception {
        // Execute candidate schema only in the newly allocated in-memory DB. These explicit
        // dialect substitutions are why passing H2 is not evidence of MySQL DDL acceptance.
        String schema = RendezvousH2Schema.consent(new String(Files.readAllBytes(
                sourceRoot().resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql")), StandardCharsets.UTF_8));
        try (Connection connection = dataSource.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }
    MyBatisRendezvousConsentStore newStore() throws Exception {
        return new MyBatisRendezvousConsentStore(MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION,
                session(dataSource), manager);
    }
    SqlSessionTemplate session(JdbcDataSource source) throws Exception {
        Configuration config = new Configuration(new Environment("isolated-w16", new SpringManagedTransactionFactory(), source));
        // Production XML is executed unchanged, with its declared result maps and statements.
        String path = "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml";
        try (InputStream input = Files.newInputStream(sourceRoot().resolve(path))) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }
    static Path sourceRoot() {
        for (Path root = Paths.get("").toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))
                    && Files.isRegularFile(root.resolve("chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml"))) return root;
        }
        throw new IllegalStateException("W16_SOURCE_FIXTURE_ROOT_MISSING");
    }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    View join(long actor) { return service.join(actor, "pool", "reviewed-v1", 1000); }
    View[] pair() {
        View a = join(1), b = join(2); service.propose(a.ticketId, b.ticketId, "public-poi");
        return new View[]{service.read(1, a.ticketId), service.read(2, b.ticketId)};
    }
    @Test void defaultDisabledMakesNoDatabaseCall() {
        assertThrows(IllegalStateException.class, () -> new MyBatisRendezvousConsentStore().transaction(tx -> fail("must not run")));
    }
    @Test void realCommitPersistsReceiptsAndNewServiceReadsThemWithoutReacceptance() throws Exception {
        View[] p = pair(); service.accept(1, p[0].ticketId, 1);
        RendezvousConsentService restarted = new RendezvousConsentService(newStore(), policy, clock);
        View restored = restarted.read(2, p[1].ticketId);
        assertEquals(MatchState.PROPOSED, restored.matchState); assertFalse(restored.acceptedByMe); assertTrue(restored.acceptedByOther);
        View mutual = restarted.accept(2, restored.ticketId, restored.version);
        assertEquals(MatchState.MUTUAL_ACCEPTED, mutual.matchState);
        assertEquals(2, count("w16_match_reservation"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_rendezvous_match WHERE first_accepted_at IS NOT NULL AND second_accepted_at IS NOT NULL", Integer.class));
    }
    @Test void committedReceiptsSurviveFileDatabaseShutdownAndReopen() throws Exception {
        jdbc.execute("SHUTDOWN");
        String url = "jdbc:h2:file:" + temporary.resolve("consent").toAbsolutePath() + ";MODE=MySQL;LOCK_TIMEOUT=5000";
        dataSource = new JdbcDataSource(); dataSource.setURL(url); jdbc = new JdbcTemplate(dataSource);
        initializeSchema(); manager = new DataSourceTransactionManager(dataSource); store = newStore();
        service = new RendezvousConsentService(store, policy, clock);
        View[] p = pair(); service.accept(1, p[0].ticketId, 1);
        jdbc.execute("SHUTDOWN");
        dataSource = new JdbcDataSource(); dataSource.setURL(url); jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource); store = newStore();
        service = new RendezvousConsentService(store, policy, clock);
        View restored = service.read(2, p[1].ticketId);
        assertEquals(MatchState.PROPOSED, restored.matchState);
        assertTrue(restored.acceptedByOther); assertFalse(restored.acceptedByMe);
        assertEquals(2, restored.version); assertEquals(2, count("w16_match_reservation"));
    }
    @Test void callbackFailureAfterFirstWriteRollsBackAllRows() {
        assertThrows(IllegalStateException.class, () -> store.transaction(tx -> {
            tx.save(new Ticket("synthetic", 7, "pool", "reviewed-v1", 1000, TicketState.WAITING, null));
            throw new IllegalStateException("injected interruption");
        }));
        assertEquals(0, count("w16_match_ticket")); assertEquals(0, count("w16_match_reservation"));
    }
    @Test void failureBetweenTicketWritesAndMatchWriteRollsBackReservations() {
        View a = join(1), b = join(2);
        assertThrows(IllegalStateException.class, () -> store.transaction(tx -> {
            tx.save(tx.ticket(a.ticketId).transition(TicketState.RESERVED, "synthetic-match"));
            tx.save(tx.ticket(b.ticketId).transition(TicketState.RESERVED, "synthetic-match"));
            throw new IllegalStateException("before match commit");
        }));
        assertEquals(0, count("w16_match_reservation")); assertEquals(0, count("w16_rendezvous_match"));
        assertEquals(TicketState.WAITING, service.read(1, a.ticketId).ticketState);
        assertEquals(TicketState.WAITING, service.read(2, b.ticketId).ticketState);
    }
    @Test void staleReceiptWriteRollsBackTicketMutationAndCannotOverwriteReceipt() {
        View[] p = pair(); service.accept(1, p[0].ticketId, 1);
        assertThrows(IllegalStateException.class, () -> store.transaction(tx -> {
            Ticket b = tx.ticket(p[1].ticketId); tx.save(b.transition(TicketState.ACCEPTED, b.matchId));
            Match m = tx.match(b.matchId);
            tx.save(new Match(m.id, m.firstTicket, m.secondTicket, m.publicPlace, m.expiresAt, m.version + 1,
                    Long.valueOf(123), Long.valueOf(123), MatchState.MUTUAL_ACCEPTED));
            return null;
        }));
        View b = service.read(2, p[1].ticketId);
        assertEquals(TicketState.RESERVED, b.ticketState); assertFalse(b.acceptedByMe); assertEquals(2, b.version);
    }
    @Test void cancellationAndExpiryReleaseBothUniqueReservations() {
        View[] p = pair(); service.accept(1, p[0].ticketId, 1); service.cancel(2, p[1].ticketId);
        assertEquals(0, count("w16_match_reservation"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_match_ticket WHERE active_actor_id IS NOT NULL", Integer.class));
        View a = join(1), b = join(2); service.propose(a.ticketId, b.ticketId, "public-poi"); clock.now += 1000;
        assertEquals(MatchState.EXPIRED, service.accept(1, a.ticketId, 1).matchState);
        assertEquals(0, count("w16_match_reservation"));
    }
    @Test void rejectedRejoinCommitsSafetyCleanupEvenThoughCallerReceivesRejection() {
        View[] p = pair(); policy.eligible = false;
        assertThrows(Rejection.class, () -> join(1));
        assertEquals(0, count("w16_match_reservation"));
        assertEquals(MatchState.SAFETY_STOPPED, service.read(2, p[1].ticketId).matchState);
    }
    @Test void databaseUniqueActiveActorPreventsDuplicateOptInEvenOutsideService() {
        join(7);
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO w16_match_ticket(id,actor_id,pool,eligibility_version,expires_at,state) VALUES ('other',7,'pool','reviewed-v1',20000,'WAITING')"));
        assertEquals(1, count("w16_match_ticket"));
    }
    @Test void reservationUniqueKeyPreventsCrossSideDoublePairing() {
        View[] p = pair();
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO w16_match_reservation(ticket_id,match_id) VALUES (?, 'different-match')", p[1].ticketId));
        assertEquals(2, count("w16_match_reservation"));
    }
    @Test void concurrentJoinsAcrossIndependentSessionsReturnOneTicket() throws Exception {
        RendezvousConsentService other = new RendezvousConsentService(newStore(), policy, clock);
        List<String> ids = race(() -> service.join(7, "pool", "reviewed-v1", 1000).ticketId,
                () -> other.join(7, "pool", "reviewed-v1", 1000).ticketId);
        assertEquals(ids.get(0), ids.get(1)); assertEquals(1, count("w16_match_ticket"));
    }
    @Test void concurrentProposalsAcrossIndependentSessionsCannotDoubleReserve() throws Exception {
        View a = join(1), b = join(2), c = join(3);
        RendezvousConsentService other = new RendezvousConsentService(newStore(), policy, clock);
        List<Boolean> results = race(() -> propose(service, a, b), () -> propose(other, c, a));
        assertNotEquals(results.get(0), results.get(1));
        assertEquals(1, count("w16_rendezvous_match")); assertEquals(2, count("w16_match_reservation"));
    }
    @Test void concurrentCancelAndAcceptNeverResurrectCancelledProposal() throws Exception {
        View[] p = pair(); RendezvousConsentService other = new RendezvousConsentService(newStore(), policy, clock);
        race(() -> service.cancel(1, p[0].ticketId), () -> other.accept(2, p[1].ticketId, 1));
        assertEquals(MatchState.CANCELLED, service.read(1, p[0].ticketId).matchState);
        assertEquals(0, count("w16_match_reservation"));
    }
    @Test void guardLockBlocksIndependentConnectionUntilCommit() throws Exception {
        MyBatisRendezvousConsentStore other = newStore(); ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), entered = new CountDownLatch(1);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) { statement.executeQuery("SELECT id FROM w16_consent_guard WHERE id=1 FOR UPDATE"); }
            Future<?> pending = executor.submit(() -> { started.countDown(); other.transaction(tx -> { entered.countDown(); return null; }); });
            assertTrue(started.await(2, TimeUnit.SECONDS)); assertFalse(entered.await(100, TimeUnit.MILLISECONDS));
            connection.commit(); pending.get(2, TimeUnit.SECONDS); assertEquals(0, entered.getCount());
        } finally { executor.shutdownNow(); }
    }
    @Test void expiredSessionCannotEscapeTransactionAndMissingGuardFailsClosed() {
        AtomicReference<Transaction> leaked = new AtomicReference<>();
        store.transaction(tx -> { leaked.set(tx); return null; });
        assertThrows(IllegalStateException.class, () -> leaked.get().activeTicket(7));
        jdbc.update("DELETE FROM w16_consent_guard");
        assertThrows(IllegalStateException.class, () -> join(7)); assertEquals(0, count("w16_match_ticket"));
    }
    @Test void nestedTransactionAndMismatchedResourceAreRejected() throws Exception {
        new TransactionTemplate(manager).execute(status -> {
            assertThrows(IllegalStateException.class, () -> join(7)); return null;
        });
        JdbcDataSource other = new JdbcDataSource(); other.setURL("jdbc:h2:mem:unrelated");
        SqlSessionTemplate session = session(other);
        assertThrows(IllegalArgumentException.class, () -> new MyBatisRendezvousConsentStore(
                MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, manager));
    }
    boolean propose(RendezvousConsentService s, View a, View b) {
        try { s.propose(a.ticketId, b.ticketId, "public-poi"); return true; } catch (Rejection rejected) { return false; }
    }
    <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> first = executor.submit(() -> { start.await(); return a.call(); });
            Future<T> second = executor.submit(() -> { start.await(); return b.call(); });
            start.countDown(); return Arrays.asList(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    static final class MutableClock extends Clock {
        long now = 10000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    static final class Policy implements Safety {
        boolean eligible = true;
        public boolean eligible(long actor, String pool, String version, long now) { return eligible; }
        public boolean blocked(long a, long b) { return false; }
        public boolean compatible(long a, long b, String pool, long now) { return true; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return "public-poi".equals(place); }
    }
}
