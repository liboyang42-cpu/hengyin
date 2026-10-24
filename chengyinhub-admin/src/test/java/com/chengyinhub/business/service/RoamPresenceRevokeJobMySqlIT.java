package com.chengyinhub.business.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Only the newly created test schema is mutated; no scheduler or current application is started. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoamPresenceRevokeJobMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("roam_presence_revoke_job_it");
    private JdbcTemplate admin, db;
    private DriverManagerDataSource source;
    private boolean created;
    private String migration, intentDdl;
    private static final String TARGET = "roamPresenceRevokeAuditTask.run()";

    @BeforeAll void setup() throws Exception {
        admin = new JdbcTemplate(source(mysql.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        created = true;
        source = source(mysql.databaseUrl()); db = new JdbcTemplate(source);
        Path root = Files.exists(Path.of("sql/ry_20250522.sql")) ? Path.of("") : Path.of("..");
        String base = Files.readString(root.resolve("sql/ry_20250522.sql"));
        int start = base.indexOf("create table sys_job (");
        int end = base.indexOf(';', start);
        assertTrue(start >= 0 && end > start);
        db.execute(base.substring(start, end + 1));
        migration = Files.readString(root.resolve("chengyinhub-admin/sql/migration_20261012_roam_presence_revoke_job.sql"));
        String intent = Files.readString(root.resolve("chengyinhub-admin/sql/migration_20261012_roam_presence_revoke_intent.sql"));
        int intentStart = intent.indexOf("CREATE TABLE IF NOT EXISTS roam_presence_revoke_intent (");
        int intentEnd = intent.indexOf(';', intentStart);
        assertTrue(intentStart >= 0 && intentEnd > intentStart);
        intentDdl = intent.substring(intentStart, intentEnd + 1);
    }

    @BeforeEach void clearOnlyOwnedFixtureRows() { db.execute("delete from sys_job"); db.execute(intentDdl); }
    @AfterAll void cleanup() {
        if (created) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",
                    Integer.class, mysql.databaseName()));
        }
    }

    @Test void missingCompensatorIsRegisteredOnceWithTheRealMinuteSchedule() throws Exception {
        applyMigration(); applyMigration();
        Map<String,Object> row = db.queryForMap("select * from sys_job where invoke_target=?", TARGET);
        assertEquals(1, db.queryForObject("select count(*) from sys_job", Integer.class));
        assertEquals("0 * * * * ?", row.get("cron_expression")); assertEquals("COMPLIANCE", row.get("job_group"));
        assertEquals("0", row.get("status")); assertEquals("1", row.get("concurrent")); assertEquals("3", row.get("misfire_policy"));
    }

    @Test void existingPauseAndCustomScheduleStayByteForByteUnchanged() throws Exception {
        db.update("insert into sys_job(job_name,job_group,invoke_target,cron_expression,status,remark) values(?,?,?,?,?,?)",
                "operator-owned", "CUSTOM", TARGET, "operator-schedule", "1", "requires separate approval");
        Map<String,Object> before = db.queryForMap("select * from sys_job where invoke_target=?", TARGET);
        applyMigration(); applyMigration();
        assertEquals(before, db.queryForMap("select * from sys_job where invoke_target=?", TARGET));
    }

    @Test void duplicateTargetStopsBeforeInsertAndDoesNotChangeAnyOriginalRow() {
        db.update("insert into sys_job(job_name,invoke_target,status) values('first',?,'1'),('second',?,'0')", TARGET, TARGET);
        List<Map<String,Object>> before = db.queryForList("select * from sys_job order by job_id");
        assertThrows(AssertionError.class, this::applyMigration);
        assertEquals(before, db.queryForList("select * from sys_job order by job_id"));
    }

    @Test void absentPredeployIntentSchemaCannotRegisterACompensator() {
        db.execute("drop table roam_presence_revoke_intent");
        assertThrows(AssertionError.class, this::applyMigration);
        assertEquals(0, db.queryForObject("select count(*) from sys_job", Integer.class));
    }

    private void applyMigration() throws Exception {
        long expected = migration.lines().filter(line -> line.trim().equals("-- runner: assert=scalar-true")).count();
        assertEquals(4L, expected);
        AtomicInteger observed = new AtomicInteger();
        try (Connection connection = source.getConnection()) {
            Connection checked = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                Object result;
                try { result = method.invoke(connection, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                if (!method.getName().equals("createStatement")) return result;
                Statement statement = (Statement) result;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (p, operation, parameters) -> {
                    Object value;
                    try { value = operation.invoke(statement, parameters); } catch (InvocationTargetException e) { throw e.getCause(); }
                    if (operation.getName().equals("execute") && parameters != null && parameters[0] instanceof String sql
                            && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ")) {
                        assertEquals(Boolean.TRUE, value);
                        try (ResultSet rows = statement.getResultSet()) {
                            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertFalse(rows.next());
                        }
                        observed.incrementAndGet();
                    }
                    return value;
                });
            });
            ScriptUtils.executeSqlScript(checked, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            assertEquals(expected, observed.get());
        }
    }
    private DriverManagerDataSource source(String url) { return new DriverManagerDataSource(url, mysql.user(), mysql.password()); }
}
