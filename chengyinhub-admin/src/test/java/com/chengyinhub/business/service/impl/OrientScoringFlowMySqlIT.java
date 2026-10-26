package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientScoreAppeal;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.mapper.OrientScoringMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.OrientScoringException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicitly run this IT on approved MySQL. No assumptions/skips or H2 fallback.
 * Actual scoring service, Spring transaction proxy, production XML and ledger DDL.
 * Only config/topic reads are stubbed; default zero reward policy is preserved.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrientScoringFlowMySqlIT {
    private JdbcTemplate db, admin;
    private String database;
    private boolean created;
    private IOrientScoringService service;
    private CmsTopic topic;

    @BeforeAll
    void setup() throws Exception {
        for (String key : List.of("HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD")) {
            assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_" + key), "explicit isolated MySQL config required: " + key);
        }
        assertTrue(Set.of("127.0.0.1", "localhost").contains(System.getenv("CHENGYIN_TEST_MYSQL_HOST")));
        assertFalse(System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX").isBlank());
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("oscore");
        database = config.databaseName() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        assertTrue(database.length() <= 64);
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, database));
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        created = true;
        String url = config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?");
        DriverManagerDataSource source = new DriverManagerDataSource(url, config.user(), config.password());
        db = new JdbcTemplate(source);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("chengyinhub-admin/sql/migration_orient_scoring_20261022.sql"))) root = root.getParent();
        assertNotNull(root);
        // Use all four exact scoring CREATE statements, without unrelated config seeding.
        for (String migration : List.of("migration_orient_scoring_20261022.sql", "migration_orient_score_sync_receipt_20261026.sql", "migration_orient_score_recovery_20261026.sql")) {
            String sql = Files.readString(root.resolve("chengyinhub-admin/sql/" + migration));
            if (migration.contains("20261022")) sql = sql.substring(0, sql.indexOf("INSERT INTO sys_config"));
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        }
        String lifecycle = Files.readString(root.resolve("chengyinhub-admin/sql/migration_orient_session_lifecycle_20261022.sql"));
        lifecycle = lifecycle.substring(lifecycle.indexOf("CREATE TABLE IF NOT EXISTS orient_session_clock"));
        lifecycle = lifecycle.substring(0, lifecycle.indexOf("-- runner:"));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(lifecycle.getBytes(StandardCharsets.UTF_8)));
        }
        // Minimal external task/session read fixtures; production scoring SQL is used unchanged.
        db.execute("CREATE TABLE topic_route_session(id BIGINT PRIMARY KEY,activity_id BIGINT,topic_id BIGINT,member_id BIGINT,"
                + "play_instance_id VARCHAR(64),status INT,completed_at DATETIME,create_time DATETIME,update_time DATETIME) ENGINE=InnoDB");
        db.execute("CREATE TABLE player_node_progress(id BIGINT PRIMARY KEY,member_id BIGINT,activity_id BIGINT,topic_id BIGINT,"
                + "node_id BIGINT,status INT,review_status INT,puzzle_score INT,completion_mode VARCHAR(16),checkin_time DATETIME,"
                + "del_flag INT,play_instance_id VARCHAR(64)) ENGINE=InnoDB");
        db.execute("CREATE TABLE orient_task_attempt(id BIGINT PRIMARY KEY,member_id BIGINT,activity_id BIGINT,topic_id BIGINT,"
                + "play_instance_id VARCHAR(64),task_id BIGINT,status INT,alternative_task_type VARCHAR(16)) ENGINE=InnoDB");
        db.execute("CREATE TABLE orient_task_completion(id BIGINT PRIMARY KEY,member_id BIGINT,activity_id BIGINT,topic_id BIGINT,"
                + "play_instance_id VARCHAR(64),task_id BIGINT) ENGINE=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/OrientScoringMapper.xml"));
        OrientScoringMapper mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(OrientScoringMapper.class);
        OrientScoringServiceImpl target = new OrientScoringServiceImpl();
        ReflectionTestUtils.setField(target, "mapper", mapper);
        var registrations = mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(List.of(new com.chengyinhub.business.domain.CmsRegistration()));
        ReflectionTestUtils.setField(target, "scoringRegistrations", registrations);
        var activities = mock(com.chengyinhub.business.service.ICmsActivityService.class);
        var activity = new com.chengyinhub.business.domain.CmsActivity(); activity.setTopicId(300L);
        when(activities.selectCmsActivityById(500L)).thenReturn(activity);
        ReflectionTestUtils.setField(target, "scoringActivities", activities);
        ReflectionTestUtils.setField(target, "scoringGameSessions", mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class));
        ISysConfigService settings = mock(ISysConfigService.class);
        when(settings.selectConfigByKey("orient.scoring.enabled")).thenReturn("true");
        ReflectionTestUtils.setField(target, "configService", settings);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        topic = new CmsTopic(); topic.setId(300L); topic.setProductType(1);
        when(topics.selectCmsTopicById(300L)).thenReturn(topic);
        ReflectionTestUtils.setField(target, "cmsTopicService", topics);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        service = (IOrientScoringService) proxy.getProxy();
    }

    @BeforeEach
    void fixture() {
        topic.setPublishedRouteGraphJson(null);
        for (String table : List.of("orient_task_attempt", "orient_task_completion", "orient_score_recovery", "orient_session_clock", "orient_score_sync_receipt", "orient_score_appeal", "orient_result_dispute",
                "orient_score_settlement", "orient_score_ledger", "player_node_progress", "topic_route_session")) db.update("DELETE FROM " + table);
        db.update("INSERT INTO topic_route_session VALUES(10,500,300,7,'default',1,NOW(),NOW(),NOW())");
        db.update("INSERT INTO player_node_progress VALUES(1,7,500,300,20,1,1,80,'SOLVED',NOW(),0,'default')");
    }

    @AfterAll
    void cleanup() {
        if (created) admin.execute("DROP DATABASE `" + database + "`");
    }

    @Test
    void taskLedgerSettlementAppealAndIndependentPlayerReadback() {
        Map<String, Object> sync = service.syncSession(7L, 10L, "sync-1");
        assertEquals(2, sync.get("added")); assertEquals(18000L, sync.get("totalCenti"));
        assertEquals(sync, service.receipt(7L, "SYNC", 10L, "sync-1"));
        Map<String, Object> settled = service.settle(7L, 10L, "settle-1");
        assertEquals(18000L, settled.get("totalCenti")); assertEquals(0, settled.get("pointsAwarded"));
        assertEquals(settled.get("settlementId"), service.settle(7L, 10L, "another-key").get("settlementId"));
        Long entry = db.queryForObject("SELECT id FROM orient_score_ledger WHERE dimension='PUZZLE'", Long.class);
        OrientScoreAppeal appeal = service.submitAppeal(7L, 10L, entry, null, "核对谜题", "appeal-1");
        assertEquals(0, appeal.getStatus());
        OrientScoreAppeal accepted = service.resolveAppeal(appeal.getId(), true, null, "已核对", "reviewer", 9L);
        assertEquals(1, accepted.getStatus()); assertNotNull(accepted.getResolutionEntryId());
        assertThrows(OrientScoringException.class, () -> service.resolveAppeal(appeal.getId(), true, null, "重复", "reviewer", 9L));
        Map<String, Object> readback = service.breakdown(7L, 10L);
        assertEquals(10000L, readback.get("totalCenti"));
        Map<?, ?> ownRank = (Map<?, ?>) service.leaderboard(7L, 10L).get("me");
        assertEquals("10000", ownRank.get("scoreCenti").toString());
        assertEquals("1", ownRank.get("rank"));
        assertEquals("ACCEPTED", service.receipt(7L, "APPEAL", 10L, "appeal-1").get("state"));
        assertEquals(true, service.verify(10L).get("consistent"));
        assertEquals(-8000L, service.verify(10L).get("postSettlementDeltaCenti"));
        assertEquals(8000, db.queryForObject("SELECT delta_centi FROM orient_score_ledger WHERE id=?", Integer.class, entry));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger WHERE reverses_entry_id=?", Integer.class, entry));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM orient_score_settlement", Integer.class));
    }

    @Test
    void alternativeSharesPrimaryTaskKeyAndPendingPhotoCannotSettle() {
        db.update("INSERT INTO orient_task_completion VALUES(1,7,500,300,'default',20),(2,7,500,300,'default',21),(3,7,500,300,'other-run',22)");
        service.syncSession(7L,10L,"alternatives");
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger WHERE dimension='TASK'",Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger WHERE idempotency_key='task:node:20'",Integer.class));
        db.update("INSERT INTO orient_task_attempt VALUES(1,7,500,300,'default',23,0,'PHOTO')");
        assertEquals(OrientScoringException.REVIEW_PENDING,
                assertThrows(OrientScoringException.class,()->service.settle(7L,10L,"pending-alternative")).getErrorCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM orient_score_settlement",Integer.class));
    }

    @Test
    void pendingAndUnauthorizedRequestsHaveZeroWrites() {
        db.update("UPDATE player_node_progress SET review_status=0");
        assertEquals(OrientScoringException.REVIEW_PENDING,
                assertThrows(OrientScoringException.class, () -> service.settle(7L, 10L, "pending")).getErrorCode());
        assertThrows(OrientScoringException.class, () -> service.syncSession(8L, 10L, "foreign"));
        assertThrows(OrientScoringException.class, () -> service.receipt(8L, "SYNC", 10L, "foreign"));
        for (String table : List.of("orient_score_ledger", "orient_score_settlement", "orient_score_sync_receipt"))
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }

    @Test
    void overflowRollsBackAccruedTaskAndDoesNotCreateSuccessReceipt() {
        db.update("UPDATE player_node_progress SET puzzle_score=21474837");
        assertThrows(OrientScoringException.class, () -> service.syncSession(7L, 10L, "overflow"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger", Integer.class));
        assertEquals("NOT_FOUND", service.receipt(7L, "SYNC", 10L, "overflow").get("state"));
    }

    @Test
    void concurrentSameRequestReplaysOneSnapshotAndWatermarkRemainsConsistent() throws Exception {
        List<Map<String, Object>> results = race(() -> service.syncSession(7L, 10L, "same"));
        assertEquals(results.get(0), results.get(1));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM orient_score_sync_receipt", Integer.class));
        race(() -> service.settle(7L, 10L, "settlement"));
        Long entry = db.queryForObject("SELECT MIN(id) FROM orient_score_ledger", Long.class);
        race(() -> service.reverseEntry(entry, "REVIEW", 9L, "reverse"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger WHERE reverses_entry_id=?", Integer.class, entry));
        assertEquals(true, service.verify(10L).get("consistent"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM orient_score_settlement", Integer.class));
    }

    @Test
    void signedCentiAndMaximumRemainReversibleWithoutMutatingOriginals() {
        for (int delta : new int[]{0, -1, 1, Integer.MAX_VALUE, -Integer.MAX_VALUE}) {
            OrientScoreEntry entry = service.appendEntry(10L, "BONUS", delta, "TEST", "ADMIN", "test", null, "value:" + delta);
            service.reverseEntry(entry.getId(), "REVIEW", 9L, "reverse:" + delta);
        }
        assertThrows(OrientScoringException.class, () -> service.appendEntry(10L, "BONUS", Integer.MIN_VALUE, "TEST", "ADMIN", "test", null, "min"));
        assertEquals(0L, service.breakdown(7L, 10L).get("totalCenti"));
        assertEquals(10, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger", Integer.class));
    }

    @Test
    void failedReceiptInsertRollsBackLedgerInSameTransaction() {
        db.execute("CREATE TRIGGER reject_sync BEFORE INSERT ON orient_score_sync_receipt FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='receipt failure'");
        try {
            assertThrows(RuntimeException.class, () -> service.syncSession(7L, 10L, "receipt-fails"));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger", Integer.class));
            assertEquals("NOT_FOUND", service.receipt(7L, "SYNC", 10L, "receipt-fails").get("state"));
        } finally { db.execute("DROP TRIGGER reject_sync"); }
    }

    @Test
    void appendRacingSettlementKeepsWatermarkAndProjectionAligned() throws Exception {
        service.syncSession(7L, 10L, "initial");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> append = pool.submit(() -> { start.await(); return service.appendEntry(10L, "BONUS", 7, "TEST", "ADMIN", "race", null, "bonus"); });
            Future<Map<String, Object>> settle = pool.submit(() -> { start.await(); return service.settle(7L, 10L, "race"); });
            start.countDown(); append.get(20, TimeUnit.SECONDS);
            long projected = (Long) settle.get(20, TimeUnit.SECONDS).get("totalCenti");
            assertTrue(projected == 18000L || projected == 18007L);
            assertEquals(18007L, service.breakdown(7L, 10L).get("totalCenti"));
            assertEquals(true, service.verify(10L).get("consistent"));
            assertEquals(18007L - projected, service.verify(10L).get("postSettlementDeltaCenti"));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rankingCoversEntireScopeBeforeLimitAndUsesFrozenTimeForTies() {
        for (int i = 1; i <= 55; i++) {
            long sid = 100 + i, member = 1000 + i;
            db.update("INSERT INTO topic_route_session VALUES(?,500,300,?,'default',1,NOW(),NOW(),NOW())", sid, member);
            db.update("INSERT INTO orient_session_clock VALUES(?,?,NOW(),NOW(),1000,NOW(),NOW())", sid, member);
            service.appendEntry(sid, "BONUS", i >= 54 ? 90000 : 100 + i, "TEST", "ADMIN", "rank", null, "rank");
        }
        Map<String, Object> board = service.leaderboard(7L, 10L);
        List<Map<String, Object>> list = (List<Map<String, Object>>) board.get("list");
        assertEquals(50, list.size());
        assertEquals("1", list.get(0).get("rank")); assertEquals("1", list.get(1).get("rank"));
        assertEquals("3", list.get(2).get("rank"));
        assertEquals("90000", list.get(0).get("scoreCenti").toString());
        assertEquals("1000", list.get(0).get("elapsedMs"));
        assertEquals("56", ((Map<String, Object>) board.get("me")).get("rank"));
        assertFalse(list.get(0).containsKey("memberId")); assertFalse(list.get(0).containsKey("sessionId"));
        // Newer replay replaces that member's old high-scoring session; never sum across runs.
        db.update("INSERT INTO topic_route_session VALUES(999,500,300,1055,'replay',0,NULL,NOW(),NOW())");
        board = service.leaderboard(7L, 10L);
        list = (List<Map<String, Object>>) board.get("list");
        assertEquals("2", list.get(1).get("rank"));
        assertThrows(OrientScoringException.class, () -> service.leaderboard(8L, 10L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void branchBoardUsesEndingThenTimeAndLegacyFallbackWithoutScoreTieBreak() {
        topic.setPublishedRouteGraphJson("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\"}");
        db.update("UPDATE topic_route_session SET create_time='2026-10-26 10:00:00',completed_at='2026-10-26 10:00:01' WHERE id=10");
        db.update("INSERT INTO topic_route_session VALUES(11,500,300,8,'default',1,NOW(),NOW(),NOW()),(12,500,300,9,'default',2,NOW(),NOW(),NOW())");
        db.update("INSERT INTO orient_session_clock VALUES(11,8,NOW(),NOW(),1000,NOW(),NOW()),(12,9,NOW(),NOW(),1,NOW(),NOW())");
        service.appendEntry(11L, "BONUS", 999, "TEST", "ADMIN", "rank", null, "rank");
        service.appendEntry(12L, "BONUS", 999999, "TEST", "ADMIN", "rank", null, "rank");
        Map<String, Object> board = service.leaderboard(7L, 10L);
        assertEquals("ROUTE", board.get("mode"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) board.get("list");
        assertEquals(List.of("1", "1", "3"), list.stream().map(x -> x.get("rank")).toList());
        assertEquals(true, list.get(0).get("reachedEnding")); assertEquals(false, list.get(2).get("reachedEnding"));
    }

    @Test
    void legacyInvestigationIsIdempotentAndReviewedWithoutReplayingOrUnlocking() throws Exception {
        var tickets = race(() -> service.submitRecovery(7L, 10L, "APPEAL"));
        assertEquals(tickets.get(0).getId(), tickets.get(1).getId());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM orient_score_recovery", Integer.class));
        assertThrows(OrientScoringException.class, () -> service.submitRecovery(8L, 10L, "SYNC"));
        OrientScoreAppeal actual = service.submitAppeal(7L, 10L, null, 20L, "漏记核对", "appeal");
        db.update("INSERT INTO topic_route_session VALUES(20,500,300,8,'default',1,NOW(),NOW(),NOW())");
        OrientScoreAppeal other = service.submitAppeal(8L, 20L, null, 20L, "另一局", "other");
        Long id = tickets.get(0).getId();
        assertThrows(OrientScoringException.class, () -> service.reviewRecovery(id, other.getId(), "不可跨局关联", "reviewer"));
        assertThrows(OrientScoringException.class, () -> service.reviewRecovery(id, actual.getId(), "", "reviewer"));
        var reviewed = service.reviewRecovery(id, actual.getId(), "已关联本局申请；原请求身份仍未知", "reviewer");
        assertEquals(1, reviewed.getStatus()); assertEquals(actual.getId(), reviewed.getReferenceId());
        assertThrows(OrientScoringException.class, () -> service.reviewRecovery(id, actual.getId(), "不能覆盖", "reviewer"));
        assertEquals(1, ((java.util.List<?>) service.breakdown(7L, 10L).get("recoveries")).size());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM orient_score_ledger", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM orient_score_settlement", Integer.class));
    }

    private <T> List<T> race(Callable<T> operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<T> task = () -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return operation.call(); };
            Future<T> first = pool.submit(task), second = pool.submit(task);
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
