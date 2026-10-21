package com.chengyinhub.business.service.rendezvous;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleContentSource.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleSecretService.Projection;
import static org.junit.jupiter.api.Assertions.*;

/** Real H2/MyBatis statements. Not a MySQL acceptance test and not a connected content authority. */
class RendezvousRoleContentPersistenceTest {
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    SqlSessionTemplate session;
    MyBatisRendezvousConsentStore consentStore;
    MyBatisRendezvousRoleSecretStore secretStore;
    RendezvousConsentService consent;
    final MutableClock clock = new MutableClock();
    final FixtureSafety safety = new FixtureSafety();
    final CheckingGuard guard = new CheckingGuard(safety);
    ListAppender<ILoggingEvent> logs;
    Logger root;

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16role_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        Path root = RendezvousConsentPersistenceTest.sourceRoot();
        try (Connection connection = dataSource.getConnection()) {
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_role_content_candidate.sql"))));
        }
        Configuration config = new Configuration(new Environment("w16-role", new SpringManagedTransactionFactory(), dataSource));
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml");
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousRoleContentMapper.xml");
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        manager = new DataSourceTransactionManager(dataSource);
        consentStore = new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        secretStore = new MyBatisRendezvousRoleSecretStore(consentStore, session.getMapper(RendezvousRoleContentMapper.class));
        consent = new RendezvousConsentService(consentStore, safety, clock);
        logs = new ListAppender<>();
        logs.start();
        this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.root.addAppender(logs);
    }

    @AfterEach void cleanup() {
        root.detachAppender(logs);
        jdbc.execute("SHUTDOWN");
    }

    @Test void candidateMigrationInsertsNothing() throws Exception {
        String sql = Files.readString(RendezvousConsentPersistenceTest.sourceRoot()
                .resolve("chengyinhub-admin/sql/migration_w16_role_content_candidate.sql"));
        assertFalse(sql.toLowerCase().contains("insert into"));
        assertEquals(0, count("w16_role_content_publication"));
        assertEquals(0, count("w16_role_content_body"));
        assertEquals(0, count("w16_role_content_binding"));
        assertEquals(0, count("w16_role_content_grant"));
    }

    @Test void mapperReturnsOnlyTheRequestedRoleBody() {
        jdbc.update("INSERT INTO w16_role_content_publication(goal_id, publish_version, state) VALUES ('goal-1','pub-1','ISSUED')");
        jdbc.update("INSERT INTO w16_role_content_body(goal_id, publish_version, role_key, body) VALUES ('goal-1','pub-1','role-first','fixture-body-first')");
        jdbc.update("INSERT INTO w16_role_content_body(goal_id, publish_version, role_key, body) VALUES ('goal-1','pub-1','role-second','fixture-body-second')");
        RendezvousRoleContentMapper mapper = session.getMapper(RendezvousRoleContentMapper.class);
        assertEquals("fixture-body-first", mapper.roleBody("goal-1", "pub-1", "role-first"));
        assertEquals("fixture-body-second", mapper.roleBody("goal-1", "pub-1", "role-second"));
        assertNull(mapper.roleBody("goal-1", "pub-1", "role-missing"));
        assertEquals("ISSUED", mapper.publicationState("goal-1", "pub-1"));
        assertNull(mapper.publicationState("goal-1", "pub-missing"));
    }

    @Test void twoRolesShareOnePublicationAndRetryIsStableAcrossRestart() {
        String[] ids = mutual(1, 2);
        publish(ids[2], "goal-1", "pub-1", "role-first", "role-second", "fixture-body-first", "fixture-body-second");
        RendezvousRoleSecretService secrets = secrets();
        Projection mine = secrets.read(1, ids[0]);
        Projection again = secrets.read(1, ids[0]);
        Projection theirs = secrets.read(2, ids[1]);
        assertEquals("fixture-body-first", mine.roleClue);
        assertEquals("fixture-body-second", theirs.roleClue);
        assertEquals(mine.roleClue, again.roleClue);
        assertEquals(mine.secretRef, again.secretRef);
        assertEquals("goal-1", mine.goalId);
        assertEquals("goal-1", theirs.goalId);
        assertEquals("pub-1", mine.publishVersion);
        assertEquals("pub-1", theirs.publishVersion);
        assertEquals("role-first", mine.roleKey);
        assertEquals("role-second", theirs.roleKey);
        assertTrue(mine.willLead);
        assertFalse(theirs.willLead);
        assertTrue(mine.secretRef.matches("r\\.[A-Za-z0-9_-]{22}"));
        assertNotEquals(mine.roleClue, mine.secretRef);
        assertNotEquals(mine.secretRef, theirs.secretRef);
        assertFalse(mine.toString().contains("fixture-body"));
        assertFalse(mine.toString().contains(mine.secretRef));
        assertFalse(theirs.toString().contains("fixture-body-first"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_role_content_grant WHERE ticket_id=?", Integer.class, ids[0]));
        assertEquals(2, count("w16_role_content_grant"));
        Projection restored = new RendezvousRoleSecretService(consent, secretStore, guard, RendezvousRoleContentSource.Rows.INSTANCE, clock).read(1, ids[0]);
        assertEquals(mine.roleClue, restored.roleClue);
        assertEquals(mine.secretRef, restored.secretRef);
        assertEquals(mine.publishVersion, restored.publishVersion);
        assertSecretAbsent("fixture-body-second");
    }

    @Test void strangerWrongMatchAndWrongRoleRejectWithoutAGrant() {
        String[] ids = mutual(1, 2);
        publish(ids[2], "goal-1", "pub-1", "role-missing", "role-second", "fixture-body-first", "fixture-body-second");
        jdbc.update("DELETE FROM w16_role_content_body WHERE role_key='role-missing'");
        RendezvousRoleSecretService secrets = secrets();
        Rejection stranger = assertThrows(Rejection.class, () -> secrets.read(9, ids[0]));
        assertEquals("NOT_FOUND", stranger.getCode());
        assertNull(stranger.getCause());
        Rejection wrongRole = assertThrows(Rejection.class, () -> secrets.read(1, ids[0]));
        assertEquals("CONTENT_UNAVAILABLE", wrongRole.getCode());
        assertFalse(wrongRole.getMessage().contains("fixture-body-second"));
        assertNull(wrongRole.getCause());
        Projection second = secrets.read(2, ids[1]);
        assertEquals("fixture-body-second", second.roleClue);
        jdbc.update("UPDATE w16_role_content_binding SET match_id='other-match' WHERE match_id=?", ids[2]);
        Rejection wrongMatch = assertThrows(Rejection.class, () -> secrets.read(2, ids[1]));
        assertEquals("CONTENT_UNAVAILABLE", wrongMatch.getCode());
        assertFalse(wrongMatch.toString().contains("fixture-body-second"));
        assertEquals(1, count("w16_role_content_grant"));
        assertSecretAbsent("fixture-body-first");
        assertSecretAbsent("fixture-body-second");
    }

    @Test void expiryBetweenPrereadAndReadLeavesStaleMutualStateAndWritesNoGrant() {
        String[] ids = mutual(1, 2);
        publish(ids[2], "goal-1", "pub-1", "role-first", "role-second", "fixture-body-first", "fixture-body-second");
        assertEquals(MatchState.MUTUAL_ACCEPTED, consent.read(1, ids[0]).matchState);
        clock.now = 110_000;
        Rejection rejected = assertThrows(Rejection.class, () -> secrets().read(1, ids[0]));
        assertEquals("SECRET_UNAVAILABLE", rejected.getCode());
        assertNull(rejected.getCause());
        assertFalse(rejected.getMessage().contains("fixture-body"));
        assertEquals("MUTUAL_ACCEPTED", jdbc.queryForObject("SELECT state FROM w16_rendezvous_match WHERE id=?", String.class, ids[2]));
        assertEquals(0, count("w16_role_content_grant"));
        assertSecretAbsent("fixture-body-first");
    }

    @Test void revocationAndPlaceClosureRejectWhileTheMatchRowStaysMutual() {
        String[] ids = mutual(3, 4);
        publish(ids[2], "goal-2", "pub-1", "role-first", "role-second", "fixture-body-first", "fixture-body-second");
        safety.eligible = false;
        Rejection eligibility = assertThrows(Rejection.class, () -> secrets().read(3, ids[0]));
        assertEquals("SECRET_UNAVAILABLE", eligibility.getCode());
        assertEquals("MUTUAL_ACCEPTED", jdbc.queryForObject("SELECT state FROM w16_rendezvous_match WHERE id=?", String.class, ids[2]));
        assertEquals(0, count("w16_role_content_grant"));
        safety.eligible = true;
        safety.place = false;
        Rejection place = assertThrows(Rejection.class, () -> secrets().read(3, ids[0]));
        assertEquals("SECRET_UNAVAILABLE", place.getCode());
        assertEquals("MUTUAL_ACCEPTED", jdbc.queryForObject("SELECT state FROM w16_rendezvous_match WHERE id=?", String.class, ids[2]));
        safety.place = true;
        safety.blocked = true;
        Rejection blocked = assertThrows(Rejection.class, () -> secrets().read(4, ids[1]));
        assertEquals("SECRET_UNAVAILABLE", blocked.getCode());
        assertEquals(0, count("w16_role_content_grant"));
        assertFalse(blocked.getMessage().contains("fixture-body"));
    }

    @Test void cancelBetweenPrereadAndReadDoesNotIssue() {
        String[] ids = mutual(5, 6);
        publish(ids[2], "goal-3", "pub-1", "role-first", "role-second", "fixture-body-first", "fixture-body-second");
        assertEquals(MatchState.MUTUAL_ACCEPTED, consent.read(5, ids[0]).matchState);
        consent.cancel(5, ids[0]);
        Rejection rejected = assertThrows(Rejection.class, () -> secrets().read(5, ids[0]));
        assertEquals("SECRET_UNAVAILABLE", rejected.getCode());
        assertEquals(0, count("w16_role_content_grant"));
        assertFalse(rejected.toString().contains("fixture-body"));
    }

    @Test void offlineOrRevokedPublicationDoesNotReturnTheOldBody() {
        String[] ids = mutual(7, 8);
        publish(ids[2], "goal-4", "pub-1", "role-first", "role-second", "fixture-body-first", "fixture-body-second");
        Projection issued = secrets().read(7, ids[0]);
        assertEquals("fixture-body-first", issued.roleClue);
        jdbc.update("UPDATE w16_role_content_publication SET state='OFFLINE' WHERE goal_id='goal-4'");
        Rejection offline = assertThrows(Rejection.class, () -> secrets().read(7, ids[0]));
        assertEquals("CONTENT_UNAVAILABLE", offline.getCode());
        assertNull(offline.getCause());
        assertFalse(offline.getMessage().contains("fixture-body-first"));
        assertFalse(offline.toString().contains(issued.secretRef));
        jdbc.update("UPDATE w16_role_content_publication SET state='REVOKED' WHERE goal_id='goal-4'");
        Rejection revoked = assertThrows(Rejection.class, () -> secrets().read(8, ids[1]));
        assertEquals("CONTENT_UNAVAILABLE", revoked.getCode());
        assertFalse(revoked.getMessage().contains("fixture-body-second"));
        jdbc.update("UPDATE w16_role_content_publication SET state='ISSUED' WHERE goal_id='goal-4'");
        jdbc.update("UPDATE w16_role_content_binding SET state='REVOKED' WHERE match_id=?", ids[2]);
        Rejection binding = assertThrows(Rejection.class, () -> secrets().read(7, ids[0]));
        assertEquals("CONTENT_UNAVAILABLE", binding.getCode());
        assertEquals(1, count("w16_role_content_grant"));
        assertSecretAbsent("fixture-body-first");
        assertSecretAbsent("fixture-body-second");
    }

    @Test void missingCatalogAndClosedGuardDoNotFallBackToARandomClue() {
        String[] ids = mutual(9, 10);
        Rejection missing = assertThrows(Rejection.class, () -> secrets().read(9, ids[0]));
        assertEquals("CONTENT_UNAVAILABLE", missing.getCode());
        assertFalse(missing.getMessage().startsWith("r."));
        assertEquals(0, count("w16_role_content_grant"));
        publish(ids[2], "goal-5", "pub-1", "role-first", "role-second", "fixture-body-first", "fixture-body-second");
        RendezvousRoleSecretService closed = new RendezvousRoleSecretService(
                consent, secretStore, RendezvousRoleContentGuard.Closed.INSTANCE, RendezvousRoleContentSource.Rows.INSTANCE, clock);
        Rejection guarded = assertThrows(Rejection.class, () -> closed.read(9, ids[0]));
        assertEquals("SECRET_UNAVAILABLE", guarded.getCode());
        assertEquals(0, count("w16_role_content_grant"));
        jdbc.update("INSERT INTO w16_role_content_grant(ticket_id, actor_id, match_id, goal_id, publish_version, role_key, secret_ref) VALUES (?,?,?,?,?,?,?)",
                ids[0], 9, ids[2], "goal-5", "pub-1", "role-first", "fixture-body-first");
        Rejection corrupt = assertThrows(Rejection.class, () -> secrets().read(9, ids[0]));
        assertEquals("SECRET_UNAVAILABLE", corrupt.getCode());
        assertNull(corrupt.getCause());
        assertFalse(corrupt.getMessage().contains("fixture-body-first"));
    }

    @Test void adapterReadsParticipantsOnTheOpenTransactionAndStillRejectsExpiry() {
        String[] ids = mutual(11, 12);
        publish(ids[2], "goal-6", "pub-1", "role-first", "role-second", "fixture-body-first", "fixture-body-second");
        Transaction[] seen = new Transaction[1];
        long[] seenActor = {0};
        int[] calls = {0};
        RendezvousRoleContentGuardAdapter adapter = new RendezvousRoleContentGuardAdapter(
                (tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now) -> {
                    calls[0]++;
                    seen[0] = tx;
                    seenActor[0] = tx.ticket(ticketId).actor;
                    assertNull(expectedMatchId);
                    assertNull(expectedPeer);
                    assertTrue(requireMutualAccepted);
                    if (calls[0] == 1) assertEquals(ids[0], ticketId);
                    return consent.verifyParticipants(tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now);
                });
        Projection issued = new RendezvousRoleSecretService(
                consent, secretStore, adapter, RendezvousRoleContentSource.Rows.INSTANCE, clock).read(11, ids[0]);
        assertEquals("fixture-body-first", issued.roleClue);
        assertNotNull(seen[0]);
        assertEquals(11L, seenActor[0]);
        assertEquals(1, calls[0]);
        RendezvousRoleContentGuardAdapter absent = new RendezvousRoleContentGuardAdapter(
                (tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now) -> null);
        String[] other = mutual(13, 14);
        publish(other[2], "goal-7", "pub-1", "role-first", "role-second", "fixture-body-first", "fixture-body-second");
        Rejection missing = assertThrows(Rejection.class, () -> new RendezvousRoleSecretService(
                consent, secretStore, absent, RendezvousRoleContentSource.Rows.INSTANCE, clock).read(13, other[0]));
        assertEquals("SECRET_UNAVAILABLE", missing.getCode());
        clock.now = 110_000;
        Rejection expired = assertThrows(Rejection.class, () -> new RendezvousRoleSecretService(
                consent, secretStore, adapter, RendezvousRoleContentSource.Rows.INSTANCE, clock).read(12, ids[1]));
        assertEquals("SECRET_UNAVAILABLE", expired.getCode());
        assertEquals("MUTUAL_ACCEPTED", jdbc.queryForObject("SELECT state FROM w16_rendezvous_match WHERE id=?", String.class, ids[2]));
        assertEquals(1, count("w16_role_content_grant"));
        assertFalse(expired.getMessage().contains("fixture-body"));
    }

    @Test void grantWriteRollsBackWithTheGuardTransaction() {
        assertThrows(IllegalStateException.class, () -> secretStore.transaction(tx -> {
            tx.insertGrant(new Grant("ticket-x", 1, "match-x", "goal-x", "pub-x", "role-x", "r.0123456789012345678901"));
            throw new IllegalStateException("injected");
        }));
        assertEquals(0, count("w16_role_content_grant"));
        secretStore.transaction(tx -> {
            tx.insertGrant(new Grant("ticket-x", 1, "match-x", "goal-x", "pub-x", "role-x", "r.0123456789012345678901"));
            return null;
        });
        assertThrows(RuntimeException.class, () -> secretStore.transaction(tx -> {
            tx.insertGrant(new Grant("ticket-x", 1, "match-x", "goal-x", "pub-x", "role-x", "r.0123456789012345678902"));
            return null;
        }));
        assertEquals(1, count("w16_role_content_grant"));
        assertEquals("r.0123456789012345678901", jdbc.queryForObject("SELECT secret_ref FROM w16_role_content_grant", String.class));
    }

    private RendezvousRoleSecretService secrets() {
        return new RendezvousRoleSecretService(consent, secretStore, guard, RendezvousRoleContentSource.Rows.INSTANCE, clock);
    }

    private String[] mutual(long firstActor, long secondActor) {
        View first = consent.join(firstActor, "pool", "v1", 100_000);
        View second = consent.join(secondActor, "pool", "v1", 100_000);
        consent.propose(first.ticketId, second.ticketId, "public-poi");
        consent.accept(firstActor, first.ticketId, 1);
        View done = consent.accept(secondActor, second.ticketId, 2);
        return new String[]{first.ticketId, second.ticketId, done.matchId};
    }

    private void publish(String matchId, String goal, String version, String firstRole, String secondRole, String firstBody, String secondBody) {
        jdbc.update("INSERT INTO w16_role_content_publication(goal_id, publish_version, state) VALUES (?,?, 'ISSUED')", goal, version);
        jdbc.update("INSERT INTO w16_role_content_body(goal_id, publish_version, role_key, body) VALUES (?,?,?,?)", goal, version, firstRole, firstBody);
        jdbc.update("INSERT INTO w16_role_content_body(goal_id, publish_version, role_key, body) VALUES (?,?,?,?)", goal, version, secondRole, secondBody);
        jdbc.update("INSERT INTO w16_role_content_binding(match_id, goal_id, publish_version, first_role_key, second_role_key, state) VALUES (?,?,?,?,?, 'BOUND')",
                matchId, goal, version, firstRole, secondRole);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private void assertSecretAbsent(String secret) {
        for (ILoggingEvent event : logs.list) {
            assertFalse(String.valueOf(event.getFormattedMessage()).contains(secret), event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                assertFalse(String.valueOf(event.getThrowableProxy().getMessage()).contains(secret));
            }
        }
    }

    private static void load(Configuration config, Path root, String path) throws Exception {
        byte[] xml = Files.readAllBytes(root.resolve(path));
        try (InputStream input = new ByteArrayInputStream(xml)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
    }

    static final class MutableClock extends Clock {
        long now = 10_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    static final class FixtureSafety implements Safety {
        boolean eligible = true, place = true, blocked = false;
        public boolean eligible(long actor, String pool, String version, long now) { return eligible; }
        public boolean blocked(long a, long b) { return blocked; }
        public boolean compatible(long a, long b, String pool, long now) { return true; }
        public boolean publicMeetingAllowed(String pool, String value, long now) { return place && "public-poi".equals(value); }
    }

    static final class CheckingGuard implements RendezvousRoleContentGuard {
        final FixtureSafety safety;
        CheckingGuard(FixtureSafety safety) { this.safety = safety; }
        public void recheck(Transaction tx, long actor, String ticketId, long now) {
            Ticket ticket = tx.ticket(ticketId);
            if (ticket == null || ticket.actor != actor) throw new Rejection("NOT_FOUND");
            Match match = ticket.matchId == null ? null : tx.match(ticket.matchId);
            if (match == null || match.state != MatchState.MUTUAL_ACCEPTED
                    || match.firstAcceptedAt == null || match.secondAcceptedAt == null || match.expiresAt <= now) {
                throw new Rejection("SECRET_UNAVAILABLE");
            }
            Ticket a = tx.ticket(match.firstTicket), b = tx.ticket(match.secondTicket);
            if (a == null || b == null || !safety.eligible(a.actor, a.pool, a.eligibilityVersion, now)
                    || !safety.eligible(b.actor, b.pool, b.eligibilityVersion, now) || safety.blocked(a.actor, b.actor)
                    || !safety.publicMeetingAllowed(a.pool, match.publicPlace, now)) throw new Rejection("SECRET_UNAVAILABLE");
        }
    }
}
