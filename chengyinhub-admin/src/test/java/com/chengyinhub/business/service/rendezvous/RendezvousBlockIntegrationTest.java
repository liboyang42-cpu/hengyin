package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static org.junit.jupiter.api.Assertions.*;

/** H2 guard transaction. {@code real} calls {@code consent.terminateCurrentMatch}.
 * The synthetic port only covers an unwired or empty stop. ImServiceImpl.block is not on this path. */
class RendezvousBlockIntegrationTest {
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    SqlSessionTemplate session;
    MyBatisRendezvousConsentStore consentStore;
    RendezvousConsentService consent;
    RecordingAdmission charges;
    RendezvousRoleSecretService secrets;
    RendezvousMeetingService meetings;
    RendezvousBlockIntegration integration;
    RendezvousBlockIntegration real;
    SyntheticStop stop;
    final MutableClock clock = new MutableClock();
    final int[] creates = new int[1];

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16block_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        Path root = RendezvousConsentPersistenceTest.sourceRoot();
        try (Connection connection = dataSource.getConnection()) {
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_rendezvous_protocol_candidate.sql"))));
        }
        jdbc.execute("CREATE TABLE im_block(id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, blocked_member_id BIGINT NOT NULL, create_time TIMESTAMP, UNIQUE(member_id, blocked_member_id))");
        Configuration config = new Configuration(new Environment("w16-block", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousProtocolMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImBlockMapper.xml", true);
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        manager = new DataSourceTransactionManager(dataSource);
        consentStore = new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        ImBlockMapper blocks = session.getMapper(ImBlockMapper.class);
        BlockSafety safety = new BlockSafety(blocks);
        charges = new RecordingAdmission();
        consent = new RendezvousConsentService(consentStore, safety, clock, charges);
        secrets = new RendezvousRoleSecretService(consent, new MyBatisRendezvousRoleSecretStore(consentStore, session.getMapper(RendezvousProtocolMapper.class)));
        meetings = new RendezvousMeetingService(RendezvousMeetingService.Mode.ISOLATED_CONTRACT_EVALUATION,
                new MyBatisRendezvousMeetingStore(MyBatisRendezvousMeetingStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, manager),
                safety, new Goal(), handoff(), clock, 30_000);
        stop = new SyntheticStop();
        stop.mutate = true;
        integration = new RendezvousBlockIntegration(consentStore, blocks, stop);
        real = new RendezvousBlockIntegration(consentStore, blocks, RendezvousBlockIntegration.consentStop(consent));
        creates[0] = 0;
    }

    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }

    @Test void unwiredAndNoopAndFailedWritesLeaveTheMatchAndBlockTogether() {
        String[] ids = mutual(1, 2);
        String matchId = consent.read(1, ids[0]).matchId;
        RendezvousBlockIntegration closed = new RendezvousBlockIntegration(consentStore, session.getMapper(ImBlockMapper.class), RendezvousBlockIntegration.unwired());
        IllegalStateException unwired = assertThrows(IllegalStateException.class, () -> closed.block(block(1, 2)));
        assertEquals("W16_MATCH_TERMINATION_UNWIRED", unwired.getMessage());
        assertStillLive(matchId);

        stop.mutate = false;
        IllegalStateException noop = assertThrows(IllegalStateException.class, () -> integration.block(block(1, 2)));
        assertEquals("W16_MATCH_NOT_TERMINATED", noop.getMessage());
        assertEquals(1, stop.calls);
        assertStillLive(matchId);

        stop.failure = new IllegalStateException("W16_STOP_FAILED");
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> integration.block(block(1, 2)));
        assertEquals("W16_STOP_FAILED", failed.getMessage());
        assertStillLive(matchId);

        SyntheticStop unused = new SyntheticStop();
        RendezvousBlockIntegration broken = new RendezvousBlockIntegration(consentStore, new BrokenBlocks(true), unused);
        assertThrows(IllegalStateException.class, () -> broken.block(block(1, 2)));
        assertEquals(0, unused.calls);
        assertStillLive(matchId);

