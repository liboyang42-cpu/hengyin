package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayHelpRunQuota;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayHelpRunQuotaMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.support.PlayHelpCostApplication;
import com.chengyinhub.business.service.support.PlayHelpRunQuotaApplication;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * G7 真库并发合同：同一 scope 的额度行锁、最后一份额度只成功一次、事务回滚不消耗额度、
 * 同 requestId 异节点冲突、旧局 total 冻结。必须由测试 VPS/CI 的真实 MySQL 8 执行；
 * 本工作位未跑（AGENTS 禁止本机 Java/Maven/DB）。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MYBATIS_IT_HOST", matches = ".+")
class PlayHelpRunQuotaConcurrencyMySqlIT {
    private static final long MEMBER = 9001L;
    private static final long TOPIC = 55L;
    private static final long ACTIVITY = 66L;
    private static final String INSTANCE = "it-run-1";
    private static final long NODE_A = 701L;
    private static final long NODE_B = 702L;

    private static PooledDataSource ds;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static PlayHelpRunQuotaApplication application;
    private static String createdDatabase;

    @BeforeAll
    static void schema() throws Exception {
        createdDatabase = MybatisItDatabase.databaseNameAfterCreate("play_help_run_quota");
        String url = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/" + createdDatabase
                + "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE TABLE play_attempt_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + "member_id BIGINT,topic_id BIGINT,node_id BIGINT,activity_id BIGINT,"
                + "attempt_type TINYINT,result TINYINT,wrong_answer VARCHAR(120),duration_s INT,"
                + "result_json JSON,idempotency_key VARCHAR(128),"
                + "request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                + "created_at DATETIME,"
                + "UNIQUE KEY uk_play_attempt_idempotency(idempotency_key)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE topic_route_session(id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + "activity_id BIGINT NOT NULL DEFAULT 0,topic_id BIGINT NOT NULL,member_id BIGINT NOT NULL,"
                + "play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',config_version BIGINT NOT NULL,"
                + "config_snapshot_json JSON NOT NULL,state_json JSON NOT NULL,status TINYINT NOT NULL DEFAULT 0,"
                + "current_node_id BIGINT DEFAULT NULL,recommended_node_id BIGINT DEFAULT NULL,"
                + "seed BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,completed_at DATETIME(3) DEFAULT NULL,"
                + "create_time DATETIME(3) NOT NULL,update_time DATETIME(3) NOT NULL,"
                + "UNIQUE KEY uk_topic_route_owner(activity_id,topic_id,member_id,play_instance_id)) ENGINE=InnoDB");
        // 积分扣减的同事务替身：只用来证明额度消费与收费行在同一事务里一起提交/回滚。
        jdbc.execute("CREATE TABLE it_points_charge(id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + "member_id BIGINT NOT NULL, charged INT NOT NULL) ENGINE=InnoDB");

        try (Connection connection = ds.getConnection()) {
            ItSchema.applyMigrations(connection, "sql/migration_20261108_play_help_run_quota.sql");
        }

