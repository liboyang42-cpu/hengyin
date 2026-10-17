package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Pure configuration tests, run with the rest of the backend tests on the dedicated VPS. */
class CloudTenMySqlFixtureConfigTest {
    private Map<String, String> explicit() {
        Map<String, String> env = new HashMap<>();
        env.put(CloudTenMySqlFixture.GATE, "1");
        env.put("CHENGYIN_TEN_FEATURES_VPS", "1");
        env.put("CHENGYIN_TEST_MYSQL_HOST", "127.0.0.1");
        env.put("CHENGYIN_TEST_MYSQL_PORT", "3307");
        env.put("CHENGYIN_TEST_MYSQL_USER", "isolated_test");
        env.put("CHENGYIN_TEST_MYSQL_PASSWORD", "");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "cy_cloudten_it");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_run12345678");
        return env;
    }
    @Test void acceptsOnlyAnExplicitExclusiveNamespace() {
        MysqlIntegrationTestConfig config = CloudTenMySqlFixture.configuration(explicit(), "relay");
        assertEquals("cy_cloudten_it_relay_run12345678", config.databaseName());
        assertEquals("isolated_test", config.user());
    }
    @Test void noConnectionSettingCanSilentlyUseAnExistingDefault() {
        for (String key : explicit().keySet()) {
            Map<String, String> env = explicit(); env.remove(key);
            assertThrows(IllegalArgumentException.class, () -> CloudTenMySqlFixture.configuration(env, "relay"), key);
        }
    }
    @Test void rejectsSharedOrProductionNamespacesAndUnsafeIdentifiers() {
        Map<String, String> env = explicit();
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "chengyinhubdb");
        assertThrows(IllegalArgumentException.class, () -> CloudTenMySqlFixture.configuration(env, "relay"));
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "cy_cloudten_it");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "");
        assertThrows(IllegalArgumentException.class, () -> CloudTenMySqlFixture.configuration(env, "relay"));
        assertThrows(IllegalArgumentException.class, () -> CloudTenMySqlFixture.configuration(explicit(), "bad`suite"));
    }
    @Test void refusesKnownProductionDestinationsBeforeOpeningAConnection() {
        for (String host : new String[]{"47.103.124.64", "47.100.84.122"}) {
            Map<String, String> env = explicit(); env.put("CHENGYIN_TEST_MYSQL_HOST", host);
            assertThrows(IllegalArgumentException.class, () -> CloudTenMySqlFixture.configuration(env, "relay"));
        }
    }
}
