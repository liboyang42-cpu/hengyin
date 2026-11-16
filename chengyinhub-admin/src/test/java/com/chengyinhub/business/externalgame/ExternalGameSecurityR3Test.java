package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.begapmisc.MiscMoneySwitch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the r3 security review (qa/ADMIN-SECURITY-REPORT.md):
 * CRIT-S1 (client-keyed result signature), CRIT-S2 (forged node completion), HIGH-S1 (unlimited standalone XP),
 * HIGH-S2 (TEST wallet credits the seller without debiting the buyer). Runs the real service against H2.
 */
class ExternalGameSecurityR3Test {
    private static final String SECRET = "server-only-secret";
    private static final long CREATOR = 7, PLAYER = 9;

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private ExternalGameSigner signer;
    private final AtomicLong clock = new AtomicLong(Timestamp.valueOf("2026-11-16 10:00:00").getTime());
    private final List<ExternalGameNodeBridge.GameResult> bridgeCalls = new ArrayList<>();
    private final Map<Long, Long> balances = new HashMap<>();
    private final Map<Long, Integer> xp = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:extgame_r3_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE external_game (game_id VARCHAR(64) PRIMARY KEY, owner_member_id BIGINT, approved_submission_id BIGINT)");
        jdbc.execute("CREATE TABLE external_game_monetization_config (game_id VARCHAR(64) PRIMARY KEY, owner_member_id BIGINT,"
                + " pricing_mode VARCHAR(16), price_fen BIGINT, review_status VARCHAR(16), creator_share_bps INT,"
                + " channel_fee_bearer INT, config_version BIGINT, traffic_reward_mode VARCHAR(16), daily_user_cap INT,"
                + " daily_game_cap INT, min_duration_ms INT)");
        jdbc.execute("CREATE TABLE external_game_entitlement (id BIGINT AUTO_INCREMENT PRIMARY KEY, game_id VARCHAR(64),"
                + " member_id BIGINT, order_no VARCHAR(40), status VARCHAR(16), granted_at TIMESTAMP, revoked_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE external_game_order (order_no VARCHAR(40) PRIMARY KEY, game_id VARCHAR(64), seller_id BIGINT,"
                + " buyer_id BIGINT, request_id VARCHAR(64), request_hash VARCHAR(64), config_version BIGINT, amount_fen BIGINT,"
                + " creator_share_bps INT, channel_fee_bearer INT, channel_fee_fen BIGINT, creator_net_fen BIGINT,"
                + " platform_share_fen BIGINT, currency VARCHAR(8), provider VARCHAR(16), state VARCHAR(16),"
                + " license_status VARCHAR(16), paid_at TIMESTAMP, creator_credited_at TIMESTAMP, create_time TIMESTAMP,"
                + " update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE external_game_node_binding (node_id BIGINT PRIMARY KEY, game_id VARCHAR(64), topic_id BIGINT,"
                + " min_score INT, require_pass INT, enabled INT)");
        jdbc.execute("CREATE TABLE external_game_play_session (session_id VARCHAR(40) PRIMARY KEY, game_id VARCHAR(64),"
                + " submission_id BIGINT, creator_member_id BIGINT, member_id BIGINT, node_id BIGINT, topic_id BIGINT,"
                + " activity_id BIGINT, nonce CHAR(32), session_signature CHAR(128), status VARCHAR(16) DEFAULT 'STARTED',"
                + " passed TINYINT DEFAULT 0, score INT, duration_ms BIGINT, result_digest CHAR(64), result_signature CHAR(64),"
                + " xp_awarded INT DEFAULT 0, medal_rank INT, node_completed TINYINT DEFAULT 0, traffic_eligible TINYINT DEFAULT 0,"
                + " fraud_reason VARCHAR(40), client_ip_hash CHAR(64), play_date DATE, started_at TIMESTAMP(3), completed_at TIMESTAMP(3))");
        for (String g : List.of("g.one", "g.two", "g.three", "g.four", "g.node", "g.paid")) {
            jdbc.update("INSERT INTO external_game VALUES(?,?,1)", g, CREATOR);
        }
        jdbc.update("INSERT INTO external_game_node_binding VALUES(501,'g.node',88,0,1,1)");
        String key = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
        signer = new ExternalGameSigner(key, "r3");
    }

    private ExternalGameMonetizationService service(boolean payments, boolean testWallet) {
        ExternalGameNodeBridge bridge = new ExternalGameNodeBridge() {
            @Override public NodeOwnerView requireOwnedNode(long creatorMemberId, long nodeId) { return new NodeOwnerView(nodeId, 88); }
            @Override public NodePlayOutcome onGameResult(GameResult r) {
                bridgeCalls.add(r);
                if (r.nodeId() != null) {
                    return r.passed() && r.attested() ? new NodePlayOutcome(5, null, true) : new NodePlayOutcome(0, null, false);
                }
                if (r.passed() && r.standaloneXpAllowed()) {
                    xp.merge(r.memberId(), 10, Integer::sum);
                    return new NodePlayOutcome(10, null, false);
                }
                return new NodePlayOutcome(0, null, false);
            }
        };
        ExternalGameWalletBridge wallet = new ExternalGameWalletBridge() {
            @Override public void creditPoints(long memberId, int points, String businessKey, String reason) {}
            @Override public void creditBalanceFen(long memberId, long fen, String businessKey, String reason) {
                balances.merge(memberId, fen, Long::sum);
            }
            @Override public void debitBalanceFen(long memberId, long fen, String businessKey, String reason) {
                long cur = balances.getOrDefault(memberId, 0L);
                if (cur < fen) throw new AppGapException(409, "INSUFFICIENT_BALANCE", "TEST 余额不足");
                balances.put(memberId, cur - fen);
            }
        };
        return new ExternalGameMonetizationService(jdbc, tx, signer, new MiscMoneySwitch(payments, payments, com.chengyinhub.business.moneyswitch.MoneyGateTestSupport.openGate()),
                wallet, bridge, clock::get, SECRET, testWallet);
    }

    private Map<String, Object> start(ExternalGameMonetizationService svc, String gameId, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("gameId", gameId);
        return svc.startSession(PLAYER, body, null);
    }

    private static Map<String, Object> result(Map<String, Object> ticket, String gameId, String signature) {
        Map<String, Object> body = new HashMap<>();
        body.put("nonce", ticket.get("nonce"));
        body.put("passed", true);
        body.put("score", 100);
        body.put("durationMs", 60000);
        body.put("resultDigest", "d1");
        if (signature != null) body.put("resultSignature", signature);
        return body;
    }

    private static String canonical(Map<String, Object> ticket, String gameId) {
        return ExternalGameMonetizationService.canonicalResultPayload((String) ticket.get("sessionId"), gameId,
                (String) ticket.get("nonce"), true, 100, 60000L, "d1");
    }

    // ---------- CRIT-S1 ----------

    @Test
    void critS1_clientNonceKeyedHmacIsRejected() {
        ExternalGameMonetizationService svc = service(false, false);
        Map<String, Object> t = start(svc, "g.node", Map.of("nodeId", 501L));
        String forged = ExternalGameMonetizationService.hmacSha256Hex((String) t.get("nonce"), canonical(t, "g.node"));
        AppGapException e = assertThrows(AppGapException.class,
                () -> svc.submitResult(PLAYER, (String) t.get("sessionId"), result(t, "g.node", forged)));
        assertEquals("INVALID_SIGNATURE", e.getErrorCode());
        assertTrue(bridgeCalls.isEmpty());
    }

    @Test
    void critS1_unsignedResultIsUnattestedAndCannotCompleteNodeOrEarnTraffic() {
        ExternalGameMonetizationService svc = service(false, false);
        Map<String, Object> t = start(svc, "g.node", Map.of("nodeId", 501L));
        clock.addAndGet(30_000);
        Map<String, Object> out = svc.submitResult(PLAYER, (String) t.get("sessionId"), result(t, "g.node", null));
        assertEquals(false, out.get("attested"));
        assertEquals(false, out.get("nodeCompleted"));
        assertEquals(false, out.get("trafficEligible"));
        assertEquals("UNATTESTED", out.get("fraudReason"));
        assertFalse(bridgeCalls.get(0).attested());
    }

    @Test
    void critS1_serverAuthoritySignatureIsAttestedAndDurationIsServerMeasured() {
        ExternalGameMonetizationService svc = service(false, false);
        Map<String, Object> t = start(svc, "g.node", Map.of("nodeId", 501L));
        clock.addAndGet(30_000); // client claims 60 s, server saw 30 s
        String mac = ExternalGameMonetizationService.hmacSha256Hex(
                ExternalGameMonetizationService.gameResultKey(SECRET, "g.node"), canonical(t, "g.node"));
        Map<String, Object> out = svc.submitResult(PLAYER, (String) t.get("sessionId"), result(t, "g.node", mac));
        assertEquals(true, out.get("attested"));
        assertEquals(true, out.get("nodeCompleted"));
        assertEquals(30_000L, ((Number) out.get("durationMs")).longValue());
        // the ticket handed to the client never contains the per-game key or the server secret
        assertFalse(t.containsValue(ExternalGameMonetizationService.gameResultKey(SECRET, "g.node")));
    }

    @Test
    void critS1_sessionExpiresAfterTtl() {
        ExternalGameMonetizationService svc = service(false, false);
        Map<String, Object> t = start(svc, "g.one", Map.of());
        clock.addAndGet(ExternalGameMonetizationService.SESSION_TTL_MS + 1);
        AppGapException e = assertThrows(AppGapException.class,
                () -> svc.submitResult(PLAYER, (String) t.get("sessionId"), result(t, "g.one", null)));
        assertEquals("SESSION_EXPIRED", e.getErrorCode());
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM external_game_play_session WHERE session_id=?", String.class, t.get("sessionId")));
    }

    // ---------- CRIT-S2 (service side: topic comes from the binding, not the client) ----------

    @Test
    void critS2_clientTopicMustMatchBindingAndStandaloneDropsClientIds() {
        ExternalGameMonetizationService svc = service(false, false);
        AppGapException e = assertThrows(AppGapException.class,
                () -> start(svc, "g.node", Map.of("nodeId", 501L, "topicId", 999L)));
        assertEquals("NODE_BINDING_MISMATCH", e.getErrorCode());

        Map<String, Object> t = start(svc, "g.one", Map.of("topicId", 999L, "activityId", 5L));
        Map<String, Object> row = jdbc.queryForMap("SELECT topic_id, activity_id FROM external_game_play_session WHERE session_id=?",
                t.get("sessionId"));
        assertNull(row.get("TOPIC_ID"));
        assertNull(row.get("ACTIVITY_ID"));
    }

    // ---------- HIGH-S1 ----------

    @Test
    void highS1_standaloneXpOncePerGamePerDayAndDailyCap() {
        ExternalGameMonetizationService svc = service(false, false);
        int[] awarded = new int[5];
        String[] games = {"g.one", "g.one", "g.two", "g.three", "g.four"};
        for (int i = 0; i < games.length; i++) {
            Map<String, Object> t = start(svc, games[i], Map.of());
            clock.addAndGet(10_000);
            awarded[i] = ((Number) svc.submitResult(PLAYER, (String) t.get("sessionId"), result(t, games[i], null))
                    .get("xpAwarded")).intValue();
        }
        assertArrayEquals(new int[]{10, 0, 10, 10, 0}, awarded); // repeat game = 0, 4th game over the 30 XP cap = 0
        assertEquals(ExternalGameMonetizationService.STANDALONE_DAILY_XP_CAP, xp.get(PLAYER));
    }

    @Test
    void highS1_sameSessionReplayDoesNotGrantAgain() {
        ExternalGameMonetizationService svc = service(false, false);
        Map<String, Object> t = start(svc, "g.one", Map.of());
        svc.submitResult(PLAYER, (String) t.get("sessionId"), result(t, "g.one", null));
        Map<String, Object> replay = svc.submitResult(PLAYER, (String) t.get("sessionId"), result(t, "g.one", null));
        assertEquals(true, replay.get("replayed"));
        assertEquals(1, bridgeCalls.size());
        assertEquals(10, xp.get(PLAYER));
    }

    // ---------- HIGH-S2 ----------

    private void paidConfig() {
        jdbc.update("INSERT INTO external_game_monetization_config VALUES('g.paid',?,'PAID',2000,'APPROVED',7500,1,1,'NONE',0,0,0)", CREATOR);
    }

    @Test
    void highS2_testWalletClosedEvenWhenProdPaymentsOpen() {
        paidConfig();
        ExternalGameMonetizationService svc = service(true, false);
        AppGapException e = assertThrows(AppGapException.class, () -> svc.purchaseGame(PLAYER, "g.paid",
                Map.of("requestId", "ord-00000001", "provider", "TEST_WALLET")));
        assertEquals("TEST_ONLY", e.getErrorCode());
        assertThrows(AppGapException.class, () -> svc.confirmOrderForTest(PLAYER, "EGO_x"));
        assertTrue(balances.isEmpty());
    }

    @Test
    void highS2_testWalletIsPairedDebitAndCredit() {
        paidConfig();
        balances.put(PLAYER, 5000L);
        ExternalGameMonetizationService svc = service(true, true);
        Map<String, Object> order = svc.purchaseGame(PLAYER, "g.paid", Map.of("requestId", "ord-00000001", "provider", "TEST_WALLET"));
        assertEquals("PAID", order.get("state"));
        assertEquals(3000L, balances.get(PLAYER));
        assertEquals(1500L, balances.get(CREATOR));
    }

    @Test
    void highS2_insufficientBuyerBalanceCreditsNobody() {
        paidConfig();
        balances.put(PLAYER, 100L);
        ExternalGameMonetizationService svc = service(true, true);
        AppGapException e = assertThrows(AppGapException.class, () -> svc.purchaseGame(PLAYER, "g.paid",
                Map.of("requestId", "ord-00000001", "provider", "TEST_WALLET")));
        assertEquals("INSUFFICIENT_BALANCE", e.getErrorCode());
        assertNull(balances.get(CREATOR));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM external_game_order", Integer.class));
    }
}
