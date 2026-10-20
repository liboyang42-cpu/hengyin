package com.chengyinhub.business.mapper;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL ordering checks; scratch schema only, never a business account or database. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerRunSessionOrderingMybatisIntegrationTest {
    private String database;
    private JdbcTemplate admin, db;
    private PlayerRunSessionMapper runs;
    private boolean created;

    @BeforeAll void database() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("run_order");
        database = "chengyin_run_order_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                || config.adminUrl().startsWith("jdbc:mysql://localhost:"), "localhost scratch only");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?", Integer.class, database));
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        db.execute(new String(Files.readAllBytes(Paths.get("sql/migration_20260917_player_run_session.sql")), StandardCharsets.UTF_8));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/PlayerRunSessionMapper.xml"));
        runs = new SqlSessionTemplate(factory.getObject()).getMapper(PlayerRunSessionMapper.class);
    }

    @BeforeEach void reset() { db.execute("DELETE FROM player_run_session"); }

    @AfterAll void cleanup() {
        if (created && admin != null && database.matches("chengyin_run_order_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }

    @Test void olderActivityPauseCannotReviveNewerEndedTombstone() {
        runs.upsertPaused(9L, 27L, 31L, 5, 1000L);
        runs.markEnded(9L, 27L, null, 3000L);
        runs.upsertPaused(9L, 27L, 31L, 7, 2000L);
        Map<String, Object> actual = runs.selectScope(9L, 27L, null);
        assertAll("older activity save must not replace the newer ended tombstone",
                () -> assertEquals("ENDED", actual.get("runState")),
                () -> assertEquals(0, ((Number) actual.get("elapsedSeconds")).intValue()),
                () -> assertEquals(3000L, ((Number) actual.get("savedAt")).longValue()));
    }

    @Test void olderSelfPlayPauseCannotReviveNewerEndedTombstone() {
        runs.upsertPaused(9L, 0L, 31L, 5, 1000L);
        runs.markEnded(9L, 0L, 31L, 3000L);
        runs.upsertPaused(9L, 0L, 31L, 7, 2000L);
        Map<String, Object> actual = runs.selectScope(9L, 0L, 31L);
        assertAll("older self-play save must not replace the newer ended tombstone",
                () -> assertEquals("ENDED", actual.get("runState")),
                () -> assertEquals(0, ((Number) actual.get("elapsedSeconds")).intValue()),
                () -> assertEquals(3000L, ((Number) actual.get("savedAt")).longValue()));
    }

    @Test void newerPauseCanStillStartAnotherRunAfterEnd() {
        runs.upsertPaused(9L, 27L, 31L, 5, 1000L);
        runs.markEnded(9L, 27L, null, 3000L);
        runs.upsertPaused(9L, 27L, 31L, 7, 4000L);
        Map<String, Object> actual = runs.selectScope(9L, 27L, null);
        assertEquals("PAUSED", actual.get("runState"), "newer legitimate run remains allowed");
        assertEquals(7, ((Number) actual.get("elapsedSeconds")).intValue());
        assertEquals(4000L, ((Number) actual.get("savedAt")).longValue());
    }

    @Test void olderPauseCannotOverwriteNewerPausedSnapshot() {
        for (long activity : new long[] {27L, 0L}) {
            runs.upsertPaused(9L, activity, 31L, 11, 3000L);
            runs.upsertPaused(9L, activity, 31L, 7, 2000L);
            Map<String, Object> actual = runs.selectScope(9L, activity, 31L);
            assertEquals("PAUSED", actual.get("runState"));
            assertEquals(11, ((Number) actual.get("elapsedSeconds")).intValue());
            assertEquals(3000L, ((Number) actual.get("savedAt")).longValue());
        }
    }

    @Test void olderClearCannotEndNewerPausedSnapshot() {
        for (long activity : new long[] {27L, 0L}) {
            runs.upsertPaused(9L, activity, 31L, 11, 3000L);
            assertEquals(0, runs.markEnded(9L, activity, 31L, 2000L));
            Map<String, Object> actual = runs.selectScope(9L, activity, 31L);
            assertEquals("PAUSED", actual.get("runState"));
            assertEquals(11, ((Number) actual.get("elapsedSeconds")).intValue());
            assertEquals(3000L, ((Number) actual.get("savedAt")).longValue());
        }
    }

    /** CORE OWN-C04-19 移植:同刻 ENDED 优先,同一 savedAt 的迟到暂停写不能把已结束的局复活。 */
    @Test void sameSavedAtEndedWinsOverLatePause() {
        for (long activity : new long[] {27L, 0L}) {
            runs.upsertPaused(9L, activity, 31L, 5, 3000L);
            runs.markEnded(9L, activity, 31L, 3000L);
            assertEquals("ENDED", runs.selectScope(9L, activity, 31L).get("runState"));
            runs.upsertPaused(9L, activity, 31L, 7, 3000L);
            Map<String, Object> actual = runs.selectScope(9L, activity, 31L);
            assertEquals("ENDED", actual.get("runState"));
            assertEquals(3000L, ((Number) actual.get("savedAt")).longValue());
            runs.upsertPaused(9L, activity, 31L, 9, 3001L);
            assertEquals("PAUSED", runs.selectScope(9L, activity, 31L).get("runState"));
        }
    }

}
