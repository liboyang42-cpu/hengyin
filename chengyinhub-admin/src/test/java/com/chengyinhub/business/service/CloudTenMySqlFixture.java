package com.chengyinhub.business.service;

import com.chengyinhub.business.it.ForbiddenDbHosts;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Dedicated VPS or public GitHub-hosted CI: explicit credentials and an exclusive schema. */
public final class CloudTenMySqlFixture implements AutoCloseable {
    public static final String GATE = "CHENGYIN_TEN_FEATURES_MYSQL_IT";
    private final MysqlIntegrationTestConfig config;
    private final Connection admin;
    private boolean created;

    static MysqlIntegrationTestConfig configuration(Map<String, String> env, String suite) {
        return configuration(env, suite, ForbiddenDbHosts.BUILT_IN_SHA256);
    }

    static MysqlIntegrationTestConfig configuration(Map<String, String> env, String suite, Set<String> forbiddenSha256) {
        if (!"1".equals(env.get(GATE))) throw new IllegalArgumentException("Dedicated MySQL test gate is required");
        boolean hosted = "true".equals(env.get("GITHUB_ACTIONS"))
                && "liboyang42-cpu/hengyin".equals(env.get("GITHUB_REPOSITORY"))
                && "github-hosted".equals(env.get("RUNNER_ENVIRONMENT"))
                && "Linux".equals(env.get("RUNNER_OS"));
        if (hosted && "1".equals(env.get("CHENGYIN_TEN_FEATURES_VPS")))
            throw new IllegalArgumentException("GitHub-hosted execution must not claim the dedicated VPS profile");
        if (!hosted && !"1".equals(env.get("CHENGYIN_TEN_FEATURES_VPS")))
            throw new IllegalArgumentException("Dedicated VPS or public GitHub-hosted test execution is required");
        for (String name : new String[]{"HOST", "PORT", "USER", "DATABASE_BASE", "DATABASE_SUFFIX"}) {
            String value = env.get("CHENGYIN_TEST_MYSQL_" + name);
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Explicit MySQL test setting required: " + name);
        }
        if (!env.containsKey("CHENGYIN_TEST_MYSQL_PASSWORD"))
            throw new IllegalArgumentException("Explicit MySQL test PASSWORD setting is required");
        String host = env.get("CHENGYIN_TEST_MYSQL_HOST");
        if (hosted && !"127.0.0.1".equals(host))
            throw new IllegalArgumentException("GitHub-hosted tests require an exclusive loopback MySQL");
        if (ForbiddenDbHosts.isForbidden(host, env.get(ForbiddenDbHosts.ENV), forbiddenSha256))
            throw new IllegalArgumentException("Production destinations are forbidden for tests");
        if (!"cy_cloudten_it".equals(env.get("CHENGYIN_TEST_MYSQL_DATABASE_BASE")))
            throw new IllegalArgumentException("This suite requires the cy_cloudten_it database prefix");
        if (suite == null || !suite.matches("[a-z][a-z0-9_]{0,19}"))
            throw new IllegalArgumentException("Invalid test suite identifier");
        if (!env.get("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX").matches("_[A-Za-z0-9_]{8,24}"))
            throw new IllegalArgumentException("Use a unique database suffix beginning with underscore and 8–24 characters");
        return MysqlIntegrationTestConfig.from(env, suite);
    }

    public static CloudTenMySqlFixture open(String suite) throws Exception {
        return new CloudTenMySqlFixture(configuration(System.getenv(), suite));
    }

    private CloudTenMySqlFixture(MysqlIntegrationTestConfig config) throws Exception {
        this.config = config;
        this.admin = DriverManager.getConnection(config.adminUrl(), config.user(), config.password());
        try {
            if (!"MySQL".equalsIgnoreCase(admin.getMetaData().getDatabaseProductName())
                    || !MysqlIntegrationTestConfig.isMysql8OrNewer(admin.getMetaData().getDatabaseProductVersion()))
                throw new IllegalStateException("Oracle MySQL 8 or newer is required");
            try (Statement statement = admin.createStatement()) {
                // The existing JDBC fixture uses connectionTimeZone=LOCAL. Verify its actual precondition.
                try (ResultSet clock = statement.executeQuery(
                        "SELECT @@session.time_zone, TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())")) {
                    if (!clock.next()) throw new IllegalStateException("Cannot read MySQL session timezone");
                    int expectedOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
                    if (clock.getInt(2) != expectedOffset)
                        throw new IllegalStateException("MySQL session timezone must match the test JVM before creating the exclusive schema");
                    System.out.println("CloudTen test database version=" + admin.getMetaData().getDatabaseProductVersion()
                            + " sessionTimezone=" + clock.getString(1) + " offsetSeconds=" + clock.getInt(2)
                            + " jvmTimezone=" + TimeZone.getDefault().getID());
                }
                statement.execute("CREATE DATABASE `" + config.databaseName() + "` CHARACTER SET utf8mb4");
                created = true;
            }
        } catch (Exception failure) {
            try { admin.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public MysqlIntegrationTestConfig config() { return config; }
    public Connection connection() throws Exception {
        if (!created) throw new IllegalStateException("Test schema is not owned by this fixture");
        return DriverManager.getConnection(config.databaseUrl(), config.user(), config.password());
    }

    @Override public void close() throws Exception {
        if (admin.isClosed()) return;
        try {
            if (created) {
                try (Statement statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE `" + config.databaseName() + "`");
                    created = false;
                }
            }
        } finally { admin.close(); }
    }
}
