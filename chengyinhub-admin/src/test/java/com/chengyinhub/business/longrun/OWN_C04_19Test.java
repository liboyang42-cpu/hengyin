package com.chengyinhub.business.longrun;

import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.PlayerRunSessionMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Real MySQL/MyBatis ordering semantics, explicit dedicated-fixture gate; no production DDL. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_RUN_SESSION_ORDER_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OWN_C04_19Test {
    private DriverManagerDataSource source;
    private SqlSessionFactory factory;
    private String ownedSchema;

    @BeforeAll void createOwnedFixture() throws Exception {
        String prefix = "c04_run_order_";
        int length = Math.min(16, 64 - MybatisItDatabase.databaseName(prefix).length());
        if (length < 12) throw new IllegalArgumentException("DB_SUFFIX leaves insufficient random scratch identity within MySQL 64 characters");
        String suite = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, length);
        ownedSchema = MybatisItDatabase.databaseName(suite);
        if (!ownedSchema.matches("[a-zA-Z0-9_]{1,64}"))
            throw new IllegalArgumentException("CHENGYIN_MYBATIS_IT_DB_SUFFIX produces invalid scratch database name");
        source = new DriverManagerDataSource(MybatisItDatabase.url(suite), MybatisItDatabase.user(), MybatisItDatabase.password());
        Path migration = Paths.get("chengyinhub-admin/sql/migration_20260917_player_run_session.sql");
        if (!Files.exists(migration)) migration = Paths.get("sql/migration_20260917_player_run_session.sql");
        String ddl = new String(Files.readAllBytes(migration), StandardCharsets.UTF_8);
        int begin = ddl.indexOf("CREATE TABLE IF NOT EXISTS player_run_session");
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute(ddl.substring(begin, ddl.indexOf(';', begin)));
        }
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("c04-run-order", new JdbcTransactionFactory(), source));
        String path = "mapper/business/PlayerRunSessionMapper.xml";
        try (InputStream xml = Resources.getResourceAsStream(path)) {
            new XMLMapperBuilder(xml, cfg, path, cfg.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }
    @BeforeEach void clearFixture() throws Exception {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) { s.execute("delete from player_run_session"); }
    }
    @AfterAll void dropOnlyOwnedFixture() throws Exception {
        if (source != null && ownedSchema != null && ownedSchema.matches("chengyin_mybatis_c04_run_order_[a-zA-Z0-9_]+"))
            try (Connection c = source.getConnection(); Statement s = c.createStatement()) { s.execute("drop database `" + ownedSchema + "`"); }
    }
    private void assertRow(PlayerRunSessionMapper mapper, long member, long activity, Long topic,
                           String state, int elapsed, long savedAt) {
        Map<String, Object> row = mapper.selectScope(member, activity, topic);
        assertNotNull(row); assertEquals(state, row.get("runState"));
        assertEquals(elapsed, ((Number) row.get("elapsedSeconds")).intValue());
        assertEquals(savedAt, ((Number) row.get("savedAt")).longValue());
    }
    @Test void oldPauseCannotReplaceNewPauseOrAnEndedTombstone() {
        try (SqlSession session = factory.openSession(true)) {
            PlayerRunSessionMapper mapper = session.getMapper(PlayerRunSessionMapper.class);
            mapper.upsertPaused(9L, 27L, 31L, 10, 100L);
            mapper.upsertPaused(9L, 27L, 31L, 20, 200L);
            mapper.upsertPaused(9L, 27L, 31L, 1, 100L);
            assertRow(mapper, 9, 27, null, "PAUSED", 20, 200);
            mapper.markEnded(9L, 27L, null, 300L);
            mapper.upsertPaused(9L, 27L, 31L, 2, 200L);
            assertRow(mapper, 9, 27, null, "ENDED", 0, 300);
        }
    }
    @Test void endedWinsEqualTimestampInBothArrivalOrders() {
        try (SqlSession session = factory.openSession(true)) {
            PlayerRunSessionMapper mapper = session.getMapper(PlayerRunSessionMapper.class);
            mapper.upsertPaused(9L, 27L, 31L, 10, 100L);
            mapper.markEnded(9L, 27L, null, 100L);
            mapper.upsertPaused(9L, 27L, 31L, 99, 100L);
            assertRow(mapper, 9, 27, null, "ENDED", 0, 100);
            mapper.upsertPaused(9L, 27L, 31L, 20, 200L);
            mapper.upsertPaused(9L, 27L, 31L, 21, 200L);
            mapper.markEnded(9L, 27L, null, 200L);
            assertRow(mapper, 9, 27, null, "ENDED", 0, 200);
        }
    }
    @Test void oldClearCannotEraseNewPauseAndNewerPauseStillReopens() {
        try (SqlSession session = factory.openSession(true)) {
            PlayerRunSessionMapper mapper = session.getMapper(PlayerRunSessionMapper.class);
            mapper.upsertPaused(9L, 27L, 31L, 10, 100L);
            mapper.markEnded(9L, 27L, null, 200L);
            mapper.upsertPaused(9L, 27L, 31L, 7, 300L);
            assertEquals(0, mapper.markEnded(9L, 27L, null, 200L));
            assertRow(mapper, 9, 27, null, "PAUSED", 7, 300);
        }
    }
    @Test void scopeIsolationAndNoRowClearContractRemainUnchanged() {
        try (SqlSession session = factory.openSession(true)) {
            PlayerRunSessionMapper mapper = session.getMapper(PlayerRunSessionMapper.class);
            assertEquals(0, mapper.markEnded(9L, 0L, 99L, 9000L));
            assertNull(mapper.selectScope(9L, 0L, 99L), "do not invent a missing-scope tombstone");
            mapper.upsertPaused(9L, 0L, 31L, 5, 100L);
            mapper.upsertPaused(8L, 27L, 31L, 6, 100L);
            mapper.upsertPaused(9L, 27L, 31L, 7, 100L);
            mapper.markEnded(9L, 27L, null, 200L);
            assertRow(mapper, 9, 0, 31L, "PAUSED", 5, 100);
            assertRow(mapper, 8, 27, null, "PAUSED", 6, 100);
            assertRow(mapper, 9, 27, null, "ENDED", 0, 200);
        }
    }
}
