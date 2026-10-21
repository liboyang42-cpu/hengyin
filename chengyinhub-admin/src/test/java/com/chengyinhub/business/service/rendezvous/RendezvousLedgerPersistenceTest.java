package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.service.impl.ImServiceImpl;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.util.ReflectionTestUtils;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated H2. Not a MySQL acceptance test and not a production team write. */
class RendezvousLedgerPersistenceTest {
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    SqlSessionTemplate session;
    MyBatisRendezvousConsentStore consentStore;
    MyBatisRendezvousMeetingStore meetingStore;
    RendezvousConsentService consent;
    RendezvousMeetingService meetings;
    final MutableClock clock = new MutableClock();
    final int[] creates = new int[1];
    final int[] lookups = new int[1];
    final Map<String, Long> createdTeams = new HashMap<>();
    final Set<String> joinedTeams = new HashSet<>();
    boolean failJoin;

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16ledger_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        Path root = RendezvousConsentPersistenceTest.sourceRoot();
        try (Connection connection = dataSource.getConnection()) {
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_rendezvous_protocol_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_handoff_recovery_candidate.sql"))));
        }
        jdbc.execute("CREATE TABLE im_block(id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, blocked_member_id BIGINT NOT NULL, create_time TIMESTAMP, UNIQUE(member_id, blocked_member_id))");
        Configuration config = new Configuration(new Environment("w16-ledger", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousProtocolMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousHandoffMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousMeetingEvidenceMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImBlockMapper.xml", true);
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        manager = new DataSourceTransactionManager(dataSource);
        consentStore = new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        meetingStore = new MyBatisRendezvousMeetingStore(MyBatisRendezvousMeetingStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        OpenSafety safety = new OpenSafety();
        consent = new RendezvousConsentService(consentStore, safety, clock, RendezvousConsentServiceTest.permitsPairing);
        meetings = new RendezvousMeetingService(RendezvousMeetingService.Mode.ISOLATED_CONTRACT_EVALUATION, meetingStore, safety, (m, t, a, p, n) -> true, teamPort(), clock, 30_000);
        creates[0] = 0; lookups[0] = 0; failJoin = false; createdTeams.clear(); joinedTeams.clear();
    }

    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }

    @Test void closedPolicyRefusesJoinWithoutATicket() {
        RendezvousVersionedPolicy policy = new RendezvousVersionedPolicy(new MyBatisRendezvousCatalog(session.getMapper(RendezvousProtocolMapper.class)), session.getMapper(ImBlockMapper.class));
        RendezvousConsentService gated = new RendezvousConsentService(consentStore, policy, clock);
        Rejection rejected = assertThrows(Rejection.class, () -> gated.join(1, "pool", "v0", 1000));
        assertEquals("NOT_ELIGIBLE", rejected.getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_match_ticket", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_policy_version", Integer.class));
    }

    @Test void challengeSurvivesRestartAndExpiredReplayDoesNotReissue() {
        String[] ids = mutual();
        String challenge = meetings.issue(1, ids[0]).challengeId;
        assertEquals(challenge, meetings.issue(2, ids[1]).challengeId);
        RendezvousMeetingService restarted = new RendezvousMeetingService(RendezvousMeetingService.Mode.ISOLATED_CONTRACT_EVALUATION, newStore(), new OpenSafety(), (m, t, a, p, n) -> true, teamPort(), clock, 30_000);
        assertEquals(challenge, restarted.read(1, ids[0]).challengeId);
        assertEquals(0, creates[0]);
        clock.now = 40_000;
        assertEquals(RendezvousMeetingService.State.EXPIRED, meetings.confirm(1, ids[0], challenge).state);
        assertNull(meetings.issue(1, ids[0]).challengeId);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_rendezvous_meeting", Integer.class));
        assertThrows(Rejection.class, () -> meetings.confirm(2, ids[0], challenge));
    }

