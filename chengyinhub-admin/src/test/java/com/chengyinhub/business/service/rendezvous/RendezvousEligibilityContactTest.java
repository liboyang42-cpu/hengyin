package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.controller.api.ApiRendezvousPoolController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.MatchState;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Termination;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.TicketState;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.View;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** H2 executes the real mapper XML. It is not MySQL acceptance. */
class RendezvousEligibilityContactTest {
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private SqlSessionTemplate session;
    private MyBatisRendezvousConsentStore store;
    private RendezvousConsentService consent;
    private RendezvousVersionedPolicy policy;
    private RendezvousPoolScheduler scheduler;
    private RendezvousContactLedger ledger;
    private final RendezvousProtocolTest.MutableClock clock = new RendezvousProtocolTest.MutableClock();

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16elig_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        Path root = sourceRoot();
        try (Connection connection = dataSource.getConnection()) {
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(root.resolve("chengyinhub-admin/sql/migration_w16_rendezvous_protocol_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(root.resolve("chengyinhub-admin/sql/migration_w16_eligibility_contact_candidate.sql"))));
        }
        Configuration config = new Configuration(new Environment("w16-elig", new SpringManagedTransactionFactory(), dataSource));
        for (String path : new String[]{
                "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml",
                "chengyinhub-system/src/main/resources/mapper/business/RendezvousProtocolMapper.xml",
                "chengyinhub-system/src/main/resources/mapper/business/RendezvousEligibilityMapper.xml",
                "chengyinhub-system/src/main/resources/mapper/business/RendezvousContactMapper.xml"}) {
            try (InputStream input = Files.newInputStream(root.resolve(path))) {
                new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        manager = new DataSourceTransactionManager(dataSource);
        store = new MyBatisRendezvousConsentStore(MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        RendezvousEligibilityMapper eligibility = session.getMapper(RendezvousEligibilityMapper.class);
        RendezvousProtocolMapper protocol = session.getMapper(RendezvousProtocolMapper.class);
        RendezvousContactMapper contacts = session.getMapper(RendezvousContactMapper.class);
        policy = new RendezvousVersionedPolicy(new MyBatisRendezvousCatalog(protocol, eligibility), new RendezvousProtocolTest.Blocks());
        ledger = new RendezvousContactLedger(contacts);
        consent = new RendezvousConsentService(store, policy, clock, ledger::charge);
        MyBatisRendezvousWaiting waiting = new MyBatisRendezvousWaiting(protocol, ledger);
        scheduler = new RendezvousPoolScheduler(consent, policy, waiting, clock);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.execute("SHUTDOWN");
    }

    @Test void openRowsDoNotQualifyWithoutACurrentFact() {
        dimensions("v1");
        dimensions("v0");
        poolRows();
        assertTrue(policy.factsConnected());
        assertFalse(policy.eligible(1, "pool", "v1", clock.now));
        assertFalse(new RendezvousVersionedPolicy(new MyBatisRendezvousCatalog(session.getMapper(RendezvousProtocolMapper.class)), new RendezvousProtocolTest.Blocks())
                .eligible(1, "pool", "v1", clock.now));
        jdbc.update("INSERT INTO w16_eligibility_current(id, version_id) VALUES (1, 'v1')");
        jdbc.update("INSERT INTO w16_eligibility_rule(version_id, decision, min_age, region_code, window_start, window_end) VALUES ('v1','OPEN',NULL,NULL,NULL,NULL)");
        assertFalse(policy.eligible(1, "pool", "v1", clock.now));
    }

    @Test void sameOpenConfigurationRejectsBadFactsAndAcceptsOnlyTheCurrentRule() {
        readyRules(1);
        dimensions("v0");
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 17, "CN-SH", 80_000);
        fact(4, "v1", 40, "CN-BJ", 80_000);
        fact(5, "v1", 30, "CN-SH", 10_000);
        fact(6, "v1", 30, "CN-SH", 80_000);
        fact(8, "v0", 30, "CN-SH", 80_000);
        assertTrue(policy.eligible(1, "pool", "v1", clock.now));
        assertFalse(policy.eligible(2, "pool", "v1", clock.now));
        assertFalse(policy.eligible(3, "pool", "v1", clock.now));
        assertFalse(policy.eligible(4, "pool", "v1", clock.now));
        assertFalse(policy.eligible(5, "pool", "v1", clock.now));
        clock.now = 100_000;
        assertFalse(policy.eligible(6, "pool", "v1", clock.now));
        clock.now = 10_000;
        assertFalse(policy.eligible(1, "pool", "v0", clock.now));
        assertFalse(policy.eligible(8, "pool", "v1", clock.now));
        assertEquals("NOT_ELIGIBLE", assertThrows(Rejection.class, () -> policy.requireClientVersion("v0")).getCode());
        policy.requireClientVersion("v1");
        assertEquals("NOT_ELIGIBLE", assertThrows(Rejection.class, () -> consent.join(2, "pool", "v1", 1000)).getCode());
    }

    @Test void capOneAllowsBothAcceptsThenRefusesTheNextPair() {
        readyRules(1);
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        fact(3, "v1", 22, "CN-SH", 80_000);
        consent.join(1, "pool", "v1", 100_000);
        consent.join(2, "pool", "v1", 100_000);
        consent.join(3, "pool", "v1", 100_000);
        String match = scheduler.pairOnce("pool");
        String firstTicket = jdbc.queryForObject("SELECT first_ticket FROM w16_rendezvous_match", String.class);
        String secondTicket = jdbc.queryForObject("SELECT second_ticket FROM w16_rendezvous_match", String.class);
        long firstActor = jdbc.queryForObject("SELECT actor_id FROM w16_match_ticket WHERE id=?", Long.class, firstTicket);
        long secondActor = jdbc.queryForObject("SELECT actor_id FROM w16_match_ticket WHERE id=?", Long.class, secondTicket);
        assertEquals(MatchState.PROPOSED, consent.accept(firstActor, firstTicket, 1).matchState);
        assertEquals(MatchState.MUTUAL_ACCEPTED, consent.accept(secondActor, secondTicket, 2).matchState);
        assertEquals(MatchState.MUTUAL_ACCEPTED, consent.read(firstActor, firstTicket).matchState);
        store.transaction(tx -> {
            assertTrue(consent.verifyParticipants(tx, firstActor, firstTicket, match, secondActor, true, clock.now).safe);
            assertThrows(IllegalStateException.class, () -> consent.read(firstActor, firstTicket));
            return null;
        });
        assertEquals(2, count("w16_contact_event"));
        consent.cancel(firstActor, firstTicket);
        consent.join(firstActor, "pool", "v1", 100_000);
        consent.join(secondActor, "pool", "v1", 100_000);
        assertEquals("PAIR_UNAVAILABLE", assertThrows(Rejection.class, () -> scheduler.pairOnce("pool")).getCode());
        assertEquals(2, count("w16_contact_event"));
        long waiting = 6 - firstActor - secondActor;
        assertEquals(TicketState.WAITING, consent.current(waiting).ticketState);
    }

    @Test void secondChargeFailureRollsBackTheMatchAndTheFirstInsert() {
        readyRules(1);
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        jdbc.update("INSERT INTO w16_contact_event(match_id, actor_id, pool, scope, charged_at) VALUES ('seed',2,'pool','POOL',?)", clock.now);
        consent.join(1, "pool", "v1", 100_000);
        consent.join(2, "pool", "v1", 100_000);
        assertEquals("PAIR_UNAVAILABLE", assertThrows(Rejection.class, () -> scheduler.pairOnce("pool")).getCode());
        assertEquals(0, count("w16_rendezvous_match"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_contact_event WHERE actor_id=1", Integer.class));
        assertEquals(TicketState.WAITING, consent.current(1).ticketState);
        assertEquals(1, count("w16_contact_event"));
    }

    @Test void directProposeRecordsBothCharges() {
        readyRules(1);
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        View a = consent.join(1, "pool", "v1", 100_000);
        View b = consent.join(2, "pool", "v1", 100_000);
        String match = consent.propose(a.ticketId, b.ticketId, "public-place");
        assertEquals(2, count("w16_contact_event"));
        assertEquals(clock.now, jdbc.queryForObject("SELECT charged_at FROM w16_contact_event WHERE match_id=? AND actor_id=1", Long.class, match));
        assertEquals(clock.now, jdbc.queryForObject("SELECT charged_at FROM w16_contact_event WHERE match_id=? AND actor_id=2", Long.class, match));
    }

    @Test void quotaWindowUsesGuardTimeNotTheListingTime() {
        readyRules(1);
        jdbc.update("UPDATE w16_contact_quota SET window_millis=10000 WHERE pool='pool'");
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        consent.join(1, "pool", "v1", 100_000);
        consent.join(2, "pool", "v1", 100_000);
        jdbc.update("INSERT INTO w16_contact_event(match_id, actor_id, pool, scope, charged_at) VALUES ('seed',1,'pool','POOL',15000)");
        clock.readings.add(1_000L);
        clock.readings.add(20_000L);
        assertEquals("PAIR_UNAVAILABLE", assertThrows(Rejection.class, () -> scheduler.pairOnce("pool")).getCode());
        assertEquals(1, count("w16_contact_event"));
        clock.readings.add(1_000L);
        clock.readings.add(20_000L);
        jdbc.update("DELETE FROM w16_contact_event");
        String match = scheduler.pairOnce("pool");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM w16_contact_event WHERE match_id=? AND charged_at=20000", Integer.class, match));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_contact_event WHERE match_id=? AND charged_at<>20000", Integer.class, match));
    }

    @Test void lostResponseAndDuplicateMatchChargeOnce() {
        readyRules(1);
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        View a = consent.join(1, "pool", "v1", 100_000);
        View b = consent.join(2, "pool", "v1", 100_000);
        RendezvousConsentService lost = new RendezvousConsentService(store, policy, clock, (id, first, second, pool, now) -> {
            ledger.charge(id, first, second, pool, now);
            throw new IllegalStateException("lost");
        });
        assertThrows(IllegalStateException.class, () -> lost.propose(a.ticketId, b.ticketId, "public-place"));
        assertEquals(TicketState.WAITING, consent.read(1, a.ticketId).ticketState);
        assertEquals(0, count("w16_contact_event"));
        String match = scheduler.pairOnce("pool");
        store.transaction(tx -> { ledger.charge(match, 1, 2, "pool", clock.now); return null; });
        assertEquals(2, count("w16_contact_event"));
    }

    @Test void missingScopeOrWindowRefusesWithoutALifetimeDefault() {
        readyRules(1);
        jdbc.update("DELETE FROM w16_contact_quota");
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        consent.join(1, "pool", "v1", 100_000);
        consent.join(2, "pool", "v1", 100_000);
        assertEquals("POLICY_CLOSED", assertThrows(Rejection.class, () -> scheduler.pairOnce("pool")).getCode());
        assertEquals(0, count("w16_contact_event"));
        assertEquals(0, count("w16_rendezvous_match"));
    }

    @Test void windowAndScopeLimitTheCount() {
        readyRules(1);
        jdbc.update("UPDATE w16_contact_quota SET window_millis=1000 WHERE pool='pool'");
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        jdbc.update("INSERT INTO w16_contact_event(match_id, actor_id, pool, scope, charged_at) VALUES ('old',1,'pool','POOL',?)", clock.now - 5000);
        jdbc.update("INSERT INTO w16_contact_event(match_id, actor_id, pool, scope, charged_at) VALUES ('other',1,'pool','OTHER',?)", clock.now);
        consent.join(1, "pool", "v1", 100_000);
        consent.join(2, "pool", "v1", 100_000);
        scheduler.pairOnce("pool");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM w16_contact_event WHERE scope='POOL' AND match_id<>'old'", Integer.class));
    }

    @Test void exitReleasesReservationsAndARepeatDoesNotWrite() {
        readyRules(2);
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        View a = consent.join(1, "pool", "v1", 100_000);
        View b = consent.join(2, "pool", "v1", 100_000);
        scheduler.pairOnce("pool");
        assertEquals(2, count("w16_match_reservation"));
        store.transaction(tx -> {
            consent.terminateCurrent(tx, 1, null, a.ticketId, Termination.EXIT, clock.now);
            return null;
        });
        assertEquals(0, count("w16_match_reservation"));
        assertEquals(MatchState.CANCELLED, consent.read(2, b.ticketId).matchState);
        int version = jdbc.queryForObject("SELECT version FROM w16_rendezvous_match", Integer.class);
        store.transaction(tx -> { consent.terminateCurrent(tx, 1, null, a.ticketId, Termination.EXIT, clock.now); return null; });
        assertEquals(version, jdbc.queryForObject("SELECT version FROM w16_rendezvous_match", Integer.class));
    }

    @Test void blockStopsTheSharedMatchAndARepeatDoesNotReviveIt() {
        readyRules(2);
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        View a = consent.join(1, "pool", "v1", 100_000);
        View b = consent.join(2, "pool", "v1", 100_000);
        scheduler.pairOnce("pool");
        consent.accept(1, a.ticketId, 1);
        consent.accept(2, b.ticketId, 2);
        assertEquals(2, count("w16_match_reservation"));
        store.transaction(tx -> { consent.terminateCurrentMatch(tx, 1, 2); return null; });
        assertEquals(0, count("w16_match_reservation"));
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(1, a.ticketId).matchState);
        assertEquals(TicketState.CANCELLED, consent.read(2, b.ticketId).ticketState);
        int version = jdbc.queryForObject("SELECT version FROM w16_rendezvous_match", Integer.class);
        store.transaction(tx -> { consent.terminateCurrentMatch(tx, 2, 1); return null; });
        assertEquals(version, jdbc.queryForObject("SELECT version FROM w16_rendezvous_match", Integer.class));
        assertEquals(MatchState.SAFETY_STOPPED, consent.accept(1, a.ticketId, version).matchState);
    }

    @Test void revocationStopsTheMatchAfterItsOwnCharge() {
        readyRules(1);
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        View a = consent.join(1, "pool", "v1", 100_000);
        consent.join(2, "pool", "v1", 100_000);
        scheduler.pairOnce("pool");
        jdbc.update("DELETE FROM w16_eligibility_fact WHERE actor_id=1");
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(1, a.ticketId).matchState);
        assertEquals(2, count("w16_contact_event"));
    }

    @Test void concurrentPairsReserveOneMatchAndOneCharge() throws Exception {
        readyRules(1);
        fact(1, "v1", 20, "CN-SH", 80_000);
        fact(2, "v1", 21, "CN-SH", 80_000);
        fact(3, "v1", 22, "CN-SH", 80_000);
        consent.join(1, "pool", "v1", 100_000);
        consent.join(2, "pool", "v1", 100_000);
        consent.join(3, "pool", "v1", 100_000);
        RendezvousPoolScheduler other = new RendezvousPoolScheduler(
                new RendezvousConsentService(store, policy, clock, ledger::charge), policy,
                new MyBatisRendezvousWaiting(session.getMapper(RendezvousProtocolMapper.class), ledger), clock);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(pair(scheduler, start));
            Future<Boolean> second = executor.submit(pair(other, start));
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertEquals(1, count("w16_rendezvous_match"));
        assertEquals(2, count("w16_contact_event"));
    }

    @Test void httpJoinUsesTheServerVersion() throws Exception {
        readyRules(2);
        fact(1, "v1", 20, "CN-SH", 80_000);
        dimensions("v0");
        RendezvousRoleSecretService secrets = new RendezvousRoleSecretService(consent, new RendezvousProtocolTest.SecretStore(new RendezvousProtocolTest.Db()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiRendezvousPoolController(consent, policy, secrets)).build();
        AppUser app = new AppUser();
        app.setUserId(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginAppUser(9L, app), null, Collections.emptyList()));
        ObjectMapper json = new ObjectMapper();
        var staleResult = mvc.perform(post("/api/rendezvous/pool/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pool\":\"pool\",\"eligibilityVersion\":\"v0\"}")).andReturn().getResponse();
        assertEquals(503, staleResult.getStatus());
        JsonNode stale = json.readTree(staleResult.getContentAsString());
        assertEquals("NOT_ELIGIBLE", stale.get("errorCode").asText());
        var current = mvc.perform(post("/api/rendezvous/pool/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pool\":\"pool\",\"eligibilityVersion\":\"v1\"}")).andReturn().getResponse();
        assertEquals(200, current.getStatus());
        assertEquals("v1", jdbc.queryForObject("SELECT eligibility_version FROM w16_match_ticket", String.class));
    }

    private Callable<Boolean> pair(RendezvousPoolScheduler target, CountDownLatch start) {
        return () -> { start.await(); try { target.pairOnce("pool"); return true; } catch (Rejection rejected) { return false; } };
    }

    private void readyRules(int max) {
        dimensions("v1");
        poolRows();
        jdbc.update("INSERT INTO w16_eligibility_current(id, version_id) VALUES (1, 'v1')");
        jdbc.update("INSERT INTO w16_eligibility_rule(version_id, decision, min_age, max_age, region_code, window_start, window_end) VALUES ('v1','OPEN',18,NULL,'CN-SH',0,100000)");
        jdbc.update("INSERT INTO w16_contact_quota(pool, scope, window_millis, max_contacts, decision) VALUES ('pool','POOL',100000,?, 'OPEN')", max);
    }

    private void dimensions(String version) {
        for (String dimension : new String[]{"eligibility-source", "age", "region", "time-window"}) {
            jdbc.update("INSERT INTO w16_policy_version(dimension, version_id, decision) VALUES (?,?, 'OPEN')", dimension, version);
        }
        jdbc.update("INSERT INTO w16_policy_version(dimension, version_id, decision, ticket_ttl_millis) VALUES ('ticket-ttl',?,'OPEN',100000)", version);
    }

    private void poolRows() {
        jdbc.update("INSERT INTO w16_policy_version(dimension, version_id, decision, max_contacts) VALUES ('frequency','pool','OPEN',2)");
        jdbc.update("INSERT INTO w16_policy_version(dimension, version_id, decision, public_place) VALUES ('meeting-safety','pool','OPEN','public-place')");
    }

    private void fact(long actor, String version, int age, String region, long expires) {
        jdbc.update("INSERT INTO w16_eligibility_fact(actor_id, rule_version, age_years, region_code, reviewed_at, expires_at) VALUES (?,?,?,?,?,?)",
                actor, version, age, region, 1, expires);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private static Path sourceRoot() {
        for (Path root = Paths.get("").toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_w16_eligibility_contact_candidate.sql"))) return root;
        }
        throw new IllegalStateException("W16_SOURCE_FIXTURE_ROOT_MISSING");
    }
}
