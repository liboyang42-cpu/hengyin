package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.PlayerWithdrawalCooldown;
import com.chengyinhub.business.mapper.PlayerWithdrawalCooldownMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.PlayerWithdrawalCooldownServiceImpl;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.domain.SysConfig;
import com.chengyinhub.system.mapper.SysConfigMapper;
import com.chengyinhub.system.service.impl.SysConfigServiceImpl;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SOURCE ONLY / NOT_RUN. Real MySQL and Spring transactions; Redis alone is mocked. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(40)
class OWN_C05_25MySqlIT {
    private static final String KEY = "withdrawal.player-complaint-cooldown-hours";
    private static final String CACHE = CacheConstants.SYS_CONFIG_KEY + KEY;
    private MysqlIntegrationTestConfig mysql;
    private boolean created;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RedisCache redis;
    private PlayerWithdrawalCooldownServiceImpl cooldown;

    @BeforeAll
    void createUniqueScratchSchema() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        // The helper has developer defaults; an explicitly enabled destructive IT must not use them.
        for (String name : Arrays.asList("HOST", "PORT", "DATABASE_BASE", "USER", "PASSWORD")) {
            String key = "CHENGYIN_TEST_MYSQL_" + name;
            assertNotNull(env.get(key), "Enabled IT requires explicit " + key);
            if (!"PASSWORD".equals(name)) assertFalse(env.get(key).trim().isEmpty(), key);
        }
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        mysql = MysqlIntegrationTestConfig.from(env, "c0525");
        try (Connection c = admin(); Statement s = c.createStatement()) {
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(c.getMetaData().getDatabaseProductVersion()),
                    "MySQL >=8 required; MariaDB is not supported");
            s.execute("CREATE DATABASE `" + mysql.databaseName() + "` CHARACTER SET utf8mb4");
            created = true;
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(mysql.databaseUrl()); ds.setUsername(mysql.user()); ds.setPassword(mysql.password());
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        Configuration config = new Configuration(new Environment("c0525", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAlias("SysConfig", SysConfig.class);
        config.getTypeAliasRegistry().registerAlias("PlayerWithdrawalCooldown", PlayerWithdrawalCooldown.class);
        for (String resource : Arrays.asList("mapper/system/SysConfigMapper.xml",
                "mapper/business/PlayerWithdrawalCooldownMapper.xml")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in, resource);
                new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        redis = mock(RedisCache.class);
        SysConfigServiceImpl system = new SysConfigServiceImpl();
        ReflectionTestUtils.setField(system, "configMapper", sessions.getMapper(SysConfigMapper.class));
        ReflectionTestUtils.setField(system, "redisCache", redis);
        cooldown = new PlayerWithdrawalCooldownServiceImpl(
                sessions.getMapper(PlayerWithdrawalCooldownMapper.class), system);
        // Explicit TransactionTemplate boundaries execute the same real services/mappers in one real TX.
        // No application context, routing aspect, scheduled job or external provider is started.
    }

    @BeforeEach
    void resetScratchTables() {
        reset(redis);
        jdbc.execute("DROP TABLE IF EXISTS player_withdrawal_cooldown_config_audit");
        jdbc.execute("DROP TABLE IF EXISTS sys_config");
        jdbc.execute("CREATE TABLE sys_config (config_id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + "config_key VARCHAR(100) UNIQUE, config_value VARCHAR(500), config_name VARCHAR(100),"
                + "config_type CHAR(1), create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64),"
                + "update_time DATETIME, remark VARCHAR(500)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE player_withdrawal_cooldown_config_audit (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + "config_key VARCHAR(100), before_hours INT NOT NULL, after_hours INT NOT NULL,"
                + "reason VARCHAR(500), changed_by BIGINT, changed_at DATETIME(3)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO sys_config(config_key,config_value) VALUES (?, '48')", KEY);
    }

    @AfterAll
    void dropOnlyOurSchema() throws Exception {
        if (created) try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE `" + mysql.databaseName() + "`");
        }
    }

