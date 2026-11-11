package com.chengyinhub.business.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-money P0-17：真实 MySQL 8 上原样执行 {@code migration_20261111_creator_income_detail_idempotency.sql}（两次），
 * 证明幂等唯一键真的挡住同一键的第二次入账，且存量 NULL 键不冲突。
 *
 * <p>表结构取自建表迁移 {@code migration_p3_phase_b_player_world_creator.sql} 里的原文 CREATE TABLE，不手写。
 * 只在 {@link MybatisItDatabase} 分配的一次性 scratch 库里跑，结束时 DROP 并回读确认。
 * ⚠️ surefire 默认只收 *Test，本类需 -Dtest 点名（Real Storage IT 工作流按登记执行）。
 */
class CreatorIncomeDetailIdempotencyMySqlIT {
    private static final String SUITE = "creator_income_idem";
    private static final String CREATE_SOURCE = "sql/migration_p3_phase_b_player_world_creator.sql";
    private static final String MIGRATION = "sql/migration_20261111_creator_income_detail_idempotency.sql";
    private static String database;
    private static String adminUrl;
    private static String url;

    @BeforeAll
    static void start() throws Exception {
        adminUrl = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select version()")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).startsWith("8.0."), "必须是 MySQL 8.0，实际=" + rs.getString(1));
        }
        url = MybatisItDatabase.url(SUITE);
        database = MybatisItDatabase.databaseName(SUITE);
        String source = new String(Files.readAllBytes(Paths.get(CREATE_SOURCE)), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS creator_income_detail \\(.*?\\)[^;]*;").matcher(source);
        assertTrue(m.find(), "建表迁移里找不到 creator_income_detail 的 CREATE TABLE");
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(m.group().substring(0, m.group().length() - 1));
            // 存量行：没有幂等键（NULL），迁移不得回填也不得因它失败
            st.execute("insert into creator_income_detail (creator_id, source_type, source_id, amount, income_status, occur_time)"
                    + " values (7, 'LEGACY', 1, 1.00, 0, now()), (7, 'LEGACY', 1, 1.00, 0, now())");
            ItSchema.applyMigrations(c, MIGRATION);
            ItSchema.applyMigrations(c, MIGRATION); // 可重复执行
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (database == null || adminUrl == null) return;
        assertTrue(database.matches("chengyin_mybatis_creator_income_idem[a-z0-9_]*"), "只许 DROP 本套 scratch 库:" + database);
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            st.execute("drop database `" + database + "`");
            try (ResultSet rs = st.executeQuery("select count(*) from information_schema.schemata where schema_name = '" + database + "'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("delete from creator_income_detail where source_type <> 'LEGACY'");
        }
    }

    @Test
    void uniqueKeyExistsOnIdempotencyKeyOnlyAndLegacyRowsKeepNullKeys() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("select non_unique, group_concat(column_name order by seq_in_index)"
                    + " from information_schema.statistics where table_schema=database() and table_name='creator_income_detail'"
                    + " and index_name='uk_creator_income_idempotency' group by non_unique")) {
                assertTrue(rs.next(), "缺少 uk_creator_income_idempotency");
                assertEquals(0, rs.getInt(1));
                assertEquals("idempotency_key", rs.getString(2));
            }
            try (ResultSet rs = st.executeQuery("select count(*), count(idempotency_key) from creator_income_detail where source_type='LEGACY'")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals(0, rs.getInt(2), "存量行不回填幂等键");
            }
        }
    }

    @Test
    void replayWithTheSameKeyIsRejectedByTheDatabase() throws Exception {
        String insert = "insert into creator_income_detail (creator_id, source_type, source_id, idempotency_key, amount, income_status, occur_time)"
                + " values (9, 'TOPIC_SETTLEMENT', 42, 'TOPIC_SETTLEMENT:42:9', 12.34, 0, now())";
        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertEquals(1, st.executeUpdate(insert));
            assertThrows(SQLIntegrityConstraintViolationException.class, () -> st.executeUpdate(insert));
            assertEquals(0, st.executeUpdate(insert.replaceFirst("insert into", "insert ignore into")));
            try (ResultSet rs = st.executeQuery("select count(*), sum(amount) from creator_income_detail where idempotency_key='TOPIC_SETTLEMENT:42:9'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("12.34", rs.getBigDecimal(2).toPlainString());
            }
            // 不同事件（不同键）照常各记一行
            assertEquals(1, st.executeUpdate(insert.replace("TOPIC_SETTLEMENT:42:9", "TOPIC_SETTLEMENT:43:9").replace(", 42,", ", 43,")));
        }
    }
}
