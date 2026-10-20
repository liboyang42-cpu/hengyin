package com.chengyinhub.business.it.support;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only loopback scratch. No environment-selectable database name, remote host or H2 fallback. */
public final class IsolatedMySqlSchema implements AutoCloseable {
    private MysqlIntegrationTestConfig config;
    private boolean created;
    private final List<String> migrations = new ArrayList<>();
    private final ScratchDataSource source = new ScratchDataSource();

    public DriverManagerDataSource open(String suite) throws Exception {
        Map<String,String> env = new HashMap<>(System.getenv());
        assertEquals("1", env.get("CHENGYIN_FUNDS_MYSQL_IT"), "Explicit MySQL opt-in required");
        String host = env.get("CHENGYIN_TEST_MYSQL_HOST");
        assertTrue("127.0.0.1".equals(host) || "localhost".equals(host), "Only explicit loopback scratch allowed");
        for (String key : Arrays.asList("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
            assertTrue(env.containsKey(key), "Required MySQL setting: " + key);
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "cy_module_city");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        config = MysqlIntegrationTestConfig.from(env, suite);
        try (Connection connection = DriverManager.getConnection(config.adminUrl(), config.user(), config.password()); Statement sql = connection.createStatement()) {
            try (ResultSet row = sql.executeQuery("SELECT VERSION(), @@version_comment")) {
                assertTrue(row.next()); assertEquals("8.0.45", row.getString(1));
                assertEquals("MySQL Community Server - GPL", row.getString(2));
            }
            sql.execute("CREATE DATABASE `" + config.databaseName() + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            created = true;
        }
        source.setDriverClassName("com.mysql.cj.jdbc.Driver"); source.setUrl(config.databaseUrl());
        source.setUsername(config.user()); source.setPassword(config.password());
        try (Connection connection = source.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://"));
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(config.databaseName(), connection.getCatalog());
        }
        return source;
    }
    public void isolation(String value) {
        if (!"REPEATABLE READ".equals(value)) throw new IllegalArgumentException("Only MySQL RR is a supported hostile default");
        source.isolation = Connection.TRANSACTION_REPEATABLE_READ;
    }
    public void apply(String migration) throws Exception { applyMigration(source, migration); migrations.add(migration); }

    public void assertModuleSchema(int tables, int foreignKeys) {
        org.springframework.jdbc.core.JdbcTemplate jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);
        assertEquals(tables,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'w18\\_%' AND engine='InnoDB'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'w18\\_%' AND data_type='datetime' AND datetime_precision<>6",Integer.class));
        assertEquals("ascii_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='w18_module_install_reservation' AND column_name='reservation_key'",String.class));
        assertEquals("utf8mb4_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='w18_module_install_reservation' AND column_name='request_id'",String.class));
        assertEquals(foreignKeys,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name LIKE 'fk_w18\\_%'",Integer.class));
    }

    /** Executes unmodified repository bytes through ci/sql_splitter, including scalar readback assertions. */
    public static void applyMigration(javax.sql.DataSource source, String migration) throws Exception {
        if (!migration.matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("migration name");
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if ("chengyinhub-admin".equals(root.getFileName().toString())) root = root.getParent();
        String code = "import json,sys;from pathlib import Path;sys.path.insert(0,'ci');"
                + "from sql_splitter import split_statements,delimiter_error,unterminated_token;"
                + "s=Path(sys.argv[1]).read_text(encoding='utf-8');"
                + "assert not delimiter_error(s) and not unterminated_token(s);print(json.dumps(split_statements(s)))";
        Process process = new ProcessBuilder("python3", "-c", code, "chengyinhub-admin/sql/migration_" + migration + ".sql")
                .directory(root.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) { byte[] buffer = new byte[4096]; int n; while ((n=input.read(buffer))!=-1) bytes.write(buffer,0,n); }
        assertTrue(process.waitFor(15,TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), "Repository SQL splitter required");
        JsonNode statements = new ObjectMapper().readTree(bytes.toByteArray()); assertTrue(statements.isArray()); assertTrue(statements.size()>0);
        try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            for (JsonNode statement : statements) {
                if (sql.execute(statement.asText())) try (ResultSet result = sql.getResultSet()) {
                    assertTrue(result.next(), "Migration assertion must produce one row");
                    assertEquals(1, result.getInt(1), "Actual migration scalar assertion failed: " + migration);
                    assertFalse(result.wasNull()); assertFalse(result.next());
                }
            }
        }
        System.out.println("MODULE_CITY_MYSQL_MIGRATION " + migration + " STATEMENTS=" + statements.size() + " ORIGINAL_BYTES=true ASSERTIONS_CHECKED=true");
    }
    @Override public void close() throws Exception {
        if (!created) return;
        try (Connection connection = DriverManager.getConnection(config.adminUrl(),config.user(),config.password()); Statement sql=connection.createStatement()) {
            sql.execute("DROP DATABASE `" + config.databaseName() + "`");
            try (ResultSet result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+config.databaseName()+"'")) {
                assertTrue(result.next()); assertEquals(0,result.getInt(1));
            }
        }
        created=false;
        System.out.println("MODULE_CITY_MYSQL_IT VERSION=8.0.45 SCHEMA="+config.databaseName()+" MIGRATIONS="+migrations+" SCRATCH_DROPPED=true");
    }
    private static final class ScratchDataSource extends DriverManagerDataSource {
        int isolation=Connection.TRANSACTION_REPEATABLE_READ;
        @Override public Connection getConnection() throws SQLException {
            Connection connection=super.getConnection();
            try (Statement sql=connection.createStatement()) {
                sql.execute("SET SESSION innodb_lock_wait_timeout=10");
                sql.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'");
                connection.setTransactionIsolation(isolation);
                return connection;
            } catch (Throwable failure) { connection.close(); throw failure; }
        }
    }
}
