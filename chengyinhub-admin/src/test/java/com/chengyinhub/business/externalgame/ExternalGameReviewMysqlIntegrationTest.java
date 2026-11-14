package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * External game review state machine on real MySQL 8 with the real migration: submit → PENDING → approve / reject /
 * escalate / cancel, review timeout → ESCALATED, resubmit after rejection, catalog + signed download, reports, and the
 * CHECK / UNIQUE guards behind them.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_R2_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalGameReviewMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("external_game_review_it");
    private static final long AUTHOR = 7, OTHER = 8, PLAYER = 9, ADMIN = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ExternalGameSigner signer;
    private ExternalGameReviewService service;
    private final AtomicLong clock = new AtomicLong(java.sql.Timestamp.valueOf("2026-11-16 10:00:00").getTime());

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        jdbc = new JdbcTemplate(dataSource);
        String key = java.util.Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
        signer = new ExternalGameSigner(key, "it-1");
        service = new ExternalGameReviewService(jdbc, new DataSourceTransactionManager(dataSource), signer, 3, 600, clock::get);
    }

    @BeforeEach
    void reset() throws Exception {
        for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES")) jdbc.execute("DROP TABLE `" + t.values().iterator().next() + "`");
        migrate("sql/migration_20261116_external_game_review.sql");
        migrate("sql/migration_20261116_external_game_review.sql"); // rerunnable
        clock.set(java.sql.Timestamp.valueOf("2026-11-16 10:00:00").getTime());
    }

    @Test
    void submitIsPendingServerSignedAndIdempotent() {
        Map<String, Object> s = service.submit(AUTHOR, "req-00000001", pkg("city.quiz", "1.0.0", "<p>v1</p>"), null);
        assertEquals("PENDING", s.get("status"));
        assertEquals("NONE", s.get("monetization"));
        assertEquals("Ed25519", s.get("signatureAlgorithm"));
        assertEquals("2026-11-19T10:00:00.000+08:00", s.get("reviewDueAt"));
        String sig = (String) s.get("signature");
        assertNotEquals("client", sig);
        ExternalGamePackageValidator.Validated again = pkg("city.quiz", "1.0.0", "<p>v1</p>");
        assertTrue(signer.verifyHex(ExternalGamePackageValidator.canonicalSigningPayload(again.manifest()), sig), "server signature verifies with the public key");
        Map<String, Object> replay = service.submit(AUTHOR, "req-00000001", again, null);
        assertEquals(s.get("submissionId"), replay.get("submissionId"));
        assertEquals(true, replay.get("replayed"));
        assertEquals("REQUEST_ID_REUSED", err(() -> service.submit(AUTHOR, "req-00000001", pkg("city.quiz", "1.0.1", "<p>x</p>"), null)));
        assertEquals("SUBMISSION_ALREADY_OPEN", err(() -> service.submit(AUTHOR, null, pkg("city.quiz", "1.0.1", "<p>x</p>"), null)));
        assertEquals("GAME_ID_TAKEN", err(() -> service.submit(OTHER, null, pkg("city.quiz", "9.0.0", "<p>x</p>"), null)));
        assertEquals(401, assertThrows(AppGapException.class, () -> service.submit(null, null, again, null)).getStatus());
        assertEquals(1, count("external_game_review_event"));
        assertEquals("SIGNING_UNCONFIGURED", err(() -> new ExternalGameReviewService(jdbc, new DataSourceTransactionManager(dataSource),
                new ExternalGameSigner("", "x"), 3, 600, clock::get).submit(AUTHOR, null, pkg("other.game", "1.0.0", "<p/>"), null)));
    }

    @Test
    void approveRejectEscalateAndCancelFollowTheMachine() {
        long a = id(service.submit(AUTHOR, null, pkg("game.aaa", "1.0.0", "<p>a</p>"), null));
        long b = id(service.submit(AUTHOR, null, pkg("game.bbb", "1.0.0", "<p>b</p>"), null));
        long c = id(service.submit(AUTHOR, null, pkg("game.ccc", "1.0.0", "<p>c</p>"), null));
        long d = id(service.submit(AUTHOR, null, pkg("game.ddd", "1.0.0", "<p>d</p>"), null));

        assertEquals("APPROVED", service.approve(ADMIN, String.valueOf(a), Map.of("note", "ok")).get("status"));
        assertEquals("INVALID_TRANSITION", err(() -> service.reject(ADMIN, String.valueOf(a), Map.of("reason", "late"))));
        assertEquals("BAD_REQUEST", err(() -> service.reject(ADMIN, String.valueOf(b), Map.of())), "reject needs a reason");
        Map<String, Object> rejected = service.reject(ADMIN, String.valueOf(b), Map.of("reason", "入口页空白"));
        assertEquals("REJECTED", rejected.get("status"));
        assertEquals("入口页空白", service.mineDetail(AUTHOR, String.valueOf(b)).get("rejectReason"));

        assertEquals("ESCALATED", service.escalate(ADMIN, String.valueOf(c), Map.of("reason", "需要法务看版权")).get("status"));
        assertEquals("INVALID_TRANSITION", err(() -> service.escalate(ADMIN, String.valueOf(c), Map.of("reason", "again"))));
        assertEquals("APPROVED", service.approve(ADMIN, String.valueOf(c), null).get("status"), "escalated can still be decided");

        assertEquals("SUBMISSION_NOT_FOUND", err(() -> service.cancel(OTHER, String.valueOf(d), null)), "only the author cancels");
        assertEquals("CANCELLED", service.cancel(AUTHOR, String.valueOf(d), null).get("status"));
        assertEquals("CANCELLED", service.cancel(AUTHOR, String.valueOf(d), null).get("status"), "replay-safe");
        assertEquals("INVALID_TRANSITION", err(() -> service.approve(ADMIN, String.valueOf(d), null)));
        assertEquals("INVALID_TRANSITION", err(() -> service.cancel(AUTHOR, String.valueOf(a), null)));

        // Every transition is audited with its actor.
        List<Map<String, Object>> events = jdbc.queryForList("SELECT submission_id, from_status, to_status, actor_type, actor_id FROM external_game_review_event ORDER BY id");
        assertEquals(4 + 1 + 1 + 2 + 1, events.size());
        assertEquals(List.of("ADMIN", "ADMIN", "ADMIN", "ADMIN", "AUTHOR"), events.subList(4, 9).stream().map(e -> e.get("actor_type")).toList());
        List<?> timeline = (List<?>) service.adminDetail(String.valueOf(c)).get("timeline");
        assertEquals(3, timeline.size());
        assertEquals("ESCALATED", ((Map<?, ?>) timeline.get(1)).get("toStatus"));
        assertEquals(ADMIN, ((Map<?, ?>) timeline.get(1)).get("actorId"));
        assertFalse(((Map<?, ?>) ((List<?>) service.mineDetail(AUTHOR, String.valueOf(c)).get("timeline")).get(1)).containsKey("actorId"), "author never sees reviewer ids");
    }

    @Test
    void reviewTimeoutMovesPendingToEscalatedAndSurfacesItFirst() {
        long old = id(service.submit(AUTHOR, null, pkg("game.old", "1.0.0", "<p>o</p>"), null));
        clock.addAndGet(2 * 86_400_000L);
        long fresh = id(service.submit(AUTHOR, null, pkg("game.new", "1.0.0", "<p>n</p>"), null));
        assertEquals(0, service.sweepTimeouts(), "2 days < 3 days");
        clock.addAndGet(86_400_000L + 1);
        assertEquals(1, service.sweepTimeouts());
        assertEquals(0, service.sweepTimeouts(), "idempotent");
        Map<String, Object> row = jdbc.queryForMap("SELECT status, escalation_source, escalation_reason FROM external_game_submission WHERE id = ?", old);
        assertEquals("ESCALATED", row.get("status"));
        assertEquals("TIMEOUT", row.get("escalation_source"));
        assertEquals("REVIEW_TIMEOUT", row.get("escalation_reason"));
        Map<String, Object> ev = jdbc.queryForMap("SELECT actor_type, actor_id, from_status FROM external_game_review_event WHERE submission_id = ? AND to_status = 'ESCALATED'", old);
        assertEquals("SYSTEM", ev.get("actor_type"));
        assertNull(ev.get("actor_id"));
        assertEquals("PENDING", ev.get("from_status"));

        clock.addAndGet(2 * 86_400_000L);
        Map<String, Object> queue = service.adminQueue(null, 1, 20); // sweeps first
        List<?> rows = (List<?>) queue.get("rows");
        assertEquals(2, rows.size());
        assertEquals(old, ((Map<?, ?>) rows.get(0)).get("submissionId"));
        assertEquals("ESCALATED", ((Map<?, ?>) rows.get(1)).get("status"), "the queue read escalated the second one too");
        assertEquals(fresh, ((Map<?, ?>) rows.get(1)).get("submissionId"));
        assertEquals(2L, ((Map<?, ?>) queue.get("counts")).get("ESCALATED"));
        assertEquals(0L, ((Map<?, ?>) queue.get("counts")).get("PENDING"));
        assertEquals("APPROVED", service.approve(ADMIN, String.valueOf(old), null).get("status"));
    }

    @Test
    void resubmitAfterRejectionNeedsAHigherVersionAndLinksBack() {
        long first = id(service.submit(AUTHOR, null, pkg("game.re", "1.0.0", "<p>1</p>"), null));
        assertEquals("RESUBMIT_NOT_ALLOWED", err(() -> service.submit(AUTHOR, null, pkg("game.re", "1.0.1", "<p>2</p>"), first)));
        service.reject(ADMIN, String.valueOf(first), Map.of("reason", "加载失败"));
        assertEquals("VERSION_NOT_INCREASED", err(() -> service.submit(AUTHOR, null, pkg("game.re", "1.0.0", "<p>2</p>"), first)));
        assertEquals("GAME_ID_MISMATCH", err(() -> service.submit(AUTHOR, null, pkg("game.zz", "1.0.1", "<p>2</p>"), first)));
        assertEquals("GAME_ID_TAKEN", err(() -> service.submit(OTHER, null, pkg("game.re", "1.0.1", "<p>2</p>"), first)));
        Map<String, Object> second = service.submit(AUTHOR, null, pkg("game.re", "1.0.1", "<p>2</p>"), first);
        assertEquals("PENDING", second.get("status"));
        assertEquals(first, second.get("previousSubmissionId"));
        Map<String, Object> mine = service.mine(AUTHOR, null, null, "10");
        assertEquals(2, ((List<?>) mine.get("items")).size());
        assertEquals(1, ((List<?>) service.mine(AUTHOR, "REJECTED", null, null).get("items")).size());
        assertEquals(0, ((List<?>) service.mine(OTHER, null, null, null).get("items")).size());
    }

    @Test
    void catalogListsOnlyTheApprovedVersionAndDownloadsAreSignedAndExpire() throws Exception {
        long v1 = id(service.submit(AUTHOR, null, pkg("game.cat", "1.0.0", "<p>1</p>"), null));
        id(service.submit(AUTHOR, null, pkg("game.hidden", "1.0.0", "<p>h</p>"), null));
        assertEquals(0, ((List<?>) service.catalog(null, null).get("items")).size());
        service.approve(ADMIN, String.valueOf(v1), null);
        List<?> items = (List<?>) service.catalog(null, null).get("items");
        assertEquals(1, items.size());
        assertEquals("game.cat", ((Map<?, ?>) items.get(0)).get("gameId"));
        assertEquals("GAME_NOT_FOUND", err(() -> service.catalogDetail("game.hidden")));

        Map<String, Object> detail = service.catalogDetail("game.cat");
        Map<String, String> q = query((String) detail.get("downloadPath"));
        byte[] body = service.catalogPackage("game.cat", q.get("submissionId"), q.get("exp"), q.get("sig"));
        JsonNode archive = JSON.readTree(body);
        assertEquals(detail.get("signature"), archive.at("/manifest/signature").asText());
        assertEquals("NONE", archive.at("/manifest/monetization").asText());
        ExternalGamePackageValidator.Validated roundTrip = ExternalGamePackageValidator.fromArchiveJson(body, null);
        assertTrue(signer.verifyHex(ExternalGamePackageValidator.canonicalSigningPayload(roundTrip.manifest()), archive.at("/manifest/signature").asText()));

        assertEquals("DOWNLOAD_LINK_INVALID", err(() -> service.catalogPackage("game.cat", q.get("submissionId"), q.get("exp"), q.get("sig").replace('A', 'B').replace('a', 'A'))));
        assertEquals("DOWNLOAD_LINK_INVALID", err(() -> service.catalogPackage("game.cat", q.get("submissionId"), String.valueOf(Long.parseLong(q.get("exp")) + 1), q.get("sig"))));
        clock.addAndGet(601_000);
        assertEquals("DOWNLOAD_LINK_EXPIRED", err(() -> service.catalogPackage("game.cat", q.get("submissionId"), q.get("exp"), q.get("sig"))));

        // A newer approved version supersedes the old link.
        Map<String, String> fresh = query((String) service.catalogDetail("game.cat").get("downloadPath"));
        long v2 = id(service.submit(AUTHOR, null, pkg("game.cat", "1.1.0", "<p>2</p>"), null));
        service.approve(ADMIN, String.valueOf(v2), null);
        assertEquals("PACKAGE_SUPERSEDED", err(() -> service.catalogPackage("game.cat", fresh.get("submissionId"), fresh.get("exp"), fresh.get("sig"))));
        assertEquals("1.1.0", service.catalogDetail("game.cat").get("version"));
    }

    @Test
    void reportsAreIdempotentOnePerOpenGameAndAcceptSideLoadedPackages() {
        long v1 = id(service.submit(AUTHOR, null, pkg("game.rep", "1.0.0", "<p>r</p>"), null));
        service.approve(ADMIN, String.valueOf(v1), null);
        Map<String, Object> r = service.report(PLAYER, Map.of("requestId", "rep-00000001", "gameId", "game.rep", "reasonCode", "MALICIOUS_CODE", "blocked", true));
        assertEquals("OPEN", r.get("status"));
        assertEquals(v1, r.get("submissionId"));
        assertEquals(true, r.get("blocked"));
        assertFalse(r.containsKey("reporterMemberId"));
        assertEquals(r.get("reportId"), service.report(PLAYER, Map.of("requestId", "rep-00000001", "gameId", "game.rep", "reasonCode", "MALICIOUS_CODE", "blocked", true)).get("reportId"));
        assertEquals("REQUEST_ID_REUSED", err(() -> service.report(PLAYER, Map.of("requestId", "rep-00000001", "gameId", "game.rep", "reasonCode", "BROKEN"))));
        assertEquals("REPORT_ALREADY_OPEN", err(() -> service.report(PLAYER, Map.of("requestId", "rep-00000002", "gameId", "game.rep", "reasonCode", "BROKEN"))));
        assertEquals("BAD_REQUEST", err(() -> service.report(PLAYER, Map.of("requestId", "rep-00000003", "gameId", "side.loaded", "reasonCode", "OTHER"))), "OTHER needs detail");
        Map<String, Object> side = service.report(PLAYER, Map.of("requestId", "rep-00000003", "gameId", "side.loaded", "version", "0.1.0",
                "sha256", "a".repeat(64), "reasonCode", "OTHER", "detail", "弹出奇怪链接"));
        assertEquals(false, side.get("knownToCatalog"));
        assertEquals(2L, service.adminReports(null, "OPEN", 1, 20).get("total"));
        assertEquals(1L, ((Map<?, ?>) ((List<?>) service.adminQueue("APPROVED", 1, 20).get("rows")).get(0)).get("openReports"));
    }

    @Test
    void databaseGuardsBackTheService() {
        long a = id(service.submit(AUTHOR, null, pkg("game.db", "1.0.0", "<p>a</p>"), null));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE external_game_submission SET monetization = 'IAA' WHERE id = ?", a));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE external_game_submission SET status = 'REJECTED' WHERE id = ?", a));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE external_game_submission SET status = 'DONE' WHERE id = ?", a));
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO external_game_submission(game_id, author_member_id, version, name,"
                + " content_rating, entry, permissions_json, allowed_domains_json, package_sha256, package_bytes, file_count, signature, signature_algorithm,"
                + " signing_key_id, status, request_hash, submitted_time, update_time) SELECT game_id, author_member_id, '2.0.0', name, content_rating, entry,"
                + " permissions_json, allowed_domains_json, package_sha256, package_bytes, file_count, signature, signature_algorithm, signing_key_id,"
                + " 'PENDING', request_hash, submitted_time, update_time FROM external_game_submission WHERE id = " + a), "one open submission per game");
    }

    // ---- helpers ----

    private static ExternalGamePackageValidator.Validated pkg(String id, String version, String html) {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("index.html", ("<!doctype html>" + html).getBytes(StandardCharsets.UTF_8));
        files.put("app.js", "console.log('ok')".getBytes(StandardCharsets.UTF_8));
        ObjectNode m = JSON.createObjectNode();
        m.put("id", id); m.put("name", "游戏 " + id); m.put("version", version); m.put("entry", "index.html");
        m.putArray("permissions").add("storage");
        m.put("contentRating", "4+");
        m.putArray("allowedDomains");
        m.put("sha256", ExternalGamePackageValidator.packageSha256(files));
        m.put("signature", "client");
        m.put("author", "Ada");
        return ExternalGamePackageValidator.validate(m, files, "NONE");
    }

    private static long id(Map<String, Object> view) { return ((Number) view.get("submissionId")).longValue(); }

    private static String err(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(AppGapException.class, call).getErrorCode();
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class); }

    private static Map<String, String> query(String path) {
        Map<String, String> out = new java.util.HashMap<>();
        for (String kv : path.substring(path.indexOf('?') + 1).split("&")) out.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
        return out;
    }

    private void migrate(String path) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : Files.readString(Paths.get(path)).split(";\\s*\\n")) {
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