    @Test
    void competingWriterActuallyTimesOutThenRetriesAgainstCommittedBefore() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch aWritten = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        Future<?> a = worker.submit(() -> tx().execute(status -> {
            assertEquals(48, cooldown.rewriteCooldownHours(24, "A", 1L));
            aWritten.countDown();
            await(releaseA);
            return null;
        }));
        try {
            assertTrue(aWritten.await(10, TimeUnit.SECONDS), "A did not acquire/write config");
            RuntimeException failure = assertThrows(RuntimeException.class, () -> tx().execute(status -> {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                cooldown.rewriteCooldownHours(72, "B blocked", 2L);
                return null;
            }));
            assertTrue(hasMysqlCode(failure, 1205), "B must really wait on the DB lock, not fail elsewhere");
            verifyNoInteractions(redis); // A uncommitted; failed B cannot publish cache changes.
        } finally {
            releaseA.countDown();
            try { a.get(15, TimeUnit.SECONDS); }
            finally { worker.shutdownNow(); }
        }
        assertEquals(24, tx().<Integer>execute(status -> cooldown.rewriteCooldownHours(72, "B retry", 2L)).intValue());
        assertChain();
        verify(redis, times(2)).deleteObject(CACHE);
        verifyNoMoreInteractions(redis);
    }

    @Test
    void currentReadBeatsAnAlreadyEstablishedRepeatableReadSnapshot() throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            tx().execute(status -> {
                assertEquals("48", jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?",
                        String.class, KEY)); // Establish B's RR read view without acquiring a row lock.
                try {
                    writer.submit(() -> tx().execute(a -> cooldown.rewriteCooldownHours(24, "A", 1L)))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception e) { throw new IllegalStateException(e); }
                assertEquals("48", jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?",
                        String.class, KEY), "B still has its original snapshot");
                assertEquals(24, cooldown.rewriteCooldownHours(72, "B current read", 2L));
                return null;
            });
        } finally { writer.shutdownNow(); }
        assertChain();
    }

    @Test
    void updateFailureRollsBackRealAuditAndDoesNotInvalidateCache() {
        jdbc.execute("CREATE TRIGGER c0525_reject_update BEFORE UPDATE ON sys_config FOR EACH ROW"
                + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='c0525 injected update failure'");
        assertThrows(RuntimeException.class, () -> tx().execute(status ->
                cooldown.rewriteCooldownHours(24, "must rollback", 1L)));
        assertEquals("48", value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM player_withdrawal_cooldown_config_audit", Integer.class));
        verifyNoInteractions(redis);
    }

    @Test
    void cacheDeleteFailureDoesNotUndoCommitOrBecomeAuthority() {
        doThrow(new IllegalStateException("mock Redis outage")).when(redis).deleteObject(CACHE);
        assertDoesNotThrow(() -> tx().execute(status -> cooldown.rewriteCooldownHours(24, "commit", 1L)));
        assertEquals("24", value());
        assertEquals(24, tx().<Integer>execute(status -> cooldown.currentCooldownHours()).intValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM player_withdrawal_cooldown_config_audit", Integer.class));
        verify(redis).deleteObject(CACHE);
        verifyNoMoreInteractions(redis);
    }

    private void assertChain() {
        assertEquals("72", value());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT before_hours,after_hours FROM player_withdrawal_cooldown_config_audit ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals(48, ((Number) rows.get(0).get("before_hours")).intValue());
        assertEquals(24, ((Number) rows.get(0).get("after_hours")).intValue());
        assertEquals(24, ((Number) rows.get(1).get("before_hours")).intValue());
        assertEquals(72, ((Number) rows.get(1).get("after_hours")).intValue());
    }

    private String value() {
        return jdbc.queryForObject("SELECT config_value FROM sys_config WHERE config_key=?", String.class, KEY);
    }

    private TransactionTemplate tx() {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(20);
        return tx;
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(12, TimeUnit.SECONDS), "release deadline"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static boolean hasMysqlCode(Throwable failure, int code) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException && ((SQLException) t).getErrorCode() == code) return true;
        }
        return false;
    }

    private Connection admin() throws SQLException {
        return DriverManager.getConnection(mysql.adminUrl(), mysql.user(), mysql.password());
    }
}