    @Test void partialHandoffIsQueriedWithoutASecondTeam() {
        String[] ids = mutual();
        String challenge = meetings.issue(1, ids[0]).challengeId;
        meetings.confirm(1, ids[0], challenge);
        meetings.confirm(2, ids[1], challenge);
        failJoin = true;
        RendezvousMeetingService.View failed = meetings.handoff(1, ids[0]);
        assertEquals(RendezvousMeetingService.State.RECOVERY_REQUIRED, failed.state);
        assertNull(failed.teamId);
        assertEquals(77L, jdbc.queryForObject("SELECT team_id FROM w16_rendezvous_meeting", Long.class));
        assertEquals(RendezvousMeetingService.State.RECOVERY_REQUIRED, meetings.result(2, ids[1]).state);
        assertEquals(1, creates[0]);
        meetings.handoff(2, ids[1]);
        assertEquals(1, creates[0]);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_rendezvous_meeting", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_handoff_request", Integer.class));
        assertEquals("TEAM_CREATED", jdbc.queryForObject("SELECT phase FROM w16_handoff_request", String.class));
    }

    @Test void completedHandoffReplayCreatesOneTeam() {
        String[] ids = mutual();
        String challenge = meetings.issue(1, ids[0]).challengeId;
        meetings.confirm(1, ids[0], challenge);
        meetings.confirm(2, ids[1], challenge);
        assertEquals(RendezvousMeetingService.State.COMPLETED, meetings.handoff(1, ids[0]).state);
        assertEquals(77L, meetings.result(2, ids[1]).teamId.longValue());
        int seen = lookups[0];
        assertEquals(RendezvousMeetingService.HandoffState.COMPLETE, meetings.handoffStateFor(consent.read(1, ids[0]).matchId));
        meetings.handoff(1, ids[0]);
        assertEquals(1, creates[0]);
        assertEquals(seen, lookups[0]);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_handoff_request", Integer.class));
    }

    @Test void missingOrUnapprovedEvidenceDoesNotIssue() {
        View a = consent.join(1, "pool", "v1", 100_000);
        View b = consent.join(2, "pool", "v1", 100_000);
        String matchId = consent.propose(a.ticketId, b.ticketId, "public-place");
        consent.accept(1, a.ticketId, 1);
        consent.accept(2, b.ticketId, 2);
        Rejection missing = assertThrows(Rejection.class, () -> meetings.issue(1, a.ticketId));
        assertEquals("GOAL_UNAVAILABLE", missing.getCode());
        jdbc.update("INSERT INTO w16_meeting_evidence(match_id, activity_id, place_id, first_actor, second_actor,"
                + " risk_clear, time_distance_plausible, merchant_required, merchant_verified, review_required,"
                + " review_approved, fact_source, rule_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                matchId, 99L, "public-place", 1L, 2L, 1, 1, 0, 0, 0, 0, "SCAN", "evidence-v1");
        Rejection scan = assertThrows(Rejection.class, () -> meetings.issue(1, a.ticketId));
        assertEquals("GOAL_UNAVAILABLE", scan.getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_handoff_request", Integer.class));
        assertEquals(0, creates[0]);
    }

    @Test void acceptAndBlockRaceLeavesNoActiveReservation() throws Exception {
        View a = consent.join(6, "pool", "v1", 100_000);
        View b = consent.join(7, "pool", "v1", 100_000);
        consent.propose(a.ticketId, b.ticketId, "public-place");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> block = workers.submit(() -> {
                start.await();
                ImBlock request = new ImBlock();
                request.setMemberId(6L);
                request.setBlockedMemberId(7L);
                new RendezvousImBlockMutation(ISOLATED_PERSISTENCE_EVALUATION, session, manager).insert(request);
                return null;
            });
            Future<?> accept = workers.submit(() -> { start.await(); consent.accept(7, b.ticketId, 1); return null; });
            start.countDown();
            block.get(10, TimeUnit.SECONDS);
            accept.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(6, a.ticketId).matchState);
        assertNull(consent.read(7, b.ticketId).publicPlace);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_match_reservation", Integer.class));
    }

    @Test void reportAndBlockStopThePairWithoutTouchingTheSharedBlockMethod() {
        String[] ids = mutual();
        new RendezvousReportStop(ISOLATED_PERSISTENCE_EVALUATION, session, manager, clock).report(2, 1);
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(1, ids[0]).matchState);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_safety_report", Integer.class));
        new RendezvousReportStop(ISOLATED_PERSISTENCE_EVALUATION, session, manager, clock).report(2, 1);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_safety_report", Integer.class));

        View a = consent.join(4, "pool", "v1", 100_000);
        View b = consent.join(5, "pool", "v1", 100_000);
        consent.propose(a.ticketId, b.ticketId, "public-place");
        ImServiceImpl im = new ImServiceImpl();
        ReflectionTestUtils.setField(im, "blockMapper", session.getMapper(ImBlockMapper.class));
        im.block(4L, 5L);
        assertEquals(MatchState.PROPOSED, consent.read(4, a.ticketId).matchState);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM im_block", Integer.class));
        ImBlock block = new ImBlock();
        block.setMemberId(4L); block.setBlockedMemberId(5L);
        new RendezvousImBlockMutation(ISOLATED_PERSISTENCE_EVALUATION, session, manager).insert(block);
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(4, a.ticketId).matchState);
    }

    private String[] mutual() {
        View a = consent.join(1, "pool", "v1", 100_000);
        View b = consent.join(2, "pool", "v1", 100_000);
        consent.propose(a.ticketId, b.ticketId, "public-place");
        consent.accept(1, a.ticketId, 1);
        consent.accept(2, b.ticketId, 2);
        jdbc.update("INSERT INTO w16_meeting_evidence(match_id, activity_id, place_id, first_actor, second_actor,"
                + " risk_clear, time_distance_plausible, merchant_required, merchant_verified, review_required,"
                + " review_approved, fact_source, rule_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                consent.read(1, a.ticketId).matchId, 99L, "public-place", 1L, 2L, 1, 1, 0, 0, 0, 0,
                "AUTHORITY_FACT", "evidence-v1");
        return new String[]{a.ticketId, b.ticketId};
    }

    private MyBatisRendezvousMeetingStore newStore() {
        return new MyBatisRendezvousMeetingStore(MyBatisRendezvousMeetingStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, manager);
    }

    private RendezvousHandoffTeamPort teamPort() {
        return new RendezvousHandoffTeamPort() {
            public boolean available() { return true; }
            public Lookup lookup(String requestId) {
                lookups[0]++;
                if (joinedTeams.contains(requestId)) return Lookup.joined(createdTeams.get(requestId));
                if (createdTeams.containsKey(requestId)) return Lookup.created(createdTeams.get(requestId));
                return Lookup.absent();
            }
            public Created create(String requestId, long first, long second, long activity) {
                if (createdTeams.containsKey(requestId)) return new Created(createdTeams.get(requestId));
                creates[0]++;
                assertEquals(1, first); assertEquals(2, second); assertEquals(99, activity);
                createdTeams.put(requestId, 77L);
                return new Created(77L);
            }
            public void join(String requestId) {
                if (!createdTeams.containsKey(requestId) || failJoin) throw new Unconfirmed(createdTeams.get(requestId));
                joinedTeams.add(requestId);
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

    static final class MutableClock extends Clock {
        long now = 10_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    static final class OpenSafety implements Safety {
        public boolean eligible(long actor, String pool, String version, long now) { return true; }
        public boolean blocked(long a, long b) { return false; }
        public boolean compatible(long a, long b, String pool, long now) { return true; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return "public-place".equals(place); }
    }
}
