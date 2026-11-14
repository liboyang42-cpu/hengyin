package com.chengyinhub.business.begapmisc;

import com.chengyinhub.business.begapmisc.home.NativeHomePolicy;
import com.chengyinhub.business.begapmisc.home.NativeHomeReturnService;
import com.chengyinhub.business.begapmisc.home.NativeHomeService;
import com.chengyinhub.business.begapmisc.usapple.UsAppleAuthService;
import com.chengyinhub.business.begapmisc.usapple.UsAppleKeySource;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * be-gap-misc new tables on real MySQL 8: {@code migration_20261119_be_gap_misc.sql} applied twice, then the home, home-return and
 * US Apple services against those tables (run runtime and member service mocked; they own other tables).
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_R2_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BeGapMiscMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("be_gap_misc_it");
    private static final String MIGRATION = "sql/migration_20261119_be_gap_misc.sql";
    private static final List<String> TABLES = List.of("native_app_attest_guard", "native_app_attest_key", "native_app_attest_challenge",
            "native_private_home", "native_private_home_mutation", "native_home_return_check", "native_home_restore_receipt",
            "us_apple_challenge", "us_apple_account");
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void reset() throws Exception {
        for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES")) jdbc.execute("DROP TABLE `" + t.values().iterator().next() + "`");
        migrate();
        migrate(); // rerunnable
    }

    @Test
    void migrationCreatesNineInnoDbTablesWithTheirKeysAndChecks() {
        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND engine = 'InnoDB' AND table_name IN ('" + String.join("','", TABLES) + "')", Integer.class);
        assertEquals(9, tables);
        Integer checks = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE()"
                + " AND constraint_type = 'CHECK' AND constraint_name IN ('chk_native_attest_guard','chk_native_attest_key',"
                + "'chk_native_attest_challenge','chk_native_private_home')", Integer.class);
        assertEquals(4, checks);
        assertEquals(List.of("realm", "apple_sub"), jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE"
                + " table_schema = DATABASE() AND table_name = 'us_apple_account' AND index_name = 'uk_us_apple_account_sub'"
                + " ORDER BY seq_in_index", String.class));
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO native_private_home"
                + " (member_id, version, status, label, latitude, longitude, datum, updated_at) VALUES (1, 1, 'ACTIVE', NULL, 1, 1, 'WGS84', 1)"),
                "an ACTIVE home needs every field");
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO native_private_home"
                + " (member_id, version, status, latitude, updated_at) VALUES (2, 1, 'DELETED', 1, 1)"), "a deleted home keeps no point");
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO native_app_attest_challenge (challenge_id, member_id,"
                + " key_id, provider, app_id, environment, nonce, issued_at, expires_at) VALUES (REPEAT('a',64), 1, REPEAT('A',44),"
                + " 'OTHER', 'x', 'production', X'00', 1, 2)"), "provider is fixed");
    }

    @Test
    void homeAndReturnRoundTripOnMysql() {
        NativeHomePolicy policy = new NativeHomePolicy(true, true, "hr-it-1", 86400, 0, 150, 100, 120, 0, 120, 21600);
        NativeHomeService homes = new NativeHomeService(jdbc, policy);
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        TopicRouteRuntimeServiceImpl runtime = mock(TopicRouteRuntimeServiceImpl.class);
        TopicRouteSession session = new TopicRouteSession();
        session.setId(41L); session.setMemberId(9009L); session.setStatus(0); session.setVersion(3);
        when(routes.selectAccessibleByIdForUpdate(41L, 9009L)).thenReturn(session);
        long[] hp = {2, 9};
        when(runtime.homeReturnHp(any())).thenAnswer(i -> hp.clone());
        when(runtime.restoreHomeReturnHp(any())).thenAnswer(i -> { session.setVersion(session.getVersion() + 1); hp[0] = hp[1]; return true; });
        NativeHomeReturnService returns = new NativeHomeReturnService(jdbc, policy, homes, routes, runtime);

        Map<String, Object> saved = homes.set(9009L, "{\"datum\":\"WGS84\",\"expectedVersion\":0,\"label\":\"家\",\"latitude\":31.230416,"
                + "\"longitude\":121.473701,\"requestId\":\"it-h-1\"}");
        assertEquals("SAVED", saved.get("decision"));
        assertEquals("31.230416", String.valueOf(homes.snapshot(9009L).get("latitude")));
        long now = System.currentTimeMillis();
        Map<String, Object> v = returns.verify(9009L, "{\"accuracyMeters\":12.5,\"capturedAtMillis\":" + now + ",\"datum\":\"WGS84\","
                + "\"expectedAccountVersion\":3,\"homeVersion\":1,\"latitude\":31.2305,\"longitude\":121.4737,\"policyVersion\":\"hr-it-1\","
                + "\"requestId\":\"it-v-1\",\"resourceType\":\"sys.hp\",\"scopeId\":\"41\",\"scopeType\":\"RUN\"}");
        assertEquals("VERIFIED", v.get("decision"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM native_home_return_check WHERE response_json LIKE '%121.4737%'", Integer.class),
                "the device fix is not stored");
        String restore = "{\"expectedAccountVersion\":3,\"homeVersion\":1,\"proofId\":\"" + v.get("proofId") + "\",\"requestId\":\"it-r-1\","
                + "\"resourceType\":\"sys.hp\",\"scopeId\":\"41\",\"scopeType\":\"RUN\"}";
        Map<String, Object> r = returns.restore(9009L, restore);
        assertEquals("RESTORED", r.get("decision"));
        assertEquals(9L, ((Number) r.get("current")).longValue());
        assertEquals(4L, ((Number) r.get("accountVersion")).longValue());
        assertEquals(r, returns.restore(9009L, restore));
        assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM native_home_return_check WHERE proof_id = ?", Long.class, v.get("proofId")));
        assertThrows(DuplicateKeyException.class, () -> jdbc.update("INSERT INTO native_home_return_check (member_id, request_id, payload_digest,"
                + " decision, run_id, home_version, proof_id, response_json, created_at) VALUES (1, 'x', REPEAT('0',64), 'VERIFIED', 1, 1, ?, '{}', 1)",
                v.get("proofId")), "a proof id is global");
        assertEquals("DELETED", homes.delete(9009L, "{\"expectedVersion\":1,\"requestId\":\"it-d-1\"}").get("decision"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM native_private_home WHERE latitude IS NOT NULL", Integer.class));
    }

    @Test
    void usAppleChallengeAndExchangeOnMysql() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair apple = generator.generateKeyPair();
        UsAppleKeySource keys = new UsAppleKeySource("https://invalid.example/keys") {
            @Override public PublicKey key(String kid) { return apple.getPublic(); }
        };
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember created = new UmsMember();
        when(members.insertUmsMember(any())).thenAnswer(i -> { UmsMember m = i.getArgument(0); m.setId(30001L); created.setId(30001L); return 1; });
        when(members.selectUmsMemberById(30001L)).thenAnswer(i -> { UmsMember m = new UmsMember(); m.setId(30001L); m.setNickname("Questify Player");
            m.setRole("player"); m.setUserType(1L); m.setStatus(1L); m.setDelFlag(0); return m; });
        UsAppleAuthService service = new UsAppleAuthService(jdbc, members, keys, true, "us-it", "com.example.us");
        Map<String, Object> c = service.challenge().data;
        String token = Jwts.builder().setHeaderParam("kid", "k").setIssuer("https://appleid.apple.com").setAudience("com.example.us")
                .setSubject("apple-it-sub").claim("nonce", sha((String) c.get("rawNonce")))
                .setExpiration(new Date(System.currentTimeMillis() + 60_000)).signWith(SignatureAlgorithm.RS256, apple.getPrivate()).compact();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", c.get("challengeId"));
        body.put("state", c.get("state"));
        body.put("identityToken", token);
        UsAppleAuthService.Outcome ok = service.exchange(body);
        assertEquals(200, ok.status, String.valueOf(ok.errorCode));
        assertEquals(30001L, ok.memberId);
        assertEquals(401, service.exchange(body).status, "consumed");
        assertEquals(200, service.session(30001L).status);
        assertThrows(DuplicateKeyException.class, () -> jdbc.update("INSERT INTO us_apple_account (member_id, realm, apple_sub, created_at)"
                + " VALUES (30002, 'us-it', 'apple-it-sub', 1)"), "one account per sub and realm");
        assertEquals(1, jdbc.update("INSERT INTO us_apple_account (member_id, realm, apple_sub, created_at) VALUES (30003, 'us-other', 'apple-it-sub', 1)"));
    }

    private static String sha(String text) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    private void migrate() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : Files.readString(Paths.get(MIGRATION)).split(";\\s*\\n")) {
                String sql = statement.replaceAll("(?m)^--.*$", "").trim();
                if (!sql.isEmpty()) s.execute(sql);
            }
        }
    }

    private DriverManagerDataSource ds(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
