package com.chengyinhub.business.service;

import com.chengyinhub.business.it.ForbiddenDbHosts;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
    // 生产地址不写明文(公开仓):负控用 TEST-NET 夹具地址,在运行时算哈希走同一条哈希比对路径。
    private static final String FIXTURE_HOST = "203.0.113.7";

    @Test void refusesHostWhoseSha256IsDenylistedBeforeOpeningAConnection() {
        Map<String, String> env = explicit(); env.put("CHENGYIN_TEST_MYSQL_HOST", FIXTURE_HOST);
        Set<String> denylist = Set.of(ForbiddenDbHosts.sha256Hex(FIXTURE_HOST));
        assertThrows(IllegalArgumentException.class, () -> CloudTenMySqlFixture.configuration(env, "relay", denylist));
        env.put("CHENGYIN_TEST_MYSQL_HOST", " " + FIXTURE_HOST + " ");
        assertThrows(IllegalArgumentException.class, () -> CloudTenMySqlFixture.configuration(env, "relay", denylist));
        // 对照:同一主机不在名单里就放行 —— 证明上面是哈希名单拒的,不是别的闸。
        env.put("CHENGYIN_TEST_MYSQL_HOST", FIXTURE_HOST);
        assertEquals("cy_cloudten_it_relay_run12345678",
                CloudTenMySqlFixture.configuration(env, "relay", Set.of()).databaseName());
    }
    @Test void refusesHostListedInTheEnvironmentDenylist() {
        Map<String, String> env = explicit();
        env.put("CHENGYIN_TEST_MYSQL_HOST", FIXTURE_HOST);
        env.put(ForbiddenDbHosts.ENV, "198.51.100.1, " + FIXTURE_HOST.toUpperCase());
        assertThrows(IllegalArgumentException.class, () -> CloudTenMySqlFixture.configuration(env, "relay"));
    }
    @Test void builtInDenylistStillCoversBothProductionHosts() {
        assertEquals(Set.of(
                "d8c695fe7dd5f6ffc048b72c96621f7e7d13d3ec23b76aab523514183e7474d9",
                "ce1a8527392b68bab1ab6a91238a5e6909cbbb937b9eb077d1025499a59f03fe"),
                ForbiddenDbHosts.BUILT_IN_SHA256);
    }
}
