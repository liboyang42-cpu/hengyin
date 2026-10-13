package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlIntegrationTestConfigTest {
    @Test
    void environmentBuildsIsolatedSuiteDatabaseAndCredentials() {
        Map<String, String> values = new HashMap<>();
        values.put(MysqlIntegrationTestConfig.HOST, "mysql8.internal");
        values.put(MysqlIntegrationTestConfig.PORT, "3307");
        values.put(MysqlIntegrationTestConfig.DATABASE_BASE, "chengyin_ci");
        values.put(MysqlIntegrationTestConfig.DATABASE_SUFFIX, "_42");
        values.put(MysqlIntegrationTestConfig.USER, "ci_user");
        values.put(MysqlIntegrationTestConfig.PASSWORD, "secret");

        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.from(values, "funds_core_it");

        assertEquals("chengyin_ci_funds_core_it_42", config.databaseName());
        assertEquals("ci_user", config.user());
        assertEquals("secret", config.password());
        assertEquals(true, config.databaseUrl().startsWith("jdbc:mysql://mysql8.internal:3307/"));
        assertEquals(true, config.adminUrl().contains("connectionTimeZone=LOCAL"));
        assertEquals(true, config.databaseUrl().contains("connectionTimeZone=LOCAL"));
        assertEquals(false, config.adminUrl().contains("serverTimezone=Asia/Shanghai"));
        assertEquals(false, config.databaseUrl().contains("serverTimezone=Asia/Shanghai"));
    }

    @Test
    void versionGateAcceptsMysql8AndNewerOnly() {
        assertEquals(true, MysqlIntegrationTestConfig.isMysql8OrNewer("8.0.45"));
        assertEquals(true, MysqlIntegrationTestConfig.isMysql8OrNewer("9.7.1"));
        assertEquals(true, MysqlIntegrationTestConfig.isMysql8OrNewer("10.0.0"));
        assertEquals(false, MysqlIntegrationTestConfig.isMysql8OrNewer("5.7.44-log"));
        assertEquals(false, MysqlIntegrationTestConfig.isMysql8OrNewer("10.11.6-MariaDB"));
        assertEquals(false, MysqlIntegrationTestConfig.isMysql8OrNewer("unknown"));
        assertEquals(false, MysqlIntegrationTestConfig.isMysql8OrNewer(null));
    }

    @Test
    void unsafeDatabaseIdentifierIsRejectedBeforeSqlConcatenation() {
        Map<String, String> values = new HashMap<>();
        values.put(MysqlIntegrationTestConfig.DATABASE_SUFFIX, ";DROP_DATABASE");

        assertThrows(IllegalArgumentException.class,
                () -> MysqlIntegrationTestConfig.from(values, "funds_core_it"));
    }
}
