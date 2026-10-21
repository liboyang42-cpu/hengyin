package com.chengyinhub.business.citygame.analytics.fairness.source;

import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessIngestionService;
import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReader;
import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReportService;
import com.chengyinhub.business.citygame.analytics.fairness.contract.FairnessFieldContract;
import com.chengyinhub.business.citygame.analytics.fairness.source.CityFairnessOnsiteRelay.RelayResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixture schema only. This does not run the onsite producer. */
class CityFairnessOnsiteRelayTest {
    private static final long MEMBER = 7L;
    private static final String HASH = "a".repeat(64);
    private static final String RULES = "rules-rel-1";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T18:00:00Z"), ZoneOffset.UTC);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CityFairnessOnsiteRelay bean;
    private static CityFairnessReportService reports;
    private static int seq;

    @BeforeAll
    static void start() throws Exception {
        context = new AnnotationConfigApplicationContext(Wiring.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        bean = context.getBean(CityFairnessOnsiteRelay.class);
        AopTestUtils.<CityFairnessOnsiteRelay>getUltimateTargetObject(bean).clock = CLOCK;
        reports = context.getBean(CityFairnessReportService.class);
        String xml = new String(CityFairnessOnsiteRelayTest.class.getClassLoader()
                .getResourceAsStream("mapper/business/FairnessOnsiteSourceMapper.xml").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("join city_onsite_task_instance"));
        assertTrue(xml.contains("join city_onsite_task_request"));
        assertFalse(xml.toLowerCase().contains("for update"));
        assertFalse(xml.contains("option_id"));
        assertFalse(xml.contains("result_json"));
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @Test
    void springInjectsTheProductionIngestionBean() {
        CityFairnessOnsiteRelay target = AopTestUtils.getUltimateTargetObject(bean);
        assertSame(context.getBean(CityFairnessIngestionService.class), target.ingestion);
    }

    @Test
    void refusedRowsWriteNoEvent() {
        String exhausted = seed("EXHAUSTED", "EXHAUSTED", "COMPLETE", 200, MEMBER, "2026-10-21 12:00:00");
        String unknown = seed("PASSED", "PASSED", "UNKNOWN", 200, MEMBER, "2026-10-21 12:00:00");
        String passed = seed("PASSED", "PASSED", "COMPLETE", 200, MEMBER, "2026-10-21 12:00:00");
        assertEquals("EXHAUSTED", bean.forward(MEMBER, exhausted).status);
        assertEquals("UNKNOWN", bean.forward(MEMBER, unknown).status);
        assertEquals("CROSS_ACCOUNT", bean.forward(8L, passed).status);
        assertEquals("ABSENT", bean.forward(MEMBER, "missing-result").status);
        assertEquals("ABSENT", bean.forward(MEMBER, resultWithoutRequest()).status);
        assertEquals("NOT_AN_OUTING", bean.reopen(MEMBER).status);
        assertEquals("SOURCE_UNBOUND", bean.forward(MEMBER, passed).status);
        String lateBind = seed("PASSED", "PASSED", "COMPLETE", 200, MEMBER, "2026-10-21 12:00:00");
        assertEquals("NOT_ACTIVE", bean.bind(MEMBER, instanceOf(lateBind)).status);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from city_fairness_event where event_id in (?,?,?,?)",
                Integer.class, exhausted, unknown, passed, lateBind));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from city_fairness_outing where instance_id in (select instance_id from city_onsite_task_result where id in (?,?,?,?))",
                Integer.class, exhausted, unknown, passed, lateBind));
    }

    @Test
    void policyOffAndCrossScopeDoNotIngest() {
        String result = seed("ACTIVE", "PASSED", "COMPLETE", 200, MEMBER, "2026-10-21 12:00:00.9");
        String instance = instanceOf(result);
        approve(scopeOf(result));
        RelayResult bound = bean.bind(MEMBER, instance);
        assertEquals("BOUND", bound.status);
        assertTrue(bound.outingId.matches("[0-9]+"));
        jdbc.update("update city_onsite_task_instance set status='PASSED' where id=?", instance);
        assertEquals("POLICY_OFF", bean.forward(MEMBER, result).status);
        policy(scopeOf(result));
        jdbc.update("update city_fairness_outing set board_id='other-board' where instance_id=?", instance);
        RelayResult crossed = bean.forward(MEMBER, result);
        assertEquals("CROSS_SCOPE", crossed.status);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from city_fairness_event where event_id=?", Integer.class, result));
    }

    @Test
    void approvedPassStoresAndTheOwnOutingReadReturnsIt() {
        Scope scope = new Scope("board-live", "region-live", "season-live");
        String result = seed(scope, "ACTIVE", "PASSED", "COMPLETE", 200, MEMBER, "2026-10-21 12:00:00.9");
        String instance = instanceOf(result);
        approve(scope);
        policy(scope);
        String outingId = bean.bind(MEMBER, instance).outingId;
        jdbc.update("update city_onsite_task_instance set status='PASSED' where id=?", instance);
        RelayResult stored = bean.forward(MEMBER, result);
        RelayResult replay = bean.forward(MEMBER, result);
        assertEquals("STORED", stored.status);
        assertEquals("DEDUPED", replay.status);
        assertEquals(result, stored.eventId);
        assertEquals(outingId, stored.outingId);
        assertEquals(0, stored.occurredAt % 1000);
        assertEquals(stored.occurredAt, jdbc.queryForObject(
                "select known_at from city_fairness_event where event_id=?", Long.class, result));
        assertEquals(stored.occurredAt + 5_000L, stored.verifiedAt);
        assertEquals(CLOCK.millis(), stored.receivedAt);
        assertTrue(stored.receivedAt > stored.occurredAt);
        assertEquals(List.of("outing_id", "novice-label", "headcount", "join-time", "revenue",
                "gps-false-rejections", "safety-tickets", "verification-latency"), stored.coverageGaps);
        assertEquals(RULES, jdbc.queryForObject(
                "select rules_version from city_fairness_event where event_id=?", String.class, result));
        assertEquals("EFFECTIVE_TASK", jdbc.queryForObject(
                "select kind from city_fairness_event where event_id=?", String.class, result));
        assertNull(jdbc.queryForObject("select newcomer from city_fairness_event where event_id=?", String.class, result));
        assertNull(jdbc.queryForObject("select team_size from city_fairness_event where event_id=?", Integer.class, result));
        assertNull(jdbc.queryForObject("select join_time from city_fairness_event where event_id=?", Long.class, result));
        assertNull(jdbc.queryForObject("select gain_amount from city_fairness_event where event_id=?", Long.class, result));
        assertNull(jdbc.queryForObject(
                "select verification_millis from city_fairness_event where event_id=?", Long.class, result));
        assertEquals(stored.receivedAt, jdbc.queryForObject(
                "select received_at from city_fairness_source_receipt where event_id=?", Long.class, result));
        jdbc.update("update city_onsite_task_result set created_at=timestamp '2026-10-21 12:00:01' where id=?", result);
        assertEquals("CONFLICT", bean.forward(MEMBER, result).status);
        assertEquals(stored.occurredAt, jdbc.queryForObject(
                "select occurred_at from city_fairness_event where event_id=?", Long.class, result));
        long asOf = stored.occurredAt + 6_000L;
        CityFairnessReportService.Page page = reports.outings(MEMBER, "live-outing", asOf, null,
                scope.board, scope.region, scope.season, RULES);
        assertEquals(CityFairnessReader.COMPUTABLE, page.status);
        assertEquals(FairnessFieldContract.FIRST_OUTING, page.outings.get(0).reason);
        assertTrue(page.outings.get(0).valid);
        assertFalse(page.outings.get(0).inWindow);
        assertEquals(outingId, page.outings.get(0).outingId);
        assertEquals(1, page.outings.size());
    }

    @Test
    void lateArrivalStaysOnTheOldSnapshotUntilANewQuery() {
        Scope scope = new Scope("board-late", "region-late", "season-late");
        approve(scope);
        policy(scope);
        long asOf = Instant.parse("2026-10-21T12:00:20Z").toEpochMilli();
        CityFairnessReportService.Page before = reports.outings(MEMBER, "old-query", asOf, null,
                scope.board, scope.region, scope.season, RULES);
        assertEquals(FairnessFieldContract.NO_MATURE_SAMPLE, before.maturity);
        assertTrue(before.outings.isEmpty());
        String first = pass(scope, "2026-10-21 12:00:00");
        String second = pass(scope, "2026-10-21 12:00:20");
        CityFairnessReportService.Page stale = reports.outings(MEMBER, "old-query", asOf, null,
                scope.board, scope.region, scope.season, RULES);
        assertTrue(stale.readBack);
        assertTrue(stale.outings.isEmpty());
        CityFairnessReportService.Page live = reports.outings(MEMBER, "new-query", asOf, null,
                scope.board, scope.region, scope.season, RULES);
        assertEquals(2, live.outings.size());
        assertEquals(FairnessFieldContract.FIRST_OUTING, live.outings.get(0).reason);
        assertEquals(FairnessFieldContract.AFTER_HORIZON, live.outings.get(1).reason);
        CityFairnessReportService.Page report = reports.read(MEMBER, "late-report", asOf, null,
                scope.board, scope.region, scope.season, RULES);
        assertEquals(1, report.lateEvents.size());
        assertEquals(second, report.lateEvents.get(0).eventId);
        long firstAt = jdbc.queryForObject("select occurred_at from city_fairness_event where event_id=?", Long.class, first);
        long secondAt = jdbc.queryForObject("select occurred_at from city_fairness_event where event_id=?", Long.class, second);
        assertEquals(0, firstAt % 1000);
        assertEquals(20_000L, secondAt - firstAt);
    }

    @Test
    void unverifiedZoneDoesNotIngest() {
        Scope scope = new Scope("board-zone", "region-zone", "season-zone");
        String result = seed(scope, "ACTIVE", "PASSED", "COMPLETE", 200, MEMBER, "2026-10-21 12:00:00");
        approve(scope);
        policy(scope);
        bean.bind(MEMBER, instanceOf(result));
        jdbc.update("update city_onsite_task_instance set status='PASSED' where id=?", instanceOf(result));
        TransactionTemplate tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        RelayResult unverified = tx.execute(status -> {
            jdbc.execute("set time zone 'Europe/Paris'");
            return bean.forward(MEMBER, result);
        });
        assertEquals("TIMEZONE_UNVERIFIED", unverified.status);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from city_fairness_event where event_id=?", Integer.class, result));
        assertEquals(null, CityFairnessOnsiteRelay.offset("SYSTEM"));
        assertEquals(ZoneOffset.UTC, CityFairnessOnsiteRelay.offset("UTC"));
        assertEquals(ZoneOffset.of("+08:00"), CityFairnessOnsiteRelay.offset("+08:00"));
    }

    @Test
    void oneInstanceBindsOnceUnderContention() throws Exception {
        String result = seed("ACTIVE", "PASSED", "COMPLETE", 200, MEMBER, "2026-10-21 12:00:00");
        String instance = instanceOf(result);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RelayResult> left = pool.submit(() -> { barrier.await(); return bean.bind(MEMBER, instance); });
            Future<RelayResult> right = pool.submit(() -> { barrier.await(); return bean.bind(MEMBER, instance); });
            assertEquals("BOUND", left.get().status);
            assertEquals("BOUND", right.get().status);
            assertEquals(left.get().outingId, right.get().outingId);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from city_fairness_outing where instance_id=?", Integer.class, instance));
        assertEquals(leftOuting(instance), bean.bind(MEMBER, instance).outingId);
    }

    private static String leftOuting(String instance) {
        return jdbc.queryForObject("select cast(id as varchar) from city_fairness_outing where instance_id=?",
                String.class, instance);
    }

    private static String pass(Scope scope, String createdAt) {
        String result = seed(scope, "ACTIVE", "PASSED", "COMPLETE", 200, MEMBER, createdAt);
        bean.bind(MEMBER, instanceOf(result));
        jdbc.update("update city_onsite_task_instance set status='PASSED' where id=?", instanceOf(result));
        assertEquals("STORED", bean.forward(MEMBER, result).status);
        return result;
    }

    private static String resultWithoutRequest() {
        int n = ++seq;
        String result = id(n);
        String instance = id(n + 1000);
        jdbc.update("insert into city_onsite_task_instance (id, team_id, task_id, status, accepted_by, evidence_id, accept_request_id)"
                        + " values (?,?,?,?,?,?,?)",
                instance, id(n + 2000), id(n + 3000), "PASSED", MEMBER, id(n + 4000), id(n + 5000));
        jdbc.update("insert into city_onsite_task_result (id, instance_id, request_id, outcome, member_id, created_at)"
                        + " values (?,?,?,?,?,?)",
                result, instance, id(n + 6000), "PASSED", MEMBER, "2026-10-21 12:00:00");
        return result;
    }

    private static String seed(String instanceStatus, String outcome, String state, int http, long member, String createdAt) {
        return seed(new Scope("board-" + seq, "region-" + seq, "season-" + seq), instanceStatus, outcome, state, http, member, createdAt);
    }

    private static String seed(Scope scope, String instanceStatus, String outcome, String state, int http,
                               long member, String createdAt) {
        int n = ++seq;
        String result = id(n);
        String instance = id(10_000 + n);
        String request = id(20_000 + n);
        jdbc.update("insert into city_onsite_task_instance (id, team_id, session_id, region_id, board_id, season_id,"
                        + " round_id, task_id, rules_release_id, release_id, content_hash, task_hash, status, version,"
                        + " attempts, max_attempts, accepted_by, accepted_at, point_id, evidence_id, evidence_policy_hash,"
                        + " merchant_id, place_id, evidence_expires_at, accept_request_id)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                instance, id(30_000 + n), id(40_000 + n), scope.region, scope.board, scope.season, "round-1", id(50_000 + n),
                RULES, 42L, HASH, HASH, instanceStatus, 1L, 1, 3, member, "2026-10-21 11:00:00", "point-1",
                id(60_000 + n), HASH, 9L, 8L, "2026-10-22 11:00:00", id(70_000 + n));
        jdbc.update("insert into city_onsite_task_request (request_id, member_id, request_hash, state, http_status,"
                        + " result_json, created_at) values (?,?,?,?,?,?,?)",
                request, member, HASH, state, http, "{\"kept\":\"unread\"}", "2026-10-21 11:59:00");
        jdbc.update("insert into city_onsite_task_result (id, instance_id, request_id, outcome, member_id,"
                        + " attempt_number, evidence_checked_at, created_at) values (?,?,?,?,?,?,?,?)",
                result, instance, request, outcome, member, 1, "2026-10-21 12:00:05", createdAt);
        return result;
    }

    private static String instanceOf(String resultId) {
        return jdbc.queryForObject("select instance_id from city_onsite_task_result where id=?", String.class, resultId);
    }

    private static Scope scopeOf(String resultId) {
        return jdbc.queryForObject("select i.board_id, i.region_id, i.season_id from city_onsite_task_result r"
                        + " join city_onsite_task_instance i on i.id=r.instance_id where r.id=?",
                (rs, n) -> new Scope(rs.getString(1), rs.getString(2), rs.getString(3)), resultId);
    }

    private static void approve(Scope scope) {
        jdbc.update("insert into city_fairness_parameter (id, state, source_ref, board_id, region_id, season_id,"
                        + " rules_version, metric_version, delta_millis, horizon_millis, grace_millis, merge_window_millis,"
                        + " state_rule, max_events, page_size) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1_000 + seq, "APPROVED", "parameter-source", scope.board, scope.region, scope.season, RULES, "metric-1",
                1_000L, 5_000L, 1_000L, 60_000L, FairnessFieldContract.STATE_RULE, 50, 10);
    }

    private static void policy(Scope scope) {
        jdbc.update("insert into city_fairness_source_policy (id, state, source_ref, board_id, region_id, season_id,"
                        + " rules_version, counts_as_effective_outing) values (?,?,?,?,?,?,?,?)",
                2_000 + seq, "APPROVED", CityFairnessOnsiteRelay.SOURCE, scope.board, scope.region, scope.season, RULES, 1);
    }

    private static String id(int n) {
        return String.format("00000000-0000-0000-0000-%012d", n);
    }

    private static final class Scope {
        final String board;
        final String region;
        final String season;

        Scope(String board, String region, String season) {
            this.board = board;
            this.region = region;
            this.season = season;
        }
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan({
            "com.chengyinhub.business.citygame.analytics.fairness.mapper",
            "com.chengyinhub.business.citygame.analytics.fairness.source.mapper"
    })
    @ComponentScan(basePackageClasses = {CityFairnessIngestionService.class, CityFairnessOnsiteRelay.class,
            CityFairnessReportService.class}, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {CityFairnessIngestionService.class, CityFairnessOnsiteRelay.class,
                            CityFairnessReportService.class}))
    static class Wiring {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:onsite_fairness_" + System.nanoTime()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            JdbcTemplate setup = new JdbcTemplate(dataSource);
            setup.execute("create table city_onsite_task_instance ("
                    + "id varchar(36) primary key, team_id varchar(36), session_id varchar(36),"
                    + "region_id varchar(96), board_id varchar(96), season_id varchar(96), round_id varchar(96),"
                    + "task_id varchar(96), rules_release_id varchar(96), release_id bigint, content_hash char(64),"
                    + "task_hash char(64), status varchar(16), version bigint, attempts int, max_attempts int,"
                    + "accepted_by bigint, accepted_at timestamp, point_id varchar(96), evidence_id varchar(36) unique,"
                    + "evidence_policy_hash char(64), merchant_id bigint, place_id bigint, evidence_expires_at timestamp,"
                    + "accept_request_id varchar(96) unique, unique (team_id, accepted_by, task_id))");
            setup.execute("create table city_onsite_task_request ("
                    + "request_id varchar(96) primary key, member_id bigint, request_hash char(64), state varchar(16),"
                    + "http_status int, result_json longtext, created_at timestamp)");
            setup.execute("create table city_onsite_task_result ("
                    + "id varchar(36) primary key, instance_id varchar(36) unique, request_id varchar(96) unique,"
                    + "outcome varchar(16), member_id bigint, attempt_number int, evidence_checked_at timestamp,"
                    + "created_at timestamp)");
            setup.execute("create table city_fairness_outing ("
                    + "id bigint generated by default as identity primary key, member_id bigint not null,"
                    + "instance_id varchar(36) not null unique, board_id varchar(96) not null, region_id varchar(96) not null,"
                    + "season_id varchar(96) not null, rules_release_id varchar(96) not null, release_id bigint not null,"
                    + "content_hash char(64) not null)");
            setup.execute("create table city_fairness_source_policy ("
                    + "id bigint primary key, state varchar(16) not null, source_ref varchar(128) not null,"
                    + "board_id varchar(96) not null, region_id varchar(96) not null, season_id varchar(96) not null,"
                    + "rules_version varchar(96) not null, counts_as_effective_outing tinyint not null)");
            setup.execute("create table city_fairness_source_receipt ("
                    + "source_ref varchar(128) not null, event_id varchar(96) not null, received_at bigint not null,"
                    + "primary key (source_ref, event_id))");
            setup.execute("create table city_fairness_parameter ("
                    + "id bigint primary key, state varchar(16) not null, source_ref varchar(128) not null,"
                    + "board_id varchar(64) not null, region_id varchar(64) not null, season_id varchar(64) not null,"
                    + "rules_version varchar(64) not null, metric_version varchar(64), delta_millis bigint,"
                    + "horizon_millis bigint, grace_millis bigint, merge_window_millis bigint, state_rule varchar(64),"
                    + "max_events int, page_size int)");
            setup.execute("create table city_fairness_event ("
                    + "source_ref varchar(128) not null, event_id varchar(96) not null, outing_id varchar(96),"
                    + "kind varchar(64) not null, actor_id bigint not null, occurred_at bigint not null, known_at bigint not null,"
                    + "subject_source varchar(128), subject_event_id varchar(96), join_time bigint, team_size int,"
                    + "action_cost int, newcomer varchar(32), gain_amount bigint, verification_millis bigint,"
                    + "board_id varchar(64) not null, region_id varchar(64) not null, season_id varchar(64) not null,"
                    + "rules_version varchar(64) not null, primary key (source_ref, event_id))");
            setup.execute("create table city_fairness_read ("
                    + "account_id bigint not null, query_id varchar(64) not null, board_id varchar(64) not null,"
                    + "region_id varchar(64) not null, season_id varchar(64) not null, rules_version varchar(64) not null,"
                    + "as_of bigint not null, payload clob not null,"
                    + "primary key (account_id, query_id, board_id, region_id, season_id, rules_version))");
            return dataSource;
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTransactionFactory(new SpringManagedTransactionFactory());
            factory.setMapperLocations(
                    new ClassPathResource("mapper/business/CityFairnessMapper.xml"),
                    new ClassPathResource("mapper/business/FairnessOnsiteSourceMapper.xml"),
                    new ClassPathResource("mapper/business/FairnessOutingMapper.xml"));
            return factory.getObject();
        }
    }
}