        Configuration config = new Configuration(new Environment(
                "play-help-run-quota", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAlias("PlayAttemptLog", PlayAttemptLog.class);
        config.getTypeAliasRegistry().registerAlias("PlayHelpRunQuota", PlayHelpRunQuota.class);
        config.getTypeAliasRegistry().registerAlias("TopicRouteSession", TopicRouteSession.class);
        parse(config, "mapper/business/PlayAttemptLogMapper.xml");
        parse(config, "mapper/business/PlayHelpRunQuotaMapper.xml");
        parse(config, "mapper/business/TopicRouteSessionMapper.xml");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        PlayAttemptLogMapper attempts = session.getMapper(PlayAttemptLogMapper.class);
        PlayHelpRunQuotaMapper quotas = session.getMapper(PlayHelpRunQuotaMapper.class);
        TopicRouteSessionMapper sessions = session.getMapper(TopicRouteSessionMapper.class);
        // 并发用例只覆盖路线局：高级局归属另由 PlayHelpRunQuotaApplicationTest 覆盖，
        // 这里给一个未被调用的 game mapper 桩，保持构造兼容。
        application = new PlayHelpRunQuotaApplication(mock(PlayHelpCostApplication.class),
                attempts, quotas, sessions,
                mock(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class), new ObjectMapper());
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private static void parse(Configuration config, String resource) throws Exception {
        try (InputStream input = PlayHelpRunQuotaConcurrencyMySqlIT.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (ds != null) ds.forceCloseAll();
        if (createdDatabase == null) return;
        assertEquals(MybatisItDatabase.databaseName("play_help_run_quota"), createdDatabase,
                "只允许删除本 IT 专属库");
        JdbcTemplate admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                        + "/?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        P0AcceptanceSchema.drop(admin, "play_help_run_quota");
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM play_attempt_log");
        jdbc.update("DELETE FROM play_help_run_quota");
        jdbc.update("DELETE FROM it_points_charge");
        jdbc.update("DELETE FROM topic_route_session");
        jdbc.update("INSERT INTO topic_route_session(activity_id,topic_id,member_id,play_instance_id,"
                + "config_version,config_snapshot_json,state_json,status,seed,version,create_time,update_time) "
                + "VALUES(?,?,?,?,1,JSON_OBJECT(),JSON_OBJECT(),0,1,0,NOW(3),NOW(3))",
                ACTIVITY, TOPIC, MEMBER, INSTANCE);
    }

    @Test
    void concurrentLastQuotaHasOneWinnerAndOneRolledBackRequest() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Future<Boolean> a = workers.submit(() -> consumeWhenReleased(ready, NODE_A, "req-a"));
            Future<Boolean> b = workers.submit(() -> consumeWhenReleased(ready, NODE_B, "req-b"));
            ready.countDown();
            boolean one = a.get(15, TimeUnit.SECONDS);
            boolean two = b.get(15, TimeUnit.SECONDS);
            assertTrue(one ^ two, "N=1 时跨两节点只能有一个请求拿到免费额度");
        } finally {
            workers.shutdownNow();
        }
        assertEquals(1, quotaUsed(), "quota_used 必须恰好 1");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_attempt_log WHERE idempotency_key LIKE 'help1qreq:%'", Integer.class),
                "败者的请求认领必须随事务回滚");
    }

    private boolean consumeWhenReleased(CountDownLatch ready, long nodeId, String requestId) throws Exception {
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        try {
            return tx.execute(status -> application.consumeFreeUse(MEMBER, scope(nodeId), 1, 1, 30, requestId));
        } catch (PlayHelpRunQuotaApplication.PlayHelpRunQuotaException exhausted) {
            assertEquals(PlayHelpRunQuotaApplication.REASON_EXHAUSTED, exhausted.code());
            return false;
        }
    }