        RendezvousBlockIntegration unread = new RendezvousBlockIntegration(consentStore, new BrokenBlocks(false), unused);
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> unread.block(block(1, 2)));
        assertEquals("W16_BLOCK_NOT_PERSISTED", missing.getMessage());
        assertEquals(0, unused.calls);
        assertStillLive(matchId);
        assertThrows(IllegalArgumentException.class, () -> integration.block(block(1, 1)));
        assertThrows(IllegalStateException.class, () -> new RendezvousBlockIntegration(
                new MyBatisRendezvousConsentStore(), new BrokenBlocks(true), RendezvousBlockIntegration.unwired()).block(block(4, 5)));
    }

    @Test void blockStopsOnlyTheSharedMatchAndSurvivesRestart() {
        integration = real;
        String[] pair = mutual(1, 2);
        String[] other = mutual(3, 4);
        String matchId = consent.read(1, pair[0]).matchId;
        String otherId = consent.read(3, other[0]).matchId;
        String clue = secrets.read(1, pair[0]).roleClue;
        String challenge = meetings.issue(1, pair[0]).challengeId;
        assertNotNull(challenge);
        integration.block(block(1, 2));
        assertEquals("SAFETY_STOPPED", state(matchId));
        assertEquals("MUTUAL_ACCEPTED", state(otherId));
        assertEquals(2, count("w16_match_reservation"));
        assertEquals(1, count("im_block"));
        assertEquals("CHALLENGED", jdbc.queryForObject("SELECT state FROM w16_rendezvous_meeting", String.class));
        assertNull(jdbc.queryForObject("SELECT team_id FROM w16_rendezvous_meeting", Long.class));
        assertEquals(1, count("w16_role_secret"));
        assertEquals(0, creates[0]);
        assertRejected(pair[0], clue, matchId);

        integration.block(block(1, 2));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM im_block WHERE member_id=1 AND blocked_member_id=2", Integer.class));
        integration.block(block(2, 1));
        assertEquals(2, count("im_block"));
        assertEquals("SAFETY_STOPPED", state(matchId));
        assertEquals("MUTUAL_ACCEPTED", state(otherId));
        integration.unblock(1, 2);
        integration.unblock(2, 1);
        assertEquals(0, count("im_block"));
        assertRejected(pair[0], clue, matchId);

        RendezvousConsentService restarted = new RendezvousConsentService(newStore(), new BlockSafety(session.getMapper(ImBlockMapper.class)), clock, new RecordingAdmission());
        RendezvousRoleSecretService restartedSecrets = new RendezvousRoleSecretService(restarted,
                new MyBatisRendezvousRoleSecretStore(newStore(), session.getMapper(RendezvousProtocolMapper.class)));
        RendezvousMeetingService restartedMeetings = new RendezvousMeetingService(RendezvousMeetingService.Mode.ISOLATED_CONTRACT_EVALUATION,
                new MyBatisRendezvousMeetingStore(MyBatisRendezvousMeetingStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, manager),
                new BlockSafety(session.getMapper(ImBlockMapper.class)), new Goal(), handoff(), clock, 30_000);
        assertEquals(MatchState.SAFETY_STOPPED, restarted.read(1, pair[0]).matchState);
        assertNull(restarted.read(2, pair[1]).publicPlace);
        assertEquals("SECRET_UNAVAILABLE", assertThrows(Rejection.class, () -> restartedSecrets.read(1, pair[0])).getCode());
        assertEquals(RendezvousMeetingService.State.SAFETY_STOPPED, restartedMeetings.read(1, pair[0]).state);
        assertNull(restartedMeetings.handoff(2, pair[1]).challengeId);
        assertEquals(MatchState.MUTUAL_ACCEPTED, restarted.read(3, other[0]).matchState);
        assertEquals(0, creates[0]);
        assertEquals("SAFETY_STOPPED", state(matchId));
    }

    @Test void reverseBlockStopsThePairAndANonPartnerDoesNot() {
        integration = real;
        String[] pair = mutual(1, 2);
        String[] other = mutual(3, 4);
        String matchId = consent.read(1, pair[0]).matchId;
        integration.block(block(2, 1));
        assertEquals("SAFETY_STOPPED", state(matchId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM im_block WHERE member_id=2 AND blocked_member_id=1", Integer.class));
        assertEquals(MatchState.MUTUAL_ACCEPTED, consent.read(3, other[0]).matchState);

        String[] live = mutual(5, 6);
        String liveId = consent.read(5, live[0]).matchId;
        integration.block(block(5, 7));
        assertEquals("MUTUAL_ACCEPTED", state(liveId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM im_block WHERE member_id=5 AND blocked_member_id=7", Integer.class));
        assertNotNull(consent.read(6, live[1]).publicPlace);
    }

    /** Three-arg constructor selects guardStop, which calls stopBlockedPair, not terminateCurrentMatch. */
    @Test void legacyAdapterStillStopsInsideTheGuard() {
        String[] pair = mutual(8, 9);
        String matchId = consent.read(8, pair[0]).matchId;
        new RendezvousImBlockMutation(ISOLATED_PERSISTENCE_EVALUATION, session, manager).insert(block(8, 9));
        assertEquals("SAFETY_STOPPED", state(matchId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM w16_match_reservation r JOIN w16_match_ticket t ON t.id=r.ticket_id WHERE t.actor_id IN (8,9)",
                Integer.class));
    }

    @Test void realTerminateRollsBackWhenTheReservationIsCorrupt() {
        String[] ids = mutual(1, 2);
        String matchId = consent.read(1, ids[0]).matchId;
        assertEquals("W16_INVALID_BLOCK", assertThrows(IllegalArgumentException.class, () -> real.block(block(0, 2))).getMessage());
        assertEquals("W16_INVALID_BLOCK", assertThrows(IllegalArgumentException.class, () -> real.block(block(1, -5L))).getMessage());
        assertStillLive(matchId);
        jdbc.update("UPDATE w16_rendezvous_match SET first_ticket=? WHERE id=?", "corrupt-ticket", matchId);
        Rejection corrupt = assertThrows(Rejection.class, () -> real.block(block(1, 2)));
        assertEquals("CORRUPT_RESERVATION", corrupt.getCode());
        assertEquals(0, count("im_block"));
        assertEquals("MUTUAL_ACCEPTED", state(matchId));
        assertEquals(2, count("w16_match_reservation"));
    }

    @Test void blockRacesAcceptAndProposeWithoutAnActivePair() throws Exception {
        integration = real;
        View a = consent.join(6, "pool", "v1", 100_000);
        View b = consent.join(7, "pool", "v1", 100_000);
        consent.propose(a.ticketId, b.ticketId, "public-place");
        race(() -> integration.block(block(6, 7)), () -> consent.accept(7, b.ticketId, 1));
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(6, a.ticketId).matchState);
        assertNull(consent.read(7, b.ticketId).publicPlace);
        assertEquals(0, count("w16_match_reservation"));

        View c = consent.join(10, "pool", "v1", 100_000);
        View d = consent.join(11, "pool", "v1", 100_000);
        race(() -> integration.block(block(10, 11)), () -> {
            try {
                consent.propose(c.ticketId, d.ticketId, "public-place");
            } catch (Rejection rejected) {
                if (!"PAIR_UNAVAILABLE".equals(rejected.getCode())) throw rejected;
            }
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM im_block WHERE member_id=10 AND blocked_member_id=11", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM w16_rendezvous_match m WHERE m.state IN ('PROPOSED','MUTUAL_ACCEPTED') AND EXISTS (SELECT 1 FROM w16_match_ticket t WHERE t.match_id=m.id AND t.actor_id IN (10,11))",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM w16_match_reservation r JOIN w16_match_ticket t ON t.id=r.ticket_id WHERE t.actor_id IN (10,11)",
                Integer.class));
    }

    private void assertStillLive(String matchId) {
        assertEquals("MUTUAL_ACCEPTED", state(matchId));
        assertEquals(0, count("im_block"));
        assertEquals(2, count("w16_match_reservation"));
    }

    private void assertRejected(String ticketId, String clue, String matchId) {
        assertEquals(MatchState.SAFETY_STOPPED, consent.accept(1, ticketId, 99).matchState);
        assertNull(consent.read(1, ticketId).publicPlace);
        Rejection secret = assertThrows(Rejection.class, () -> secrets.read(1, ticketId));
        assertEquals("SECRET_UNAVAILABLE", secret.getCode());
        assertFalse(secret.getMessage().contains(clue));
        assertEquals(RendezvousMeetingService.State.SAFETY_STOPPED, meetings.read(1, ticketId).state);
        assertNull(meetings.read(1, ticketId).challengeId);
        assertEquals(RendezvousMeetingService.State.SAFETY_STOPPED, meetings.handoff(1, ticketId).state);
        assertEquals(0, creates[0]);
        assertNull(jdbc.queryForObject("SELECT team_id FROM w16_rendezvous_meeting", Long.class));
        assertEquals("SAFETY_STOPPED", state(matchId));
    }

    private String[] mutual(long first, long second) {
        View a = consent.join(first, "pool", "v1", 100_000);
        View b = consent.join(second, "pool", "v1", 100_000);
        int before = charges.charges;
        consent.propose(a.ticketId, b.ticketId, "public-place");
        assertEquals(before + 1, charges.charges);
        consent.accept(first, a.ticketId, 1);
        consent.accept(second, b.ticketId, 2);
        return new String[]{a.ticketId, b.ticketId};
    }

    private void race(Throwing first, Throwing second) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> left = workers.submit(() -> { start.await(); first.run(); return null; });
            Future<?> right = workers.submit(() -> { start.await(); second.run(); return null; });
            start.countDown();
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }

    private MyBatisRendezvousConsentStore newStore() {
        return new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager);
    }

    private String state(String matchId) {
        return jdbc.queryForObject("SELECT state FROM w16_rendezvous_match WHERE id=?", String.class, matchId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static ImBlock block(long actor, long other) {
        ImBlock block = new ImBlock();
        block.setMemberId(actor);
        block.setBlockedMemberId(other);
        return block;
    }

    private RendezvousMeetingService.Handoff handoff() {
        return new RendezvousMeetingService.Handoff() {
            public boolean available() { return true; }
            public long createAndJoin(long first, long second, long activity) {
                creates[0]++;
                return 77;
            }
        };
    }

    private static void load(Configuration config, Path root, String path, boolean clock) throws Exception {
        byte[] xml = Files.readAllBytes(root.resolve(path));
        if (clock) xml = new String(xml, StandardCharsets.UTF_8).replace("sysdate()", "CURRENT_TIMESTAMP").getBytes(StandardCharsets.UTF_8);
        try (InputStream input = new ByteArrayInputStream(xml)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
    }

    interface Throwing { void run() throws Exception; }

    static final class RecordingAdmission implements RendezvousConsentService.Admission {
        int charges;
        public void charge(String matchId, long first, long second, String pool, long now) {
            if (matchId == null || matchId.trim().isEmpty() || pool == null || pool.trim().isEmpty()
                    || first <= 0 || second <= 0 || first == second || now < 0) {
                throw new Rejection("PAIR_UNAVAILABLE");
            }
            charges++;
        }
    }

    static final class SyntheticStop implements RendezvousBlockIntegrationTermination {
        int calls;
        boolean mutate;
        RuntimeException failure;
        public void terminateCurrentMatch(Transaction tx, long actor, long other) {
            calls++;
            if (failure != null) throw failure;
            if (!mutate) return;
            Ticket a = tx.activeTicket(actor), b = tx.activeTicket(other);
            if (a == null || b == null || a.matchId == null || !a.matchId.equals(b.matchId)) return;
            Match match = tx.match(a.matchId);
            if (match == null || !match.active()) return;
            boolean pair = (match.firstTicket.equals(a.id) && match.secondTicket.equals(b.id))
                    || (match.firstTicket.equals(b.id) && match.secondTicket.equals(a.id));
            if (!pair) throw new Rejection("CORRUPT_RESERVATION");
            tx.save(a.transition(TicketState.CANCELLED, match.id));
            tx.save(b.transition(TicketState.CANCELLED, match.id));
            tx.save(new Match(match.id, match.firstTicket, match.secondTicket, match.publicPlace, match.expiresAt,
                    match.version + 1, match.firstAcceptedAt, match.secondAcceptedAt, MatchState.SAFETY_STOPPED));
        }
    }

    static final class BrokenBlocks implements ImBlockMapper {
        final boolean explode;
        BrokenBlocks(boolean explode) { this.explode = explode; }
        public int insertImBlock(ImBlock block) {
            if (explode) throw new IllegalStateException("W16_BLOCK_WRITE_FAILED");
            return 1;
        }
        public int deleteImBlock(Long memberId, Long blockedMemberId) { throw new AssertionError("no unblock"); }
        public ImBlock selectOne(Long memberId, Long blockedMemberId) { return null; }
        public int existsBetween(Long a, Long b) { return 0; }
    }

    static final class BlockSafety implements Safety {
        final ImBlockMapper blocks;
        BlockSafety(ImBlockMapper blocks) { this.blocks = blocks; }
        public boolean eligible(long actor, String pool, String version, long now) { return true; }
        public boolean blocked(long a, long b) { return blocks.existsBetween(a, b) > 0; }
        public boolean compatible(long a, long b, String pool, long now) { return true; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return "public-place".equals(place); }
    }

    static final class Goal implements RendezvousMeetingService.Policy {
        public long activity(Match match) { return 99; }
        public RendezvousMeetingService.Evidence evaluate(RendezvousMeetingService.Meeting meeting, Ticket first, Ticket second, long now) {
            return new RendezvousMeetingService.Evidence(true, true, false, false, false, false);
        }
    }

    static final class MutableClock extends Clock {
        long now = 10_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
