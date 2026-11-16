package com.chengyinhub.business.externalgame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.chengyinhub.business.appgaps.AppGapException;

/** W7 GAME-9: creator-issued per-game result keys: issue once, verify, rotate, revoke. */
class ExternalGameResultKeyTest {

    private static final long CREATOR = 41L;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:extgame_key_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE external_game (game_id VARCHAR(64) PRIMARY KEY, owner_member_id BIGINT, approved_submission_id BIGINT)");
        jdbc.execute("CREATE TABLE external_game_result_key (game_id VARCHAR(64) PRIMARY KEY, key_cipher VARBINARY(256) NOT NULL,"
                + " key_hint CHAR(8) NOT NULL, created_at TIMESTAMP NOT NULL, revoked_at TIMESTAMP NULL, revoked_by VARCHAR(64) NULL)");
        jdbc.update("INSERT INTO external_game VALUES('g.ok', ?, 7)", CREATOR);
        jdbc.update("INSERT INTO external_game VALUES('g.draft', ?, NULL)", CREATOR);
    }

    private ExternalGameMonetizationService verifier(ExternalGameResultKeyService keys) {
        ExternalGameMonetizationService svc = new ExternalGameMonetizationService(jdbc, tx, null, null, null, null,
                () -> 0L, null, false);
        svc.setResultKeys(keys);
        return svc;
    }

    @Test
    void issueVerifyRotateAndRevoke() {
        ExternalGameResultKeyService keys = new ExternalGameResultKeyService(jdbc, "wrap-secret-for-tests");
        Map<String, Object> issued = keys.issue(CREATOR, "g.ok");
        String key = (String) issued.get("resultKey");
        assertEquals(64, key.length());
        assertEquals(true, issued.get("shownOnce"));
        // Only ciphertext and a hint are stored.
        byte[] stored = jdbc.queryForObject("SELECT key_cipher FROM external_game_result_key WHERE game_id='g.ok'", byte[].class);
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains(key));
        assertEquals(ExternalGameResultKeyService.hint(key), issued.get("keyHint"));
        assertFalse(keys.status("g.ok").containsKey("resultKey"));

        ExternalGameMonetizationService svc = verifier(keys);
        String canonical = "EXTGAME_RESULT|s1|g.ok|n|1|100|60000|d1";
        assertTrue(svc.verifyAuthoritySignature("g.ok", canonical, ExternalGameMonetizationService.hmacSha256Hex(key, canonical)));
        assertFalse(svc.verifyAuthoritySignature("g.ok", canonical, ExternalGameMonetizationService.hmacSha256Hex("other", canonical)));

        // Rotate: the old key stops working, the new one works.
        String rotated = (String) keys.issue(CREATOR, "g.ok").get("resultKey");
        assertNotEquals(key, rotated);
        assertFalse(svc.verifyAuthoritySignature("g.ok", canonical, ExternalGameMonetizationService.hmacSha256Hex(key, canonical)));
        assertTrue(svc.verifyAuthoritySignature("g.ok", canonical, ExternalGameMonetizationService.hmacSha256Hex(rotated, canonical)));

        // Revoke: refused at once.
        assertTrue(keys.revoke("g.ok", "ops"));
        assertNull(keys.activeKey("g.ok"));
        assertFalse(svc.verifyAuthoritySignature("g.ok", canonical, ExternalGameMonetizationService.hmacSha256Hex(rotated, canonical)));
        assertEquals(false, keys.status("g.ok").get("active"));
    }

    @Test
    void onlyTheCreatorOfAnApprovedGameGetsAKey() {
        ExternalGameResultKeyService keys = new ExternalGameResultKeyService(jdbc, "wrap-secret-for-tests");
        assertEquals("NOT_GAME_OWNER", assertThrows(AppGapException.class, () -> keys.issue(99L, "g.ok")).getErrorCode());
        assertEquals("GAME_NOT_APPROVED", assertThrows(AppGapException.class, () -> keys.issue(CREATOR, "g.draft")).getErrorCode());
        assertEquals("GAME_NOT_FOUND", assertThrows(AppGapException.class, () -> keys.issue(CREATOR, "g.none")).getErrorCode());
    }

    @Test
    void withoutTheWrapSecretNothingIsIssuedOrVerified() {
        ExternalGameResultKeyService keys = new ExternalGameResultKeyService(jdbc, " ");
        assertFalse(keys.configured());
        assertEquals("RESULT_KEY_NOT_CONFIGURED", assertThrows(AppGapException.class, () -> keys.issue(CREATOR, "g.ok")).getErrorCode());
        assertNull(keys.activeKey("g.ok"));
        // A key stored under one wrap secret is unreadable under another: no false positive.
        ExternalGameResultKeyService a = new ExternalGameResultKeyService(jdbc, "wrap-a");
        a.issue(CREATOR, "g.ok");
        assertNull(new ExternalGameResultKeyService(jdbc, "wrap-b").activeKey("g.ok"));
    }

    @Test
    void migrationHoldsNoPersonalDataAndGrantsNoRole() throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        if (!Files.exists(base.resolve("chengyinhub-admin"))) base = base.getParent();
        String sql = Files.readString(base.resolve("chengyinhub-admin/sql/migration_20261116_external_game_result_key.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS external_game_result_key"));
        assertFalse(sql.contains("member_id BIGINT"), "no member id column");
        assertFalse(sql.contains("sys_role_menu"));
        assertTrue(sql.contains("'business:externalgame:resultkey'"));
    }
}