    @Test
    void rollbackAfterQuotaConsumeLeavesNoRowNoClaimAndNoCharge() {
        assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            // 与生产同序：额度消费和积分扣减在同一个事务里，后面的动作失败必须一起回滚。
            application.consumeFreeUse(MEMBER, scope(NODE_A), 1, 1, 30, "req-boom");
            jdbc.update("INSERT INTO it_points_charge(member_id, charged) VALUES(?,?)", MEMBER, 30);
            throw new RuntimeException("later write failed");
        }));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM play_help_run_quota", Integer.class),
                "事务回滚不得留下已消耗的额度行");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM play_attempt_log", Integer.class),
                "事务回滚不得留下请求认领");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM it_points_charge", Integer.class),
                "同事务的扣款行必须一起回滚");
    }

    @Test
    void committedQuotaAndChargeCoexistInOneTransaction() {
        tx.execute(status -> {
            application.consumeFreeUse(MEMBER, scope(NODE_A), 1, 1, 30, "req-ok");
            jdbc.update("INSERT INTO it_points_charge(member_id, charged) VALUES(?,?)", MEMBER, 0);
            return null;
        });

        assertEquals(1, quotaUsed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM it_points_charge", Integer.class));
    }

    @Test
    void frozenTotalIsNotRaisedByNewConfigAndNewConfigIsReportedAsConflict() {
        tx.execute(status -> application.consumeFreeUse(MEMBER, scope(NODE_A), 1, 2, 30, "req-1"));

        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException conflict = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> tx.execute(status -> application.consumeFreeUse(MEMBER, scope(NODE_B), 2, 5, 30, "req-2")));
        assertEquals(PlayHelpRunQuotaApplication.REASON_CONFIG_CONFLICT, conflict.code());

        PlayHelpRunQuota row = jdbc.queryForObject(
                "SELECT quota_total,quota_used,owner_member_id FROM play_help_run_quota", (rs, i) -> {
                    PlayHelpRunQuota q = new PlayHelpRunQuota();
                    q.setQuotaTotal(rs.getInt("quota_total"));
                    q.setQuotaUsed(rs.getInt("quota_used"));
                    q.setOwnerMemberId(rs.getLong("owner_member_id"));
                    return q;
                });
        assertEquals(2, row.getQuotaTotal(), "旧局 total 必须保持首次冻结值，不被后续 N=5 覆盖");
        assertEquals(1, row.getQuotaUsed());
        assertEquals(MEMBER, row.getOwnerMemberId());

        // 同一冻结 N 的第二层仍可继续消耗，第 2 次后额度用尽。
        tx.execute(status -> application.consumeFreeUse(MEMBER, scope(NODE_B), 2, 2, 30, "req-2b"));
        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException exhausted = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> tx.execute(status -> application.consumeFreeUse(MEMBER, scope(NODE_A), 3, 2, 30, "req-3")));
        assertEquals(PlayHelpRunQuotaApplication.REASON_EXHAUSTED, exhausted.code());
        assertEquals(2, quotaUsed());
    }

    @Test
    void sameRequestIdOnDifferentNodeConflictsWithoutSecondQuota() {
        tx.execute(status -> application.consumeFreeUse(MEMBER, scope(NODE_A), 1, 2, 30, "req-same"));

        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException conflict = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> tx.execute(status -> application.consumeFreeUse(MEMBER, scope(NODE_B), 2, 2, 30, "req-same")));
        assertEquals(PlayHelpRunQuotaApplication.REASON_REQUEST_CONFLICT, conflict.code());
        assertEquals(1, quotaUsed());
    }

    @Test
    void anotherMembersRunCannotStealTheQuota() {
        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException denied = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> tx.execute(status -> application.consumeFreeUse(MEMBER + 1, scope(NODE_A), 1, 2, 30, "req-x")));
        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_FORBIDDEN, denied.code());
        assertEquals(0, quotaUsed());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM play_attempt_log", Integer.class));
    }

    @Test
    void migrationCreatesTheScopeUniqueKey() {
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                        + "AND table_name='play_help_run_quota' AND index_name='uk_help_run_quota_scope' "
                        + "AND non_unique=0", Integer.class));
    }

    /**
     * 同一 playInstance 但两个会员：额度行按 owner 分开，绝不共享/偷池。
     * 第二会员本来也过不了 route session 归属；这里直接证明 key 不因默认实例撞行。
     */
    @Test
    void samePlayInstanceForTwoMembersUsesSeparateQuotaRows() {
        jdbc.update("INSERT INTO topic_route_session(activity_id,topic_id,member_id,play_instance_id,"
                + "config_version,config_snapshot_json,state_json,status,seed,version,create_time,update_time) "
                + "VALUES(?,?,?,?,1,JSON_OBJECT(),JSON_OBJECT(),0,1,0,NOW(3),NOW(3))",
                ACTIVITY, TOPIC, MEMBER + 1, INSTANCE);

        tx.execute(status -> application.consumeFreeUse(MEMBER, scope(NODE_A), 1, 1, 30, "req-a"));
        tx.execute(status -> application.consumeFreeUse(MEMBER + 1, scope(NODE_A), 1, 1, 30, "req-b"));

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_help_run_quota", Integer.class),
                "两个会员必须各自一行额度，不能共用同一池");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_help_run_quota WHERE quota_used=1 AND quota_total=1", Integer.class));
    }

    private static int quotaUsed() {
        Integer used = jdbc.queryForObject("SELECT COALESCE(MAX(quota_used),0) FROM play_help_run_quota", Integer.class);
        return used == null ? 0 : used;
    }

    private static PlayHelpUsagePolicy.Scope scope(long nodeId) {
        return new PlayHelpUsagePolicy.Scope(TOPIC, ACTIVITY, nodeId, INSTANCE);
    }
}
