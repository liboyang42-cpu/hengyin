package com.chengyinhub.business.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TandianriProductTypeMigrationTest {
    private static final String MIGRATION_PATH =
            "sql/migration_20260814_tandianri_product_type_backfill.sql";
    private static final Pattern GUARD_CREATE = Pattern.compile(
            "(?s)CREATE TEMPORARY TABLE assert_tandianri_[a-z_]+\\s*\\(.*?\\);"
    );
    private DriverManagerDataSource dataSource;
    private String mysqlSql;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tandianri_migration_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, product_type INT, del_flag INT, status INT, update_by VARCHAR(64), update_time DATETIME)");
        execute("CREATE TABLE oms_ticket (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_type BIGINT, owner_id BIGINT, mode INT, del_flag INT)");
        mysqlSql = new String(Files.readAllBytes(Paths.get(MIGRATION_PATH)), StandardCharsets.UTF_8);
        Matcher matcher = GUARD_CREATE.matcher(mysqlSql);
        int guardCount = 0;
        while (matcher.find()) {
            execute(matcher.group().replaceFirst("CREATE TEMPORARY TABLE", "CREATE TABLE"));
            guardCount++;
        }
        assertEquals(4, guardCount, "H2 的 CHECK 必须全部从真实 migration SQL 提取");
    }

    @Test
    void migrationIsReplaySafeAndNeverExpandsBeyondApprovedTopic23() throws Exception {
        execute("INSERT INTO cms_topic(id,product_type,del_flag,status) VALUES (23,NULL,0,1),(24,NULL,0,0)");
        execute("INSERT INTO oms_ticket(owner_type,owner_id,mode,del_flag) VALUES (1,23,1,0),(1,23,2,0),(1,24,1,0),(1,24,2,0)");

        runMigration();
        runMigration();

        assertEquals(1, scalarInt("SELECT product_type FROM cms_topic WHERE id=23"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM cms_topic WHERE id=24 AND product_type IS NULL"));
    }

    @Test
    void productTypeDriftFailsAtNamedPreGuard() throws Exception {
        execute("INSERT INTO cms_topic(id,product_type,del_flag,status) VALUES (23,2,0,1)");
        execute("INSERT INTO oms_ticket(owner_type,owner_id,mode,del_flag) VALUES (1,23,1,0),(1,23,2,0)");

        assertGuardFails("ck_tandianri_pt_before_exact", this::runMigration);
        assertEquals(2, scalarInt("SELECT product_type FROM cms_topic WHERE id=23"));
    }

    @Test
    void statusDriftFailsAtNamedPreGuardInsteadOfAdjacentGuard() throws Exception {
        execute("INSERT INTO cms_topic(id,product_type,del_flag,status) VALUES (23,NULL,0,0)");
        execute("INSERT INTO oms_ticket(owner_type,owner_id,mode,del_flag) VALUES (1,23,1,0),(1,23,2,0)");

        assertGuardFails("ck_tandianri_pt_before_exact", this::runMigration);
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM cms_topic WHERE id=23 AND product_type IS NULL"));
    }

    @Test
    void anotherActiveTicketedTopicFailsAtNamedGlobalGuard() throws Exception {
        execute("INSERT INTO cms_topic(id,product_type,del_flag,status) VALUES (23,NULL,0,1),(24,NULL,0,1)");
        execute("INSERT INTO oms_ticket(owner_type,owner_id,mode,del_flag) VALUES (1,23,1,0),(1,23,2,0),(1,24,1,0)");

        assertGuardFails("ck_tandianri_pt_global_post_zero", this::runMigration);
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM cms_topic WHERE id=23 AND product_type IS NULL"),
                "失败后的新连接必须读回 id=23 未变，不能只验证额外主题 id=24");
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM cms_topic WHERE id=24 AND product_type IS NULL"));
    }

    @Test
    void preGuardContractIsNamedAndExact() {
        assertGuardContract("assert_tandianri_pt_before", "ck_tandianri_pt_before_exact", "1");
    }

    @Test
    void afterGuardContractIsNamedAndExact() {
        assertGuardContract("assert_tandianri_pt_after", "ck_tandianri_pt_after_exact", "1");
    }

    @Test
    void globalGuardContractIsNamedAndExact() {
        assertGuardContract("assert_tandianri_pt_global_post", "ck_tandianri_pt_global_post_zero", "0");
    }

    @Test
    void replayGuardContractIsNamedAndExact() {
        assertGuardContract("assert_tandianri_pt_replay", "ck_tandianri_pt_replay_zero", "0");
    }

    @Test
    void migrationDeclaresTransactionAndUsesOnlyNonCommittingTemporaryCleanup() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(MIGRATION_PATH), StandardCharsets.UTF_8);
        long declarations = lines.stream()
                .map(String::trim)
                .filter(line -> line.equals("-- runner: requires=transaction"))
                .count();
        long temporaryDrops = lines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith("DROP TEMPORARY TABLE assert_tandianri_"))
                .count();
        long nakedDrops = lines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith("DROP TABLE assert_tandianri_"))
                .count();

        assertEquals(1, declarations, "目标 SQL 必须机器声明 requires=transaction");
        assertEquals(4, temporaryDrops, "四个 guard 都必须用不会 implicit commit 的临时表清理");
        assertEquals(0, nakedDrops, "事务脚本禁止裸 DROP TABLE");
    }

    private void assertGuardContract(String table, String constraint, String expected) {
        Pattern contract = Pattern.compile(
                "(?s)CREATE TEMPORARY TABLE\\s+" + table
                        + "\\s*\\([^;]*CONSTRAINT\\s+" + constraint
                        + "\\s+CHECK\\s*\\(\\s*observed_rows\\s*=\\s*" + expected
                        + "\\s*\\)[^;]*\\);"
        );
        assertTrue(contract.matcher(mysqlSql).find(),
                table + " 必须保留可定位的精确 CHECK 合同 " + constraint);
    }

    private void assertGuardFails(String expectedConstraint, ThrowingRunnable runnable) {
        Exception error = assertThrows(Exception.class, runnable::run);
        StringBuilder messages = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        assertTrue(messages.toString().toLowerCase().contains(expectedConstraint.toLowerCase()),
                "必须由指定 guard 失败，不能被相邻 guard 接住。实际异常: " + messages);
    }

    private void runMigration() throws Exception {
        // H2 1.4 不支持 MySQL 的临时表事务语义；guard 表由真实 SQL 的 CREATE 合同预建，
        // 运行时只执行真实 guard DML，以便失败回滚仍可观测。MySQL 临时表合同由显式 IT 覆盖。
        String h2Sql = GUARD_CREATE.matcher(mysqlSql).replaceAll("")
                .replaceAll("(?m)^DROP TEMPORARY TABLE assert_tandianri_[a-z_]+;\\s*$", "")
                .replaceAll("(?m)^-- runner: requires=transaction\\s*$", "");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement begin = connection.createStatement()) {
                begin.execute("BEGIN");
            }
            try {
                ScriptUtils.executeSqlScript(connection,
                        new ByteArrayResource(h2Sql.getBytes(StandardCharsets.UTF_8)));
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
