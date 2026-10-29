package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 专属 MySQL 库上的真实迁移、mapper、唯一约束和本地效果+回执事务边界；不代表 Photo 链路。 */
class AdvancedCommandFingerprintMySqlIT {
    private static PooledDataSource ds;
    private static JdbcTemplate jdbc;
    private static PlayAttemptLogMapper attempts;
    private static TransactionTemplate tx;
    private static String createdDatabase;

    @BeforeAll static void schema() throws Exception {
        for (String name : new String[]{"HOST", "PORT", "USER", "PASSWORD", "DB_SUFFIX"}) {
            String configured = System.getenv("CHENGYIN_MYBATIS_IT_" + name);
            assertNotNull(configured, "explicit IT variable required: " + name);
            if (!"PASSWORD".equals(name)) assertFalse(configured.isEmpty(), "empty IT variable: " + name);
        }
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        assertTrue(MybatisItDatabase.port().matches("[0-9]{1,5}"));
        int port = Integer.parseInt(MybatisItDatabase.port()); assertTrue(port > 0 && port <= 65535);
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("_[a-z0-9_]{1,20}"));
        String url = MybatisItDatabase.url("c_advanced_fingerprint");
        createdDatabase = MybatisItDatabase.databaseName("c_advanced_fingerprint");
        ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(ds);
        // Only this class's isolated CI database; never a production or shared schema.
        jdbc.execute("DROP TABLE IF EXISTS play_attempt_log");
        jdbc.execute("CREATE TABLE play_attempt_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,member_id BIGINT,topic_id BIGINT,"
                + "node_id BIGINT,activity_id BIGINT,attempt_type TINYINT,result TINYINT,wrong_answer VARCHAR(120),"
                + "duration_s INT,result_json JSON,idempotency_key VARCHAR(128),created_at DATETIME,"
                + "UNIQUE KEY uk_play_attempt_idempotency(idempotency_key)) ENGINE=InnoDB");
        jdbc.execute("DROP TABLE IF EXISTS c_fingerprint_effect");
        jdbc.execute("CREATE TABLE c_fingerprint_effect(id BIGINT PRIMARY KEY,effect_count INT NOT NULL) ENGINE=InnoDB");
        // A historical advanced row must stay NULL through migration and rerun.
        jdbc.update("INSERT INTO play_attempt_log(member_id,attempt_type,idempotency_key,result_json) VALUES(7,8,'historical','{\"sessionId\":51}')");
        try (Connection connection = ds.getConnection()) {
            ItSchema.applyMigrations(connection, "sql/migration_20261023_advanced_command_fingerprint.sql",
                    "sql/migration_20261023_advanced_command_fingerprint.sql");
        }
        assertNull(jdbc.queryForObject("SELECT request_fingerprint FROM play_attempt_log WHERE idempotency_key='historical'", String.class));
        Configuration config = new Configuration(new Environment("c-fingerprint", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAlias("PlayAttemptLog", PlayAttemptLog.class);
        String resource = "mapper/business/PlayAttemptLogMapper.xml";
        try (InputStream input = AdvancedCommandFingerprintMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        attempts = new SqlSessionTemplate(factory.getObject()).getMapper(PlayAttemptLogMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterAll static void close() throws Exception {
        if (ds != null) ds.forceCloseAll();
        if (createdDatabase == null) return;
        assertTrue(createdDatabase.matches("chengyin_mybatis_c_advanced_fingerprint_[a-z0-9_]{1,20}"));
        try (Connection admin = DriverManager.getConnection("jdbc:mysql://" + MybatisItDatabase.host() + ":"
                + MybatisItDatabase.port() + "/?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE `" + createdDatabase + "`");
        }
    }
    @BeforeEach void resetOwnFixture() {
        jdbc.update("DELETE FROM play_attempt_log WHERE idempotency_key <> 'historical' OR idempotency_key IS NULL");
        jdbc.update("DELETE FROM c_fingerprint_effect"); jdbc.update("INSERT INTO c_fingerprint_effect VALUES(1,0)");
    }

    private static PlayAttemptLog row(String key, String hash) {
        PlayAttemptLog row = new PlayAttemptLog(); row.setMemberId(7L); row.setTopicId(19L); row.setNodeId(29L);
        row.setActivityId(9L); row.setAttemptType(8); row.setResult(1); row.setIdempotencyKey(key);
        row.setRequestFingerprint(hash); row.setResultJson("{\"sessionId\":51,\"version\":1}"); row.setCreatedAt(new Date());
        return row;
    }

    @Test void mapperRoundTripPreservesHashAndOrdinaryNull() {
        String hash = "a".repeat(64);
        assertEquals(1, attempts.insertPlayAttemptLog(row("bound", hash)));
        assertEquals(hash, attempts.selectByIdempotencyKey("bound").getRequestFingerprint());
        PlayAttemptLog ordinary = row(null, null); ordinary.setAttemptType(1);
        assertEquals(1, attempts.insertPlayAttemptLog(ordinary));
        assertNull(attempts.selectByIdempotencyKey("historical").getRequestFingerprint());
    }

    @Test void receiptInsertionFailureRollsBackPriorLocalEffect() {
        attempts.insertPlayAttemptLog(row("same", "a".repeat(64)));
        assertThrows(DuplicateKeyException.class, () -> tx.execute(status -> {
            jdbc.update("UPDATE c_fingerprint_effect SET effect_count=effect_count+1 WHERE id=1");
            attempts.insertPlayAttemptLog(row("same", "b".repeat(64))); return null;
        }));
        assertEquals(0, jdbc.queryForObject("SELECT effect_count FROM c_fingerprint_effect WHERE id=1", Integer.class));
        assertEquals("a".repeat(64), attempts.selectByIdempotencyKey("same").getRequestFingerprint());
    }

    @Test void concurrentSameIdentityHasOneReceiptAndOneCommittedEffect() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(1);
        try {
            Callable<Boolean> command = () -> {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                try {
                    tx.execute(status -> {
                        jdbc.update("UPDATE c_fingerprint_effect SET effect_count=effect_count+1 WHERE id=1");
                        attempts.insertPlayAttemptLog(row("concurrent", "c".repeat(64))); return null;
                    }); return true;
                } catch (DuplicateKeyException loser) { return false; }
            };
            Future<Boolean> a = workers.submit(command), b = workers.submit(command); ready.countDown();
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT effect_count FROM c_fingerprint_effect WHERE id=1", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM play_attempt_log WHERE idempotency_key='concurrent'", Integer.class));
        } finally { workers.shutdownNow(); }
    }

    @Test void migrationRejectsWrongExistingColumnInsteadOfGuessingAHash() throws Exception {
        jdbc.execute("ALTER TABLE play_attempt_log MODIFY request_fingerprint CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL");
        try (Connection connection = ds.getConnection()) {
            assertThrows(java.sql.SQLException.class, () -> ItSchema.applyMigrations(connection,
                    "sql/migration_20261023_advanced_command_fingerprint.sql"));
            assertNull(attempts.selectByIdempotencyKey("historical").getRequestFingerprint());
        } finally {
            jdbc.execute("ALTER TABLE play_attempt_log MODIFY request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL");
        }
    }

    @Test void migrationRejectsMissingBusinessUniqueIndex() throws Exception {
        jdbc.execute("ALTER TABLE play_attempt_log DROP INDEX uk_play_attempt_idempotency");
        try (Connection connection = ds.getConnection()) {
            assertThrows(java.sql.SQLException.class, () -> ItSchema.applyMigrations(connection,
                    "sql/migration_20261023_advanced_command_fingerprint.sql"));
        } finally {
            jdbc.execute("ALTER TABLE play_attempt_log ADD UNIQUE KEY uk_play_attempt_idempotency(idempotency_key)");
        }
    }
}
