package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.begapmisc.MiscMoneySwitch;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end MySQL 8 integration test for external game monetization:
 * paid pricing + entitlement + split behind MiscMoneySwitch, node binding + signed result callback -> XP/medals/node,
 * traffic anti-fraud caps, and daily settlement into creator points or TEST earnings.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_R2_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalGameMonetizationMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("external_game_money_it");
    private static final long CREATOR = 7, PLAYER1 = 9, PLAYER2 = 10, ADMIN = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESULT_SECRET = "it-result-secret";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager txManager;
    private ExternalGameSigner signer;
    private ExternalGameReviewService reviews;
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
        txManager = new DataSourceTransactionManager(dataSource);
        String key = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
        signer = new ExternalGameSigner(key, "it-m1");
        reviews = new ExternalGameReviewService(jdbc, txManager, signer, 3, 600, clock::get);
    }

    @BeforeEach
    void reset() throws Exception {
        for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES")) {
            jdbc.execute("DROP TABLE `" + t.values().iterator().next() + "`");
        }
        migrate("sql/migration_20261116_external_game_review.sql");
        migrate("sql/migration_20261115_external_game_monetization.sql");
        migrate("sql/migration_20261115_external_game_monetization.sql");
        clock.set(java.sql.Timestamp.valueOf("2026-11-16 10:00:00").getTime());
    }

    @Test
    void paidPricingMoneyGateEntitlementAndSplit() throws Exception {
        approveGame("city.paid");

        Map<Long, Long> testBalances = new HashMap<>();
        ExternalGameWalletBridge wallet = new ExternalGameWalletBridge() {
            @Override public void creditPoints(long memberId, int points, String businessKey, String reason) {}
            @Override public void creditBalanceFen(long memberId, long fen, String businessKey, String reason) {
                testBalances.merge(memberId, fen, Long::sum);
            }
            @Override public void debitBalanceFen(long memberId, long fen, String businessKey, String reason) {
                testBalances.merge(memberId, -fen, Long::sum);
            }
        };

        AtomicBoolean paySwitch = new AtomicBoolean(false);
        MiscMoneySwitch toggleSwitch = new MiscMoneySwitch(false, false) {
            @Override public boolean paymentsOpen() { return paySwitch.get(); }
            @Override public boolean payoutOpen() { return paySwitch.get(); }
        };

        ExternalGameMonetizationService svc = new ExternalGameMonetizationService(
                jdbc, txManager, signer, toggleSwitch, wallet, null, clock::get, RESULT_SECRET, true);

        // Creator sets paid price after game is approved -> PENDING review
        Map<String, Object> saved = svc.saveCreatorConfig(CREATOR, "city.paid", Map.of(
                "pricingMode", "PAID",
                "priceFen", 2000,
                "trafficRewardMode", "NONE",
                "channelFeeBearer", 0,
                "requestId", "cfg-00000001"));
        assertEquals("PENDING", saved.get("reviewStatus"));
        assertEquals(2000L, ((Number) saved.get("priceFen")).longValue());

        // Admin approves with 75% creator share, platform bears channel fee
        Map<String, Object> reviewed = svc.adminReviewConfig(ADMIN, "city.paid", Map.of(
                "decision", "APPROVED",
                "creatorShareBps", 7500,
                "channelFeeBearer", 1));
        assertEquals("APPROVED", reviewed.get("reviewStatus"));

        // Non-owner without entitlement cannot download paid game
        AppGapException noEnt = assertThrows(AppGapException.class, () -> svc.requireDownloadOrPlayAccess(PLAYER1, "city.paid"));
        assertEquals("ENTITLEMENT_REQUIRED", noEnt.getErrorCode());

        // Money gate OFF blocks paid purchase with 403 FEATURE_CLOSED
        AppGapException closed = assertThrows(AppGapException.class, () -> svc.purchaseGame(PLAYER1, "city.paid", Map.of(
                "requestId", "ord-00000001", "provider", "TEST_WALLET")));
        assertEquals(403, closed.getStatus());
        assertEquals("FEATURE_CLOSED", closed.getErrorCode());
        assertTrue(testBalances.isEmpty());

        // Turn TEST money gate ON -> purchase succeeds, idempotent, unlocks entitlement, credits creator TEST balance
        paySwitch.set(true);
        Map<String, Object> order = svc.purchaseGame(PLAYER1, "city.paid", Map.of(
                "requestId", "ord-00000001", "provider", "TEST_WALLET"));
        assertEquals("PAID", order.get("state"));
        assertEquals("ACTIVE", order.get("licenseStatus"));
        assertEquals(1500L, ((Number) order.get("creatorNetFen")).longValue());
        assertEquals(1500L, testBalances.get(CREATOR));
        assertEquals(-2000L, testBalances.get(PLAYER1)); // HIGH-S2: paired buyer debit

        Map<String, Object> replay = svc.purchaseGame(PLAYER1, "city.paid", Map.of(
                "requestId", "ord-00000001", "provider", "TEST_WALLET"));
        assertEquals(order.get("orderNo"), replay.get("orderNo"));
        assertEquals(true, replay.get("replayed"));

        // Creator cannot buy own game
        AppGapException own = assertThrows(AppGapException.class, () -> svc.purchaseGame(CREATOR, "city.paid", Map.of(
                "requestId", "ord-own-0001", "provider", "TEST_WALLET")));
        assertEquals("CANNOT_PURCHASE_OWN_GAME", own.getErrorCode());

        // Player1 now has access and entitlement list entry
        svc.requireDownloadOrPlayAccess(PLAYER1, "city.paid");
        List<?> entItems = (List<?>) svc.myEntitlements(PLAYER1).get("items");
        assertEquals(1, entItems.size());
    }

    @Test
    void nodeBindingAndSignedResultCallbackUpdatesXpMedalsAndNode() throws Exception {
        approveGame("city.node");

        AtomicInteger completedNodes = new AtomicInteger();
        ExternalGameNodeBridge nodeBridge = new ExternalGameNodeBridge() {
            @Override public NodeOwnerView requireOwnedNode(long creatorMemberId, long nodeId) {
                if (creatorMemberId != CREATOR) throw new AppGapException(403, "FORBIDDEN", "not owner");
                return new NodeOwnerView(nodeId, 88L);
            }

            @Override public NodePlayOutcome onGameResult(GameResult r) {
                if (r.nodeId() != null && r.passed() && r.attested()) {
                    completedNodes.incrementAndGet();
                    return new NodePlayOutcome(35, 1, true);
                }
                return new NodePlayOutcome(0, null, false);
            }
        };

        ExternalGameMonetizationService svc = new ExternalGameMonetizationService(
                jdbc, txManager, signer, new MiscMoneySwitch(false, false), null, nodeBridge, clock::get, RESULT_SECRET, false);

        // Non-owner cannot bind node
        assertEquals(403, assertThrows(AppGapException.class, () -> svc.bindNode(PLAYER1, 501L, Map.of(
                "gameId", "city.node", "minScore", 60, "requirePass", true))).getStatus());

        Map<String, Object> bound = svc.bindNode(CREATOR, 501L, Map.of(
                "gameId", "city.node", "minScore", 60, "requirePass", true));
        assertEquals(true, bound.get("bound"));
        assertEquals(1, svc.activeBindingsByTopic(88L).size());

        // Start signed session
        Map<String, Object> ticket = svc.startSession(PLAYER1, Map.of(
                "gameId", "city.node", "nodeId", 501L, "topicId", 88L), "127.0.0.1");
        String sessionId = (String) ticket.get("sessionId");
        String nonce = (String) ticket.get("nonce");
        long issuedAt = ((Number) ticket.get("issuedAtMs")).longValue();
        String expectedStart = ExternalGameMonetizationService.canonicalStartPayload(
                sessionId, "city.node", PLAYER1, 501L, nonce, issuedAt);
        assertTrue(signer.verifyHex(expectedStart.getBytes(StandardCharsets.UTF_8), (String) ticket.get("sessionSignature")));

        // Bad signature rejected
        assertEquals("INVALID_SIGNATURE", assertThrows(AppGapException.class, () -> svc.submitResult(PLAYER1, sessionId, Map.of(
                "nonce", nonce, "passed", true, "score", 95, "durationMs", 12000, "resultDigest", "dig1",
                "resultSignature", "0000"))).getErrorCode());

        // Valid signed result below minScore (50 < 60) -> does NOT complete node
        String canonicalFail = ExternalGameMonetizationService.canonicalResultPayload(
                sessionId, "city.node", nonce, true, 50, 12000L, "dig-low");
        String macFail = authorityMac("city.node", canonicalFail);
        Map<String, Object> failRes = svc.submitResult(PLAYER1, sessionId, Map.of(
                "nonce", nonce, "passed", true, "score", 50, "durationMs", 12000,
                "resultDigest", "dig-low", "resultSignature", macFail));
        assertEquals(false, failRes.get("passed"));
        assertEquals(false, failRes.get("nodeCompleted"));
        assertEquals(0, completedNodes.get());

        // Start fresh session and pass with score 95 -> awards XP=35, medalRank=1, nodeCompleted=true
        Map<String, Object> t2 = svc.startSession(PLAYER1, Map.of("gameId", "city.node", "nodeId", 501L, "topicId", 88L), null);
        String s2 = (String) t2.get("sessionId");
        String n2 = (String) t2.get("nonce");
        String canOk = ExternalGameMonetizationService.canonicalResultPayload(s2, "city.node", n2, true, 95, 15000L, "dig-ok");
        String macOk = authorityMac("city.node", canOk);
        Map<String, Object> okRes = svc.submitResult(PLAYER1, s2, Map.of(
                "nonce", n2, "passed", true, "score", 95, "durationMs", 15000,
                "resultDigest", "dig-ok", "resultSignature", macOk));
        assertEquals(true, okRes.get("passed"));
        assertEquals(true, okRes.get("nodeCompleted"));
        assertEquals(35, okRes.get("xpAwarded"));
        assertEquals(1, okRes.get("medalRank"));
        assertEquals(1, completedNodes.get());

        // Same digest replays cleanly; different digest is rejected 409
        Map<String, Object> sameReplay = svc.submitResult(PLAYER1, s2, Map.of(
                "nonce", n2, "passed", true, "score", 95, "durationMs", 15000,
                "resultDigest", "dig-ok", "resultSignature", macOk));
        assertEquals(true, sameReplay.get("replayed"));

        String canTamper = ExternalGameMonetizationService.canonicalResultPayload(s2, "city.node", n2, true, 99, 15000L, "dig-other");
        String macTamper = authorityMac("city.node", canTamper);
        assertEquals("SESSION_ALREADY_COMPLETED", assertThrows(AppGapException.class, () -> svc.submitResult(PLAYER1, s2, Map.of(
                "nonce", n2, "passed", true, "score", 99, "durationMs", 15000,
                "resultDigest", "dig-other", "resultSignature", macTamper))).getErrorCode());
    }

    @Test
    void trafficAntiFraudAndDailySettlementPointsAndTestMoney() throws Exception {
        approveGame("city.pts");
        approveGame("city.cash");

        Map<Long, Integer> ptsWallet = new HashMap<>();
        Map<Long, Long> cashWallet = new HashMap<>();
        ExternalGameWalletBridge wallet = new ExternalGameWalletBridge() {
            @Override public void creditPoints(long memberId, int points, String businessKey, String reason) {
                ptsWallet.merge(memberId, points, Integer::sum);
            }
            @Override public void creditBalanceFen(long memberId, long fen, String businessKey, String reason) {
                cashWallet.merge(memberId, fen, Long::sum);
            }
            @Override public void debitBalanceFen(long memberId, long fen, String businessKey, String reason) {
                cashWallet.merge(memberId, -fen, Long::sum);
            }
        };

        AtomicBoolean payoutGate = new AtomicBoolean(false);
        MiscMoneySwitch sw = new MiscMoneySwitch(false, false) {
            @Override public boolean paymentsOpen() { return payoutGate.get(); }
            @Override public boolean payoutOpen() { return payoutGate.get(); }
        };

        ExternalGameMonetizationService svc = new ExternalGameMonetizationService(
                jdbc, txManager, signer, sw, wallet, null, clock::get, RESULT_SECRET, false);

        // Configure POINTS game (cap 1/user/day, 2/game/day, 10 points/play)
        svc.saveCreatorConfig(CREATOR, "city.pts", Map.of("pricingMode", "TRAFFIC_ONLY", "trafficRewardMode", "POINTS"));
        svc.adminReviewConfig(ADMIN, "city.pts", Map.of(
                "decision", "APPROVED", "trafficRewardMode", "POINTS",
                "rewardPerPlayPoints", 10, "dailyUserCap", 1, "dailyGameCap", 2, "minDurationMs", 5000));

        // Creator self-play -> SELF_PLAY
        assertEquals("SELF_PLAY", playAndSubmit(svc, CREATOR, "city.pts", true, 80, 9000).get("fraudReason"));
        // Short unpassed play -> DURATION_TOO_SHORT
        assertEquals("DURATION_TOO_SHORT", playAndSubmit(svc, PLAYER1, "city.pts", false, 10, 1000).get("fraudReason"));
        // Valid play 1 by PLAYER1 -> eligible
        assertEquals(true, playAndSubmit(svc, PLAYER1, "city.pts", true, 90, 9000).get("trafficEligible"));
        // 2nd play by PLAYER1 hits USER_DAILY_CAP
        assertEquals("USER_DAILY_CAP", playAndSubmit(svc, PLAYER1, "city.pts", true, 95, 9000).get("fraudReason"));
        // Valid play 2 by PLAYER2 -> eligible
        assertEquals(true, playAndSubmit(svc, PLAYER2, "city.pts", true, 88, 9000).get("trafficEligible"));

        // Configure TEST_MONEY game (15 fen/play)
        svc.saveCreatorConfig(CREATOR, "city.cash", Map.of("pricingMode", "TRAFFIC_ONLY", "trafficRewardMode", "TEST_MONEY"));
        svc.adminReviewConfig(ADMIN, "city.cash", Map.of(
                "decision", "APPROVED", "trafficRewardMode", "TEST_MONEY",
                "rewardPerPlayFen", 15, "dailyUserCap", 5, "dailyGameCap", 10, "minDurationMs", 5000));
        assertEquals(true, playAndSubmit(svc, PLAYER1, "city.cash", true, 90, 8000).get("trafficEligible"));

        // Settle with payoutGate CLOSED: points settle (2 * 10 = 20), TEST_MONEY defers
        LocalDate date = LocalDate.of(2026, 11, 16);
        Map<String, Object> firstSettle = svc.settleDailyTraffic(date);
        assertEquals(1, firstSettle.get("settledCount"));
        assertEquals(1, firstSettle.get("deferredCount"));
        assertEquals(20, ptsWallet.get(CREATOR));
        assertNull(cashWallet.get(CREATOR));

        // Open TEST payout gate and rerun: TEST_MONEY settles 15 fen, POINTS stays idempotent (still 20)!
        payoutGate.set(true);
        Map<String, Object> secondSettle = svc.settleDailyTraffic(date);
        assertEquals(2, secondSettle.get("settledCount"));
        assertEquals(20, ptsWallet.get(CREATOR));
        assertEquals(15L, cashWallet.get(CREATOR));
    }

    private Map<String, Object> playAndSubmit(ExternalGameMonetizationService svc, long memberId, String gameId,
                                              boolean passed, int score, long durationMs) {
        Map<String, Object> ticket = svc.startSession(memberId, Map.of("gameId", gameId), null);
        String sid = (String) ticket.get("sessionId");
        String nonce = (String) ticket.get("nonce");
        String digest = "dig-" + sid;
        clock.addAndGet(durationMs); // CRIT-S1: duration is server-measured
        String canonical = ExternalGameMonetizationService.canonicalResultPayload(sid, gameId, nonce, passed, score, durationMs, digest);
        String mac = authorityMac(gameId, canonical);
        return svc.submitResult(memberId, sid, Map.of(
                "nonce", nonce, "passed", passed, "score", score, "durationMs", durationMs,
                "resultDigest", digest, "resultSignature", mac));
    }

    private static String authorityMac(String gameId, String canonical) {
        return ExternalGameMonetizationService.hmacSha256Hex(
                ExternalGameMonetizationService.gameResultKey(RESULT_SECRET, gameId), canonical);
    }

    private void approveGame(String gameId) throws Exception {
        Map<String, byte[]> files = Map.of("index.html", "<h1>ok</h1>".getBytes(StandardCharsets.UTF_8));
        ObjectNode m = JSON.createObjectNode();
        m.put("id", gameId);
        m.put("name", "Game " + gameId);
        m.put("version", "1.0.0");
        m.put("entry", "index.html");
        m.put("monetization", "NONE");
        m.put("contentRating", "4+");
        m.putArray("permissions").add("storage");
        m.putArray("allowedDomains");
        m.put("sha256", ExternalGamePackageValidator.packageSha256(files));
        m.put("signature", "client");
        m.put("author", "Ada");
        ExternalGamePackageValidator.Validated pkg = ExternalGamePackageValidator.validate(m, files, "NONE");
        Map<String, Object> sub = reviews.submit(CREATOR, "req-" + gameId, pkg, null);
        reviews.approve(ADMIN, String.valueOf(sub.get("submissionId")), Map.of());
    }

    private static DriverManagerDataSource ds(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, MYSQL.user(), MYSQL.password());
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return ds;
    }

    private void migrate(String rel) throws Exception {
        String sql = Files.readString(Paths.get(rel));
        for (String stmt : sql.split(";\\s*\\n")) {
            String trimmed = stmt.replaceAll("(?m)^--.*$", "").trim();
            if (!trimmed.isEmpty()) jdbc.execute(trimmed);
        }
    }
}
