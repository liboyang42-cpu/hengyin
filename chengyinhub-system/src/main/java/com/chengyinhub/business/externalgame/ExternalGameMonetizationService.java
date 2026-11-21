package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.begapmisc.MiscMoneySwitch;
import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * External game monetization, paid entitlements (TEST-only real money behind MiscMoneySwitch),
 * node binding, signed play sessions / result callbacks into XP & medals, anti-fraud traffic accounting,
 * and daily settlements into creator points or TEST earnings.
 */
@Service
public class ExternalGameMonetizationService {
    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    public static final int DEFAULT_CREATOR_SHARE_BPS = 7000;
    public static final int DEFAULT_CHANNEL_FEE_BPS = 60;
    public static final int DEFAULT_DAILY_USER_CAP = 3;
    public static final int DEFAULT_DAILY_GAME_CAP = 200;
    public static final int DEFAULT_MIN_DURATION_MS = 5000;
    public static final int DEFAULT_REWARD_POINTS = 5;
    /** CRIT-S1: a play session older than this can no longer report a result. */
    public static final long SESSION_TTL_MS = 2L * 60 * 60 * 1000;
    /** HIGH-S1: standalone (no node) pass XP: first pass per game per day only, and at most this much per day. */
    public static final int STANDALONE_DAILY_XP_CAP = 30;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Set<String> PRICING_MODES = Set.of("FREE", "PAID", "TRAFFIC_ONLY");
    private static final Set<String> TRAFFIC_MODES = Set.of("NONE", "POINTS", "TEST_MONEY");
    private static final Set<String> PROVIDERS = Set.of("TEST_WALLET", "WECHAT_APP", "APPLE_IAP");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExternalGameSigner signer;
    private final MiscMoneySwitch moneySwitch;
    private final ExternalGameWalletBridge walletBridge;
    private final ExternalGameNodeBridge nodeBridge;
    private final LongSupplier clock;
    private final String resultSecret;
    private final boolean testWalletEnabled;

    @Autowired
    public ExternalGameMonetizationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ExternalGameSigner signer,
            @Autowired(required = false) MiscMoneySwitch moneySwitch,
            @Autowired(required = false) ExternalGameWalletBridge walletBridge,
            @Autowired(required = false) ExternalGameNodeBridge nodeBridge,
            @Value("${chengyin.external-game.result-secret:${CHENGYIN_EXTERNAL_GAME_RESULT_SECRET:}}") String resultSecret,
            @Value("${chengyin.external-game.test-wallet-enabled:false}") boolean testWalletEnabled) {
        this(jdbc, transactions, signer, moneySwitch, walletBridge, nodeBridge, System::currentTimeMillis,
                resultSecret, testWalletEnabled);
    }

    public ExternalGameMonetizationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ExternalGameSigner signer,
            MiscMoneySwitch moneySwitch,
            ExternalGameWalletBridge walletBridge,
            ExternalGameNodeBridge nodeBridge,
            LongSupplier clock) {
        this(jdbc, transactions, signer, moneySwitch, walletBridge, nodeBridge, clock, null, false);
    }

    public ExternalGameMonetizationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ExternalGameSigner signer,
            MiscMoneySwitch moneySwitch,
            ExternalGameWalletBridge walletBridge,
            ExternalGameNodeBridge nodeBridge,
            LongSupplier clock,
            String resultSecret,
            boolean testWalletEnabled) {
        this.resultSecret = resultSecret == null || resultSecret.isBlank() ? null : resultSecret.trim();
        this.testWalletEnabled = testWalletEnabled;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.signer = signer;
        this.moneySwitch = moneySwitch;
        this.walletBridge = walletBridge;
        this.nodeBridge = nodeBridge;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    // ================= Money Gate & Split Math =================

    public boolean paymentsOpen() {
        return moneySwitch != null && moneySwitch.paymentsOpen();
    }

    public boolean payoutOpen() {
        return moneySwitch != null && moneySwitch.payoutOpen();
    }

    /**
     * HIGH-S2: the TEST wallet and confirm-test paths move TEST balance without a real payment. They are only open
     * when the TEST-only flag {@code chengyin.external-game.test-wallet-enabled} is on, independent of the prod money
     * switch, so switching real payments on in prod never opens them.
     */
    public boolean testWalletOpen() {
        return testWalletEnabled && paymentsOpen();
    }

    private void requireTestWalletOpen() {
        if (!testWalletOpen()) {
            throw new AppGapException(403, "TEST_ONLY", "TEST 钱包与测试确认仅在 TEST 环境开放");
        }
    }

    private void requirePaymentsOpen() {
        if (!paymentsOpen()) {
            throw new AppGapException(403, CashPayoutSwitch.FEATURE_CLOSED, CashPayoutSwitch.FEATURE_CLOSED);
        }
    }

    public record SplitBreakdown(long grossFen, int creatorShareBps, int channelFeeBearer,
                                 long channelFeeFen, long creatorNetFen, long platformShareFen) {}

    public static SplitBreakdown computeSplit(long amountFen, int creatorShareBps, int channelFeeBearer) {
        if (amountFen <= 0) {
            return new SplitBreakdown(0, creatorShareBps, channelFeeBearer, 0, 0, 0);
        }
        int bps = Math.max(0, Math.min(10000, creatorShareBps));
        int bearer = channelFeeBearer == 1 ? 1 : 0;
        long channelFeeFen = (amountFen * DEFAULT_CHANNEL_FEE_BPS + 5000L) / 10000L;
        long creatorGrossFen = (amountFen * bps) / 10000L;
        long platformGrossFen = amountFen - creatorGrossFen;
        long creatorNetFen = bearer == 0 ? Math.max(0L, creatorGrossFen - channelFeeFen) : creatorGrossFen;
        long platformShareFen = bearer == 1 ? Math.max(0L, platformGrossFen - channelFeeFen) : platformGrossFen;
        return new SplitBreakdown(amountFen, bps, bearer, channelFeeFen, creatorNetFen, platformShareFen);
    }

    // ================= Canonical Signing Payloads =================

    public static String canonicalStartPayload(String sessionId, String gameId, long memberId, Long nodeId, String nonce, long issuedAtMs) {
        return "EXTGAME_START|" + sessionId + "|" + gameId + "|" + memberId + "|"
                + (nodeId == null ? 0L : nodeId) + "|" + nonce + "|" + issuedAtMs;
    }

    public static String canonicalResultPayload(String sessionId, String gameId, String nonce,
                                                boolean passed, Integer score, long durationMs, String resultDigest) {
        return "EXTGAME_RESULT|" + sessionId + "|" + gameId + "|" + nonce + "|"
                + (passed ? "1" : "0") + "|" + (score == null ? "" : score) + "|"
                + durationMs + "|" + (resultDigest == null ? "" : resultDigest);
    }

    public static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC calculation failed", e);
        }
    }

    // ================= Creator Pricing & Config =================

    public Map<String, Object> saveCreatorConfig(long creatorId, String gameId, Map<String, Object> body) {
        requireActor(creatorId);
        String gId = requireGameId(gameId);
        Map<String, Object> p = body == null ? Map.of() : body;
        String pricingMode = normalizeEnum(str(p.get("pricingMode"), "FREE"), PRICING_MODES, "pricingMode");
        long priceFen = longVal(p.get("priceFen"), 0L);
        if ("PAID".equals(pricingMode)) {
            if (priceFen <= 0 || priceFen > Integer.MAX_VALUE) {
                throw AppGapException.badRequest("priceFen");
            }
        } else {
            priceFen = 0L;
        }
        String trafficMode = normalizeEnum(str(p.get("trafficRewardMode"), "POINTS"), TRAFFIC_MODES, "trafficRewardMode");
        int channelFeeBearer = intVal(p.get("channelFeeBearer"), 0);
        if (channelFeeBearer != 0 && channelFeeBearer != 1) {
            throw AppGapException.badRequest("channelFeeBearer");
        }
        String requestId = trimOrNull(str(p.get("requestId"), null));
        if (requestId != null && requestId.length() > 64) {
            throw AppGapException.badRequest("requestId");
        }
        String workshopVersionId = trimOrNull(str(p.get("workshopVersionId"), null));
        String reqHash = sha256Hex(gId + "|" + pricingMode + "|" + priceFen + "|" + trafficMode + "|" + channelFeeBearer + "|" + Objects.toString(workshopVersionId, ""));

        final long finalPriceFen = priceFen;
        return tx.execute(status -> {
            Map<String, Object> game = lockGame(gId);
            long owner = num(game.get("owner_member_id"));
            if (owner != creatorId) {
                throw new AppGapException(403, "FORBIDDEN", "无权配置他人游戏的变现规则");
            }
            if (game.get("approved_submission_id") == null) {
                throw AppGapException.conflict("GAME_NOT_APPROVED", "外部游戏审核通过上架后才可配置定价与变现");
            }
            if (requestId != null) {
                List<Map<String, Object>> byReq = jdbc.queryForList(
                        "SELECT * FROM external_game_monetization_config WHERE owner_member_id=? AND request_id=?",
                        creatorId, requestId);
                if (!byReq.isEmpty()) {
                    Map<String, Object> existing = byReq.get(0);
                    if (!Objects.equals(existing.get("request_hash"), reqHash)) {
                        throw AppGapException.conflict("REQUEST_ID_REUSED", "requestId 已被用于不同的变现配置");
                    }
                    return configView(existing, true);
                }
            }

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM external_game_monetization_config WHERE game_id=? FOR UPDATE", gId);
            Timestamp now = nowTs();
            String reviewStatus = ("PAID".equals(pricingMode) || !"NONE".equals(trafficMode)) ? "PENDING" : "APPROVED";
            if (rows.isEmpty()) {
                jdbc.update(
                        "INSERT INTO external_game_monetization_config("
                                + "game_id, owner_member_id, pricing_mode, price_fen, currency, workshop_version_id,"
                                + "creator_share_bps, channel_fee_bearer, traffic_reward_mode, reward_per_play_points,"
                                + "reward_per_play_fen, daily_user_cap, daily_game_cap, min_duration_ms,"
                                + "review_status, config_version, request_id, request_hash, create_time, update_time) "
                                + "VALUES(?,?,?,?, 'CNY', ?, ?,?,?, ?, ?, ?,?,?, ?, 1, ?,?,?,?)",
                        gId, creatorId, pricingMode, finalPriceFen, workshopVersionId,
                        DEFAULT_CREATOR_SHARE_BPS, channelFeeBearer, trafficMode, DEFAULT_REWARD_POINTS,
                        0L, DEFAULT_DAILY_USER_CAP, DEFAULT_DAILY_GAME_CAP, DEFAULT_MIN_DURATION_MS,
                        reviewStatus, requestId, reqHash, now, now);
            } else {
                long nextVer = num(rows.get(0).get("config_version")) + 1L;
                jdbc.update(
                        "UPDATE external_game_monetization_config SET pricing_mode=?, price_fen=?, workshop_version_id=?,"
                                + "channel_fee_bearer=?, traffic_reward_mode=?, review_status=?, review_note=NULL,"
                                + "reviewer_id=NULL, reviewed_time=NULL, config_version=?, request_id=?, request_hash=?, update_time=? "
                                + "WHERE game_id=?",
                        pricingMode, finalPriceFen, workshopVersionId, channelFeeBearer, trafficMode,
                        reviewStatus, nextVer, requestId, reqHash, now, gId);
            }
            Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM external_game_monetization_config WHERE game_id=?", gId);
            return configView(saved, true);
        });
    }

    public Map<String, Object> publicMonetizationView(Long viewerId, String gameId) {
        String gId = requireGameId(gameId);
        List<Map<String, Object>> games = jdbc.queryForList(
                "SELECT game_id, owner_member_id, approved_submission_id FROM external_game WHERE game_id=?", gId);
        if (games.isEmpty() || games.get(0).get("approved_submission_id") == null) {
            throw AppGapException.notFound("EXTERNAL_GAME_NOT_FOUND");
        }
        long owner = num(games.get(0).get("owner_member_id"));
        List<Map<String, Object>> cfgs = jdbc.queryForList(
                "SELECT * FROM external_game_monetization_config WHERE game_id=?", gId);
        boolean entitled = viewerId != null && (viewerId == owner || hasActiveEntitlement(gId, viewerId));

        if (cfgs.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("gameId", gId);
            out.put("pricingMode", "FREE");
            out.put("priceFen", 0L);
            out.put("currency", "CNY");
            out.put("trafficRewardMode", "NONE");
            out.put("reviewStatus", "NONE");
            out.put("creatorShareBps", DEFAULT_CREATOR_SHARE_BPS);
            out.put("channelFeeBearer", 0);
            out.put("entitled", true);
            out.put("isOwner", viewerId != null && viewerId == owner);
            out.put("moneyGateOpen", paymentsOpen());
            return out;
        }
        Map<String, Object> row = cfgs.get(0);
        boolean isOwner = viewerId != null && viewerId == owner;
        boolean approvedPaid = "APPROVED".equals(row.get("review_status"))
                && "PAID".equals(row.get("pricing_mode"))
                && num(row.get("price_fen")) > 0L;
        Map<String, Object> view = configView(row, isOwner);
        view.put("entitled", !approvedPaid || entitled);
        view.put("isOwner", isOwner);
        view.put("moneyGateOpen", paymentsOpen());
        return view;
    }

    public void requireDownloadOrPlayAccess(Long viewerId, String gameId) {
        String gId = requireGameId(gameId);
        List<Map<String, Object>> cfgs = jdbc.queryForList(
                "SELECT owner_member_id, pricing_mode, price_fen, review_status FROM external_game_monetization_config WHERE game_id=?", gId);
        if (cfgs.isEmpty()) return;
        Map<String, Object> row = cfgs.get(0);
        if (!"APPROVED".equals(row.get("review_status")) || !"PAID".equals(row.get("pricing_mode")) || num(row.get("price_fen")) <= 0) {
            return;
        }
        if (viewerId == null || viewerId <= 0) {
            throw new AppGapException(403, "ENTITLEMENT_REQUIRED", "请先登录并购买该外部游戏");
        }
        long owner = num(row.get("owner_member_id"));
        if (viewerId == owner || hasActiveEntitlement(gId, viewerId)) {
            return;
        }
        throw new AppGapException(403, "ENTITLEMENT_REQUIRED", "需先购买该外部游戏才可下载或游玩");
    }

    private boolean hasActiveEntitlement(String gameId, long memberId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_game_entitlement WHERE game_id=? AND member_id=? AND status='ACTIVE'",
                Integer.class, gameId, memberId);
        return cnt != null && cnt > 0;
    }

    // ================= Paid Purchase & Entitlement (Workshop v2 Pattern) =================

    public Map<String, Object> purchaseGame(long buyerId, String gameId, Map<String, Object> body) {
        requireActor(buyerId);
        requirePaymentsOpen();
        String gId = requireGameId(gameId);
        Map<String, Object> p = body == null ? Map.of() : body;
        String requestId = requireKey(str(p.get("requestId"), null), "requestId");
        String provider = normalizeEnum(str(p.get("provider"), "TEST_WALLET"), PROVIDERS, "provider");
        if ("TEST_WALLET".equals(provider)) {
            requireTestWalletOpen();
        }
        String reqHash = sha256Hex(buyerId + "|" + gId + "|" + provider);

        return tx.execute(status -> {
            List<Map<String, Object>> byReq = jdbc.queryForList(
                    "SELECT * FROM external_game_order WHERE buyer_id=? AND request_id=?",
                    buyerId, requestId);
            if (!byReq.isEmpty()) {
                Map<String, Object> existing = byReq.get(0);
                if (!Objects.equals(existing.get("request_hash"), reqHash)) {
                    throw AppGapException.conflict("REQUEST_ID_REUSED", "requestId 已被用于其他订单");
                }
                Map<String, Object> v = orderView(existing);
                v.put("replayed", true);
                return v;
            }

            List<Map<String, Object>> cfgs = jdbc.queryForList(
                    "SELECT * FROM external_game_monetization_config WHERE game_id=? FOR UPDATE", gId);
            if (cfgs.isEmpty()) {
                throw AppGapException.conflict("NOT_FOR_SALE", "该游戏尚未配置付费上架");
            }
            Map<String, Object> cfg = cfgs.get(0);
            if (!"APPROVED".equals(cfg.get("review_status")) || !"PAID".equals(cfg.get("pricing_mode"))) {
                throw AppGapException.conflict("NOT_FOR_SALE", "该游戏当前不可付费购买");
            }
            long sellerId = num(cfg.get("owner_member_id"));
            if (sellerId == buyerId) {
                throw AppGapException.conflict("CANNOT_PURCHASE_OWN_GAME", "创作者无需购买自己的游戏");
            }
            if (hasActiveEntitlement(gId, buyerId)) {
                throw AppGapException.conflict("ALREADY_ENTITLED", "已拥有该外部游戏授权");
            }

            long amountFen = num(cfg.get("price_fen"));
            int bps = (int) num(cfg.get("creator_share_bps"));
            int bearer = (int) num(cfg.get("channel_fee_bearer"));
            long cfgVer = num(cfg.get("config_version"));
            SplitBreakdown split = computeSplit(amountFen, bps, bearer);
            String orderNo = "EGO_" + randomHex(16);
            Timestamp now = nowTs();
            boolean testWallet = "TEST_WALLET".equals(provider);
            String state = testWallet ? "PAID" : "PENDING";
            String licenseStatus = testWallet ? "ACTIVE" : "PENDING";
            Timestamp paidAt = testWallet ? now : null;

            jdbc.update(
                    "INSERT INTO external_game_order("
                            + "order_no, game_id, seller_id, buyer_id, request_id, request_hash, config_version,"
                            + "amount_fen, creator_share_bps, channel_fee_bearer, channel_fee_fen, creator_net_fen,"
                            + "platform_share_fen, currency, provider, state, license_status, paid_at, create_time, update_time) "
                            + "VALUES(?,?,?,?,?,?,?, ?,?,?,?,?, ?, 'CNY', ?,?,?,?, ?,?)",
                    orderNo, gId, sellerId, buyerId, requestId, reqHash, cfgVer,
                    amountFen, bps, bearer, split.channelFeeFen(), split.creatorNetFen(),
                    split.platformShareFen(), provider, state, licenseStatus, paidAt, now, now);

            if (testWallet) {
                settleTestPayment(orderNo, gId, buyerId, sellerId, amountFen, split.creatorNetFen(), now);
            }
            Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM external_game_order WHERE order_no=?", orderNo);
            return orderView(stored);
        });
    }

    public Map<String, Object> confirmOrderForTest(long buyerId, String orderNo) {
        requireActor(buyerId);
        requireTestWalletOpen();
        if (orderNo == null || orderNo.isBlank()) throw AppGapException.badRequest("orderNo");
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM external_game_order WHERE order_no=? FOR UPDATE", orderNo.trim());
            if (rows.isEmpty()) throw AppGapException.notFound("ORDER_NOT_FOUND");
            Map<String, Object> row = rows.get(0);
            if (num(row.get("buyer_id")) != buyerId) {
                throw new AppGapException(403, "FORBIDDEN", "无权确认他人订单");
            }
            if ("PAID".equals(row.get("state"))) {
                return orderView(row);
            }
            if (!"PENDING".equals(row.get("state"))) {
                throw AppGapException.conflict("ORDER_NOT_PAYABLE", "订单状态不可支付");
            }
            Timestamp now = nowTs();
            String gId = String.valueOf(row.get("game_id"));
            long sellerId = num(row.get("seller_id"));
            long netFen = num(row.get("creator_net_fen"));
            jdbc.update("UPDATE external_game_order SET state='PAID', license_status='ACTIVE', paid_at=?, update_time=? WHERE order_no=?",
                    now, now, orderNo.trim());
            if (row.get("creator_credited_at") == null) {
                settleTestPayment(orderNo.trim(), gId, buyerId, sellerId, num(row.get("amount_fen")), netFen, now);
            } else {
                grantEntitlement(gId, buyerId, orderNo.trim(), now);
            }
            return orderView(jdbc.queryForMap("SELECT * FROM external_game_order WHERE order_no=?", orderNo.trim()));
        });
    }

    /**
     * HIGH-S2: TEST payment = paired transfer. The buyer's TEST balance is debited by the full price (guarded in SQL:
     * insufficient balance fails the whole transaction) before the seller is credited the net share, both under the
     * same order key inside the caller's transaction. Called only while the order row is locked and not yet credited.
     */
    private void settleTestPayment(String orderNo, String gameId, long buyerId, long sellerId,
                                   long amountFen, long creatorNetFen, Timestamp now) {
        if (walletBridge == null) {
            throw new AppGapException(503, "WALLET_UNAVAILABLE", "TEST 钱包未就绪");
        }
        String key = "extgame:order:" + orderNo;
        if (amountFen > 0) {
            walletBridge.debitBalanceFen(buyerId, amountFen, key, "外部游戏购买(TEST): " + gameId);
        }
        grantEntitlement(gameId, buyerId, orderNo, now);
        if (creatorNetFen > 0) {
            walletBridge.creditBalanceFen(sellerId, creatorNetFen, key, "外部游戏购买分成(TEST): " + gameId);
        }
        jdbc.update("UPDATE external_game_order SET creator_credited_at=? WHERE order_no=? AND creator_credited_at IS NULL",
                now, orderNo);
    }

    public Map<String, Object> getOrder(long memberId, String orderNo) {
        requireActor(memberId);
        if (orderNo == null || orderNo.isBlank()) throw AppGapException.badRequest("orderNo");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM external_game_order WHERE order_no=?", orderNo.trim());
        if (rows.isEmpty()) throw AppGapException.notFound("ORDER_NOT_FOUND");
        Map<String, Object> row = rows.get(0);
        if (num(row.get("buyer_id")) != memberId && num(row.get("seller_id")) != memberId) {
            throw new AppGapException(403, "FORBIDDEN", "无权查看该订单");
        }
        return orderView(row);
    }

    public Map<String, Object> myEntitlements(long memberId) {
        requireActor(memberId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT e.game_id, e.order_no, e.status, e.granted_at, "
                        + "COALESCE(s.name, e.game_id) AS game_name, s.version "
                        + "FROM external_game_entitlement e "
                        + "LEFT JOIN external_game g ON g.game_id = e.game_id "
                        + "LEFT JOIN external_game_submission s ON s.id = g.approved_submission_id "
                        + "WHERE e.member_id=? AND e.status='ACTIVE' ORDER BY e.id DESC",
                memberId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gameId", r.get("game_id"));
            item.put("gameName", r.get("game_name"));
            item.put("version", r.get("version"));
            item.put("orderNo", r.get("order_no"));
            item.put("status", r.get("status"));
            item.put("grantedAt", r.get("granted_at") == null ? null : r.get("granted_at").toString());
            items.add(item);
        }
        return Map.of("items", items);
    }

    private void grantEntitlement(String gameId, long memberId, String orderNo, Timestamp now) {
        int updated = jdbc.update(
                "UPDATE external_game_entitlement SET order_no=?, status='ACTIVE', granted_at=?, revoked_at=NULL "
                        + "WHERE game_id=? AND member_id=?",
                orderNo, now, gameId, memberId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO external_game_entitlement(game_id, member_id, order_no, status, granted_at) VALUES(?,?,?,'ACTIVE',?)",
                    gameId, memberId, orderNo, now);
        }
    }

    // ================= Node Binding =================

    public Map<String, Object> bindNode(long creatorId, long nodeId, Map<String, Object> body) {
        requireActor(creatorId);
        if (nodeId <= 0) throw AppGapException.badRequest("nodeId");
        Map<String, Object> p = body == null ? Map.of() : body;
        String gId = requireGameId(str(p.get("gameId"), null));
        int minScore = Math.max(0, intVal(p.get("minScore"), 0));
        boolean requirePass = boolVal(p.get("requirePass"), true);

        long topicId;
        if (nodeBridge != null) {
            ExternalGameNodeBridge.NodeOwnerView owned = nodeBridge.requireOwnedNode(creatorId, nodeId);
            topicId = owned.topicId();
        } else {
            topicId = longVal(p.get("topicId"), 0L);
            if (topicId <= 0) throw AppGapException.badRequest("topicId");
        }

        return tx.execute(status -> {
            Map<String, Object> game = lockGame(gId);
            if (game.get("approved_submission_id") == null) {
                throw AppGapException.conflict("GAME_NOT_APPROVED", "只能绑定审核通过的外部游戏");
            }
            Timestamp now = nowTs();
            int updated = jdbc.update(
                    "UPDATE external_game_node_binding SET topic_id=?, game_id=?, owner_member_id=?, min_score=?, require_pass=?, enabled=1, update_time=? WHERE node_id=?",
                    topicId, gId, creatorId, minScore, requirePass ? 1 : 0, now, nodeId);
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO external_game_node_binding(node_id, topic_id, game_id, owner_member_id, min_score, require_pass, enabled, create_time, update_time) "
                                + "VALUES(?,?,?,?,?,?,1,?,?)",
                        nodeId, topicId, gId, creatorId, minScore, requirePass ? 1 : 0, now, now);
            }
            return nodeBindingView(nodeId);
        });
    }

    public Map<String, Object> unbindNode(long creatorId, long nodeId) {
        requireActor(creatorId);
        if (nodeId <= 0) throw AppGapException.badRequest("nodeId");
        if (nodeBridge != null) {
            nodeBridge.requireOwnedNode(creatorId, nodeId);
        }
        return tx.execute(status -> {
            jdbc.update("UPDATE external_game_node_binding SET enabled=0, update_time=? WHERE node_id=?", nowTs(), nodeId);
            return Map.of("nodeId", nodeId, "bound", false);
        });
    }

    public Map<String, Object> nodeBindingView(long nodeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.node_id, b.topic_id, b.game_id, b.min_score, b.require_pass, b.enabled, "
                        + "COALESCE(s.name, b.game_id) AS game_name, s.version, g.approved_submission_id "
                        + "FROM external_game_node_binding b "
                        + "LEFT JOIN external_game g ON g.game_id = b.game_id "
                        + "LEFT JOIN external_game_submission s ON s.id = g.approved_submission_id "
                        + "WHERE b.node_id=? AND b.enabled=1",
                nodeId);
        if (rows.isEmpty()) {
            return Map.of("nodeId", nodeId, "bound", false);
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", num(r.get("node_id")));
        out.put("topicId", num(r.get("topic_id")));
        out.put("bound", true);
        out.put("gameId", r.get("game_id"));
        out.put("gameName", r.get("game_name"));
        out.put("version", r.get("version"));
        out.put("minScore", (int) num(r.get("min_score")));
        out.put("requirePass", num(r.get("require_pass")) == 1L);
        // G3: false while the game has no listed version (taken down or not yet approved); the app explains why
        // with GET /api/external-game/catalog/{gameId}/availability.
        out.put("listed", r.get("approved_submission_id") != null);
        return out;
    }

    public Map<Long, Map<String, Object>> activeBindingsByTopic(long topicId) {
        if (topicId <= 0) return Map.of();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT b.node_id, b.game_id, b.min_score, b.require_pass, "
                            + "COALESCE(s.name, b.game_id) AS game_name, s.version "
                            + "FROM external_game_node_binding b "
                            + "LEFT JOIN external_game g ON g.game_id = b.game_id "
                            + "LEFT JOIN external_game_submission s ON s.id = g.approved_submission_id "
                            + "WHERE b.topic_id=? AND b.enabled=1 AND g.approved_submission_id IS NOT NULL",
                    topicId);
            Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("gameId", r.get("game_id"));
                card.put("gameName", r.get("game_name"));
                card.put("version", r.get("version"));
                card.put("minScore", (int) num(r.get("min_score")));
                card.put("requirePass", num(r.get("require_pass")) == 1L);
                out.put(num(r.get("node_id")), card);
            }
            return out;
        } catch (DataAccessException missingTableBeforeMigration) {
            return Map.of();
        }
    }

    // ================= Signed Play Session & Result Callback =================

    public Map<String, Object> startSession(long memberId, Map<String, Object> body, String clientIp) {
        requireActor(memberId);
        Map<String, Object> p = body == null ? Map.of() : body;
        String gId = requireGameId(str(p.get("gameId"), null));
        Long nodeId = nullableLong(p.get("nodeId"));
        Long clientTopicId = nullableLong(p.get("topicId"));
        Long clientActivityId = nullableLong(p.get("activityId"));

        requireDownloadOrPlayAccess(memberId, gId);

        return tx.execute(status -> {
            Map<String, Object> game = lockGame(gId);
            Object subObj = game.get("approved_submission_id");
            if (subObj == null) {
                throw AppGapException.conflict("GAME_NOT_APPROVED", "该外部游戏尚未通过上架审核");
            }
            long submissionId = num(subObj);
            long creatorId = num(game.get("owner_member_id"));
            Long topicId = null;
            Long activityId = null;

            if (nodeId != null) {
                List<Map<String, Object>> bindings = jdbc.queryForList(
                        "SELECT game_id, topic_id FROM external_game_node_binding WHERE node_id=? AND enabled=1", nodeId);
                if (bindings.isEmpty() || !gId.equals(bindings.get(0).get("game_id"))) {
                    throw AppGapException.conflict("NODE_BINDING_MISMATCH", "该节点未绑定此外部游戏");
                }
                // CRIT-S2: the topic comes from the server-side binding, never from the client.
                Long boundTopic = nullableLong(bindings.get(0).get("topic_id"));
                if (clientTopicId != null && boundTopic != null && !clientTopicId.equals(boundTopic)) {
                    throw AppGapException.conflict("NODE_BINDING_MISMATCH", "该节点不属于此主题");
                }
                topicId = boundTopic;
                // activityId is kept only as a claim; the node bridge re-validates it (registration, window) on result.
                activityId = clientActivityId != null && clientActivityId > 0 ? clientActivityId : null;
            }

            long issuedAtMs = clock.getAsLong();
            String sessionId = "EGS_" + randomHex(16);
            String nonce = randomHex(16);
            String payload = canonicalStartPayload(sessionId, gId, memberId, nodeId, nonce, issuedAtMs);
            String sessionSignature = signer.signHex(payload.getBytes(StandardCharsets.UTF_8));
            LocalDate playDate = LocalDate.ofInstant(Instant.ofEpochMilli(issuedAtMs), SHANGHAI);
            Timestamp startedAt = new Timestamp(issuedAtMs);
            String ipHash = clientIp == null || clientIp.isBlank() ? null : sha256Hex(clientIp.trim());

            jdbc.update(
                    "INSERT INTO external_game_play_session("
                            + "session_id, game_id, submission_id, creator_member_id, member_id,"
                            + "node_id, topic_id, activity_id, nonce, session_signature,"
                            + "status, client_ip_hash, play_date, started_at) "
                            + "VALUES(?,?,?,?,?, ?,?,?,?,?, 'STARTED', ?,?,?)",
                    sessionId, gId, submissionId, creatorId, memberId,
                    nodeId, topicId, activityId, nonce, sessionSignature,
                    ipHash, Date.valueOf(playDate), startedAt);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sessionId", sessionId);
            out.put("gameId", gId);
            out.put("submissionId", submissionId);
            out.put("nodeId", nodeId);
            out.put("nonce", nonce);
            out.put("issuedAtMs", issuedAtMs);
            out.put("sessionSignature", sessionSignature);
            out.put("signatureAlgorithm", "Ed25519");
            return out;
        });
    }

    /**
     * Result callback for a play session.
     *
     * <p>CRIT-S1: the client holds the session nonce, so a nonce-keyed HMAC proves nothing. The nonce now only binds
     * the result to its session. A result counts as <b>attested</b> only when it is signed by a server-side game
     * authority: HMAC-SHA256 with the per-game result key ({@link #gameResultKey}, derived from the server-held
     * {@code chengyin.external-game.result-secret}, never sent to the app), or the platform Ed25519 key. Unattested
     * results are recorded but cannot complete a node or earn traffic rewards; they get only the capped standalone XP.
     * Duration is measured on the server ({@code completed_at - started_at}) and sessions expire after
     * {@link #SESSION_TTL_MS}.
     */
    public Map<String, Object> submitResult(long memberId, String sessionId, Map<String, Object> body) {
        requireActor(memberId);
        if (sessionId == null || sessionId.isBlank()) throw AppGapException.badRequest("sessionId");
        Map<String, Object> p = body == null ? Map.of() : body;
        String nonce = trimOrNull(str(p.get("nonce"), null));
        String resultSig = trimOrNull(str(p.get("resultSignature"), null));
        if (nonce == null) {
            throw new AppGapException(400, "SIGNATURE_REQUIRED", "结果回执必须携带会话随机串");
        }
        boolean passed = boolVal(p.get("passed"), false);
        Integer score = p.get("score") == null ? null : intVal(p.get("score"), 0);
        long reportedDurationMs = Math.max(0L, longVal(p.get("durationMs"), 0L));
        String resultDigest = trimOrNull(str(p.get("resultDigest"), null));
        if (resultDigest == null) {
            resultDigest = sha256Hex((passed ? "1" : "0") + ":" + Objects.toString(score, "") + ":" + reportedDurationMs);
        }
        final String finalDigest = resultDigest;
        final String sid = sessionId.trim();
        expireIfStale(sid);

        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM external_game_play_session WHERE session_id=? FOR UPDATE", sid);
            if (rows.isEmpty()) throw AppGapException.notFound("SESSION_NOT_FOUND");
            Map<String, Object> sess = rows.get(0);
            if (num(sess.get("member_id")) != memberId) {
                throw new AppGapException(403, "FORBIDDEN", "无权提交他人游戏会话结果");
            }
            String expectedNonce = String.valueOf(sess.get("nonce"));
            String gameId = String.valueOf(sess.get("game_id"));
            if (!constantTimeEquals(expectedNonce, nonce)) {
                throw new AppGapException(403, "INVALID_NONCE", "会话随机串不匹配");
            }

            String canonical = canonicalResultPayload(sid, gameId, nonce, passed, score, reportedDurationMs, finalDigest);
            boolean attested = false;
            if (resultSig != null) {
                attested = verifyAuthoritySignature(gameId, canonical, resultSig);
                if (!attested) {
                    throw new AppGapException(403, "INVALID_SIGNATURE", "结果签名校验失败");
                }
            }

            String currentStatus = String.valueOf(sess.get("status"));
            if ("COMPLETED".equals(currentStatus)) {
                if (Objects.equals(sess.get("result_digest"), finalDigest)) {
                    Map<String, Object> replay = sessionResultView(sess);
                    replay.put("replayed", true);
                    return replay;
                }
                throw AppGapException.conflict("SESSION_ALREADY_COMPLETED", "该游玩的签名结果已上报");
            }
            if (!"STARTED".equals(currentStatus)) {
                throw AppGapException.conflict("SESSION_EXPIRED", "游戏会话已失效");
            }

            long nowMs = clock.getAsLong();
            long durationMs = Math.max(0L, nowMs - toEpochMs(sess.get("started_at")));

            // Evaluate node pass gate if bound
            Long nodeId = nullableLong(sess.get("node_id"));
            Long topicId = nullableLong(sess.get("topic_id"));
            Long activityId = nullableLong(sess.get("activity_id"));
            boolean effectivePass = passed;
            if (nodeId != null) {
                List<Map<String, Object>> bindings = jdbc.queryForList(
                        "SELECT min_score, require_pass FROM external_game_node_binding WHERE node_id=? AND enabled=1",
                        nodeId);
                if (!bindings.isEmpty()) {
                    int minScore = (int) num(bindings.get(0).get("min_score"));
                    boolean requirePass = num(bindings.get(0).get("require_pass")) == 1L;
                    if (requirePass && !passed) effectivePass = false;
                    if (minScore > 0 && (score == null || score < minScore)) effectivePass = false;
                }
            }

            Date playDate = toSqlDate(sess.get("play_date"));
            boolean standaloneXpAllowed = nodeId == null && effectivePass
                    && standaloneXpAllowed(memberId, gameId, playDate);

            int xpAwarded = 0;
            Integer medalRank = null;
            boolean nodeCompleted = false;
            if (nodeBridge != null) {
                ExternalGameNodeBridge.NodePlayOutcome outcome = nodeBridge.onGameResult(
                        new ExternalGameNodeBridge.GameResult(memberId, gameId, sid, nodeId, topicId, activityId,
                                score, effectivePass, attested, standaloneXpAllowed));
                if (outcome != null) {
                    xpAwarded = outcome.xpAwarded();
                    medalRank = outcome.medalRank();
                    nodeCompleted = outcome.nodeCompleted();
                }
            }

            // Anti-fraud evaluation for traffic reward eligibility
            long creatorId = num(sess.get("creator_member_id"));
            TrafficEligibility eligibility = attested
                    ? evaluateTrafficEligibility(gameId, creatorId, memberId, playDate, effectivePass, durationMs)
                    : new TrafficEligibility(false, "UNATTESTED");
            Timestamp now = new Timestamp(nowMs);
            String storedSig = resultSig == null ? null : (resultSig.length() <= 64 ? resultSig : sha256Hex(resultSig));

            int updated = jdbc.update(
                    "UPDATE external_game_play_session SET status='COMPLETED', passed=?, score=?, duration_ms=?,"
                            + "result_digest=?, result_signature=?, xp_awarded=?, medal_rank=?, node_completed=?,"
                            + "traffic_eligible=?, fraud_reason=?, completed_at=? "
                            + "WHERE session_id=? AND status='STARTED'",
                    effectivePass ? 1 : 0, score, durationMs, finalDigest, storedSig,
                    xpAwarded, medalRank, nodeCompleted ? 1 : 0,
                    eligibility.eligible() ? 1 : 0, eligibility.fraudReason(), now, sid);
            if (updated != 1) {
                throw AppGapException.conflict("SESSION_ALREADY_COMPLETED", "并发结果上报冲突");
            }
            Map<String, Object> out = sessionResultView(jdbc.queryForMap(
                    "SELECT * FROM external_game_play_session WHERE session_id=?", sid));
            out.put("attested", attested);
            out.put("replayed", false);
            return out;
        });
    }

    /**
     * Per-game result key a server-side game authority uses to attest results. Derived from the server-held secret;
     * provisioned to the game's own server out of band and never returned to the app.
     */
    public static String gameResultKey(String serverSecret, String gameId) {
        return hmacSha256Hex(serverSecret, "EXTGAME_RESULT_KEY|" + gameId);
    }

    /** W7 GAME-9: creator-provisioned per-game keys, tried before the derived key. Optional. */
    @Autowired(required = false)
    private ExternalGameResultKeyService resultKeys;

    void setResultKeys(ExternalGameResultKeyService resultKeys) {
        this.resultKeys = resultKeys;
    }

    boolean verifyAuthoritySignature(String gameId, String canonical, String signature) {
        String stored = resultKeys == null ? null : resultKeys.activeKey(gameId);
        if (stored != null && constantTimeEquals(hmacSha256Hex(stored, canonical), signature)) {
            return true;
        }
        if (resultSecret != null
                && constantTimeEquals(hmacSha256Hex(gameResultKey(resultSecret, gameId), canonical), signature)) {
            return true;
        }
        return signer != null && signer.configured()
                && signer.verifyHex(canonical.getBytes(StandardCharsets.UTF_8), signature);
    }

    /** CRIT-S1: sessions past the TTL become EXPIRED (own transaction, so the state sticks even though we reject). */
    private void expireIfStale(String sessionId) {
        Timestamp cutoff = new Timestamp(clock.getAsLong() - SESSION_TTL_MS);
        jdbc.update("UPDATE external_game_play_session SET status='EXPIRED' WHERE session_id=? AND status='STARTED' AND started_at < ?",
                sessionId, cutoff);
    }

    /**
     * HIGH-S1: standalone pass XP is idempotent per session (the session row is locked and completes once), granted
     * only for the member's first XP-earning pass of this game today, and never beyond the daily cap.
     */
    private boolean standaloneXpAllowed(long memberId, String gameId, Date playDate) {
        // Serialise the member's same-day completions so two concurrent sessions cannot both see "first pass".
        jdbc.queryForList("SELECT session_id FROM external_game_play_session WHERE member_id=? AND play_date=? FOR UPDATE",
                memberId, playDate);
        Integer sameGame = jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_game_play_session WHERE member_id=? AND game_id=? AND play_date=? "
                        + "AND node_id IS NULL AND status='COMPLETED' AND xp_awarded>0",
                Integer.class, memberId, gameId, playDate);
        if (sameGame != null && sameGame > 0) return false;
        Long today = jdbc.queryForObject(
                "SELECT COALESCE(SUM(xp_awarded),0) FROM external_game_play_session WHERE member_id=? AND play_date=? "
                        + "AND node_id IS NULL AND status='COMPLETED'",
                Long.class, memberId, playDate);
        return today == null || today < STANDALONE_DAILY_XP_CAP;
    }

    private static long toEpochMs(Object o) {
        if (o instanceof Timestamp t) return t.getTime();
        if (o instanceof java.util.Date d) return d.getTime();
        if (o instanceof java.time.LocalDateTime ldt) return Timestamp.valueOf(ldt).getTime();
        return Timestamp.valueOf(String.valueOf(o)).getTime();
    }

    private record TrafficEligibility(boolean eligible, String fraudReason) {}

    private TrafficEligibility evaluateTrafficEligibility(String gameId, long creatorId, long memberId,
                                                          Date playDate, boolean passed, long durationMs) {
        List<Map<String, Object>> cfgs = jdbc.queryForList(
                "SELECT traffic_reward_mode, review_status, daily_user_cap, daily_game_cap, min_duration_ms "
                        + "FROM external_game_monetization_config WHERE game_id=?", gameId);
        if (cfgs.isEmpty()) {
            return new TrafficEligibility(false, "NO_TRAFFIC_CONFIG");
        }
        Map<String, Object> cfg = cfgs.get(0);
        if (!"APPROVED".equals(cfg.get("review_status")) || "NONE".equals(cfg.get("traffic_reward_mode"))) {
            return new TrafficEligibility(false, "TRAFFIC_REWARD_DISABLED");
        }
        if (memberId == creatorId) {
            return new TrafficEligibility(false, "SELF_PLAY");
        }
        int minDur = (int) num(cfg.get("min_duration_ms"));
        if (durationMs < minDur) {
            return new TrafficEligibility(false, "DURATION_TOO_SHORT");
        }
        int userCap = (int) num(cfg.get("daily_user_cap"));
        if (userCap <= 0) {
            return new TrafficEligibility(false, "USER_DAILY_CAP");
        }
        Integer userToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_game_play_session WHERE game_id=? AND member_id=? AND play_date=? AND traffic_eligible=1",
                Integer.class, gameId, memberId, playDate);
        if (userToday != null && userToday >= userCap) {
            return new TrafficEligibility(false, "USER_DAILY_CAP");
        }
        int gameCap = (int) num(cfg.get("daily_game_cap"));
        if (gameCap <= 0) {
            return new TrafficEligibility(false, "GAME_DAILY_CAP");
        }
        Integer gameToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_game_play_session WHERE game_id=? AND play_date=? AND traffic_eligible=1",
                Integer.class, gameId, playDate);
        if (gameToday != null && gameToday >= gameCap) {
            return new TrafficEligibility(false, "GAME_DAILY_CAP");
        }
        return new TrafficEligibility(true, null);
    }

    // ================= Daily Traffic Settlement =================

    public Map<String, Object> settleDailyTraffic(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(SHANGHAI).minusDays(1) : date;
        Date sqlDate = Date.valueOf(target);

        List<Map<String, Object>> stats = jdbc.queryForList(
                "SELECT game_id, MAX(creator_member_id) AS creator_member_id, COUNT(*) AS total_plays, "
                        + "SUM(CASE WHEN traffic_eligible=1 THEN 1 ELSE 0 END) AS valid_plays "
                        + "FROM external_game_play_session "
                        + "WHERE play_date=? AND status='COMPLETED' GROUP BY game_id ORDER BY game_id",
                sqlDate);

        int settledCount = 0;
        int deferredCount = 0;
        int skippedCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        for (Map<String, Object> row : stats) {
            String gameId = String.valueOf(row.get("game_id"));
            long creatorId = num(row.get("creator_member_id"));
            int totalPlays = (int) num(row.get("total_plays"));
            int validPlays = (int) num(row.get("valid_plays"));
            int cappedPlays = Math.max(0, totalPlays - validPlays);

            Map<String, Object> settled = tx.execute(st -> settleOneGameDate(
                    sqlDate, gameId, creatorId, totalPlays, validPlays, cappedPlays));
            items.add(settled);
            String s = String.valueOf(settled.get("status"));
            if ("SETTLED".equals(s)) settledCount++;
            else if ("DEFERRED_MONEY_CLOSED".equals(s)) deferredCount++;
            else skippedCount++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settlementDate", target.toString());
        out.put("settledCount", settledCount);
        out.put("deferredCount", deferredCount);
        out.put("skippedCount", skippedCount);
        out.put("payoutGateOpen", payoutOpen());
        out.put("items", items);
        return out;
    }

    private Map<String, Object> settleOneGameDate(Date sqlDate, String gameId, long creatorId,
                                                  int totalPlays, int validPlays, int cappedPlays) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM external_game_daily_settlement WHERE settlement_date=? AND game_id=? FOR UPDATE",
                sqlDate, gameId);
        if (!existing.isEmpty() && !"DEFERRED_MONEY_CLOSED".equals(existing.get(0).get("status"))) {
            return settlementView(existing.get(0));
        }

        List<Map<String, Object>> cfgs = jdbc.queryForList(
                "SELECT traffic_reward_mode, reward_per_play_points, reward_per_play_fen, review_status "
                        + "FROM external_game_monetization_config WHERE game_id=?", gameId);
        String mode = cfgs.isEmpty() ? "NONE" : String.valueOf(cfgs.get(0).get("traffic_reward_mode"));
        boolean cfgApproved = !cfgs.isEmpty() && "APPROVED".equals(cfgs.get(0).get("review_status"));
        int perPlayPoints = cfgs.isEmpty() ? 0 : (int) num(cfgs.get(0).get("reward_per_play_points"));
        long perPlayFen = cfgs.isEmpty() ? 0L : num(cfgs.get(0).get("reward_per_play_fen"));

        String status = "SKIPPED";
        int points = 0;
        long fen = 0L;
        String note = null;
        String bKey = "extgame:traffic:" + sqlDate + ":" + gameId;

        if (!cfgApproved || validPlays <= 0 || "NONE".equals(mode)) {
            note = validPlays <= 0 ? "NO_VALID_PLAYS" : "TRAFFIC_REWARD_DISABLED";
        } else if ("POINTS".equals(mode)) {
            points = validPlays * perPlayPoints;
            if (points > 0) {
                status = "SETTLED";
                if (walletBridge != null) {
                    // G3: creator traffic points stay inside the points-governance caps (event_type 99, PLAY).
                    ExternalGameWalletBridge.PointsCredit credit = walletBridge.creditPointsGoverned(
                            creatorId, points, bKey, "外部游戏流量日结积分: " + gameId + " (" + sqlDate + ")");
                    if (credit.credited() <= 0) {
                        status = "SKIPPED";
                        note = "POINTS_GOVERNANCE_" + credit.outcome();
                        points = 0;
                    }
                }
            } else {
                note = "ZERO_RATE";
            }
        } else if ("TEST_MONEY".equals(mode)) {
            fen = validPlays * perPlayFen;
            if (fen <= 0) {
                note = "ZERO_RATE";
            } else if (!payoutOpen()) {
                status = "DEFERRED_MONEY_CLOSED";
                fen = 0L;
                note = "PAYOUT_SWITCH_CLOSED";
            } else {
                if (walletBridge != null) {
                    walletBridge.creditBalanceFen(creatorId, fen, bKey, "外部游戏流量日结激励(TEST): " + gameId + " (" + sqlDate + ")");
                }
                status = "SETTLED";
            }
        }

        Timestamp now = new Timestamp(clock.getAsLong());
        if (existing.isEmpty()) {
            jdbc.update(
                    "INSERT INTO external_game_daily_settlement("
                            + "settlement_date, game_id, creator_member_id, total_plays, valid_plays, capped_plays,"
                            + "reward_mode, settled_points, settled_fen, status, note, settled_at) "
                            + "VALUES(?,?,?,?,?,?, ?,?,?,?,?,?)",
                    sqlDate, gameId, creatorId, totalPlays, validPlays, cappedPlays,
                    mode, points, fen, status, note, now);
        } else {
            jdbc.update(
                    "UPDATE external_game_daily_settlement SET total_plays=?, valid_plays=?, capped_plays=?,"
                            + "reward_mode=?, settled_points=?, settled_fen=?, status=?, note=?, settled_at=? "
                            + "WHERE settlement_date=? AND game_id=?",
                    totalPlays, validPlays, cappedPlays, mode, points, fen, status, note, now, sqlDate, gameId);
        }
        return settlementView(jdbc.queryForMap(
                "SELECT * FROM external_game_daily_settlement WHERE settlement_date=? AND game_id=?", sqlDate, gameId));
    }

    // ================= Creator Summary & Admin Endpoints =================

    public Map<String, Object> creatorSummary(long creatorId) {
        requireActor(creatorId);
        List<Map<String, Object>> configs = jdbc.queryForList(
                "SELECT c.*, COALESCE(s.name, c.game_id) AS game_name "
                        + "FROM external_game_monetization_config c "
                        + "LEFT JOIN external_game g ON g.game_id = c.game_id "
                        + "LEFT JOIN external_game_submission s ON s.id = g.approved_submission_id "
                        + "WHERE c.owner_member_id=? ORDER BY c.update_time DESC",
                creatorId);
        Map<String, Object> orderTotals = jdbc.queryForMap(
                "SELECT COUNT(*) AS paid_orders, COALESCE(SUM(amount_fen),0) AS gross_fen, "
                        + "COALESCE(SUM(creator_net_fen),0) AS creator_net_fen "
                        + "FROM external_game_order WHERE seller_id=? AND state='PAID'",
                creatorId);
        Map<String, Object> trafficTotals = jdbc.queryForMap(
                "SELECT COALESCE(SUM(valid_plays),0) AS valid_plays, COALESCE(SUM(settled_points),0) AS settled_points, "
                        + "COALESCE(SUM(settled_fen),0) AS settled_fen "
                        + "FROM external_game_daily_settlement WHERE creator_member_id=? AND status='SETTLED'",
                creatorId);

        List<Map<String, Object>> settlementRows = jdbc.queryForList(
                "SELECT * FROM external_game_daily_settlement WHERE creator_member_id=? ORDER BY settlement_date DESC, id DESC LIMIT 30",
                creatorId);
        List<Map<String, Object>> settlements = new ArrayList<>();
        for (Map<String, Object> r : settlementRows) settlements.add(settlementView(r));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paidOrders", (int) num(orderTotals.get("paid_orders")));
        out.put("grossSalesFen", num(orderTotals.get("gross_fen")));
        out.put("creatorSalesNetFen", num(orderTotals.get("creator_net_fen")));
        out.put("trafficValidPlays", (int) num(trafficTotals.get("valid_plays")));
        out.put("trafficSettledPoints", (int) num(trafficTotals.get("settled_points")));
        out.put("trafficSettledFen", num(trafficTotals.get("settled_fen")));
        out.put("moneyGateOpen", paymentsOpen());
        out.put("payoutGateOpen", payoutOpen());
        out.put("configs", configs.stream().map(r -> configView(r, true)).toList());
        out.put("recentSettlements", settlements);
        return out;
    }

    public Map<String, Object> adminListConfigs(String reviewStatus, Integer pageNum, Integer pageSize) {
        int size = pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        int page = pageNum == null ? 1 : Math.max(1, pageNum);
        int offset = (page - 1) * size;
        String filter = trimOrNull(reviewStatus);

        List<Map<String, Object>> rows;
        Integer total;
        if (filter == null || "ALL".equalsIgnoreCase(filter)) {
            total = jdbc.queryForObject("SELECT COUNT(*) FROM external_game_monetization_config", Integer.class);
            rows = jdbc.queryForList(
                    "SELECT c.*, COALESCE(s.name, c.game_id) AS game_name "
                            + "FROM external_game_monetization_config c "
                            + "LEFT JOIN external_game g ON g.game_id = c.game_id "
                            + "LEFT JOIN external_game_submission s ON s.id = g.approved_submission_id "
                            + "ORDER BY FIELD(c.review_status,'PENDING','REJECTED','DRAFT','APPROVED'), c.update_time DESC LIMIT ? OFFSET ?",
                    size, offset);
        } else {
            total = jdbc.queryForObject("SELECT COUNT(*) FROM external_game_monetization_config WHERE review_status=?",
                    Integer.class, filter.toUpperCase(Locale.ROOT));
            rows = jdbc.queryForList(
                    "SELECT c.*, COALESCE(s.name, c.game_id) AS game_name "
                            + "FROM external_game_monetization_config c "
                            + "LEFT JOIN external_game g ON g.game_id = c.game_id "
                            + "LEFT JOIN external_game_submission s ON s.id = g.approved_submission_id "
                            + "WHERE c.review_status=? ORDER BY c.update_time DESC LIMIT ? OFFSET ?",
                    filter.toUpperCase(Locale.ROOT), size, offset);
        }
        return Map.of(
                "total", total == null ? 0 : total,
                "moneyGateOpen", paymentsOpen(),
                "payoutGateOpen", payoutOpen(),
                "rows", rows.stream().map(r -> configView(r, true)).toList());
    }

    public Map<String, Object> adminReviewConfig(Long adminId, String gameId, Map<String, Object> body) {
        String gId = requireGameId(gameId);
        Map<String, Object> p = body == null ? Map.of() : body;
        String action = trimOrNull(str(p.get("decision"), str(p.get("action"), null)));
        if (action == null) throw AppGapException.badRequest("decision");
        String upper = action.toUpperCase(Locale.ROOT);
        if (!"APPROVED".equals(upper) && !"APPROVE".equals(upper) && !"REJECTED".equals(upper) && !"REJECT".equals(upper)) {
            throw AppGapException.badRequest("decision");
        }
        String newStatus = ("APPROVED".equals(upper) || "APPROVE".equals(upper)) ? "APPROVED" : "REJECTED";
        String note = trimOrNull(str(p.get("reviewNote"), str(p.get("reason"), null)));
        if ("REJECTED".equals(newStatus) && note == null) {
            throw new AppGapException(400, "REASON_REQUIRED", "驳回变现配置必须填写原因");
        }

        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM external_game_monetization_config WHERE game_id=? FOR UPDATE", gId);
            if (rows.isEmpty()) throw AppGapException.notFound("CONFIG_NOT_FOUND");
            Map<String, Object> cur = rows.get(0);

            int bps = p.containsKey("creatorShareBps") ? intVal(p.get("creatorShareBps"), (int) num(cur.get("creator_share_bps"))) : (int) num(cur.get("creator_share_bps"));
            int bearer = p.containsKey("channelFeeBearer") ? intVal(p.get("channelFeeBearer"), (int) num(cur.get("channel_fee_bearer"))) : (int) num(cur.get("channel_fee_bearer"));
            String trafficMode = p.containsKey("trafficRewardMode")
                    ? normalizeEnum(str(p.get("trafficRewardMode"), String.valueOf(cur.get("traffic_reward_mode"))), TRAFFIC_MODES, "trafficRewardMode")
                    : String.valueOf(cur.get("traffic_reward_mode"));
            int pts = p.containsKey("rewardPerPlayPoints") ? intVal(p.get("rewardPerPlayPoints"), (int) num(cur.get("reward_per_play_points"))) : (int) num(cur.get("reward_per_play_points"));
            long fen = p.containsKey("rewardPerPlayFen") ? longVal(p.get("rewardPerPlayFen"), num(cur.get("reward_per_play_fen"))) : num(cur.get("reward_per_play_fen"));
            int userCap = p.containsKey("dailyUserCap") ? intVal(p.get("dailyUserCap"), (int) num(cur.get("daily_user_cap"))) : (int) num(cur.get("daily_user_cap"));
            int gameCap = p.containsKey("dailyGameCap") ? intVal(p.get("dailyGameCap"), (int) num(cur.get("daily_game_cap"))) : (int) num(cur.get("daily_game_cap"));
            int minDur = p.containsKey("minDurationMs") ? intVal(p.get("minDurationMs"), (int) num(cur.get("min_duration_ms"))) : (int) num(cur.get("min_duration_ms"));

            validateConfigNumbers(bps, bearer, pts, fen, userCap, gameCap, minDur);
            Timestamp now = nowTs();
            jdbc.update(
                    "UPDATE external_game_monetization_config SET creator_share_bps=?, channel_fee_bearer=?, traffic_reward_mode=?,"
                            + "reward_per_play_points=?, reward_per_play_fen=?, daily_user_cap=?, daily_game_cap=?, min_duration_ms=?,"
                            + "review_status=?, review_note=?, reviewer_id=?, reviewed_time=?, update_time=? WHERE game_id=?",
                    bps, bearer, trafficMode, pts, fen, userCap, gameCap, minDur,
                    newStatus, note, adminId, now, now, gId);
            return configView(jdbc.queryForMap("SELECT * FROM external_game_monetization_config WHERE game_id=?", gId), true);
        });
    }

    public Map<String, Object> adminUpdateConfig(Long adminId, String gameId, Map<String, Object> body) {
        Map<String, Object> merged = new LinkedHashMap<>(body == null ? Map.of() : body);
        merged.put("decision", "APPROVED");
        return adminReviewConfig(adminId, gameId, merged);
    }

    public Map<String, Object> adminSettlements(String settlementDate, String gameId, Integer pageNum, Integer pageSize) {
        int size = pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        int page = pageNum == null ? 1 : Math.max(1, pageNum);
        int offset = (page - 1) * size;
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String gId = trimOrNull(gameId);
        String sDate = trimOrNull(settlementDate);
        if (gId != null) { where.append(" AND game_id=?"); args.add(gId); }
        if (sDate != null) { where.append(" AND settlement_date=?"); args.add(Date.valueOf(LocalDate.parse(sDate))); }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM external_game_daily_settlement " + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM external_game_daily_settlement " + where + " ORDER BY settlement_date DESC, id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return Map.of(
                "total", total == null ? 0 : total,
                "payoutGateOpen", payoutOpen(),
                "rows", rows.stream().map(this::settlementView).toList());
    }

    // ================= View Mappers & Helpers =================

    private Map<String, Object> configView(Map<String, Object> row, boolean includePrivate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", row.get("game_id"));
        if (row.containsKey("game_name")) m.put("gameName", row.get("game_name"));
        m.put("pricingMode", row.get("pricing_mode"));
        m.put("priceFen", num(row.get("price_fen")));
        m.put("currency", row.get("currency"));
        m.put("workshopVersionId", row.get("workshop_version_id"));
        m.put("creatorShareBps", (int) num(row.get("creator_share_bps")));
        m.put("channelFeeBearer", (int) num(row.get("channel_fee_bearer")));
        m.put("trafficRewardMode", row.get("traffic_reward_mode"));
        m.put("rewardPerPlayPoints", (int) num(row.get("reward_per_play_points")));
        m.put("rewardPerPlayFen", num(row.get("reward_per_play_fen")));
        m.put("dailyUserCap", (int) num(row.get("daily_user_cap")));
        m.put("dailyGameCap", (int) num(row.get("daily_game_cap")));
        m.put("minDurationMs", (int) num(row.get("min_duration_ms")));
        m.put("reviewStatus", row.get("review_status"));
        m.put("configVersion", num(row.get("config_version")));
        if (includePrivate) {
            m.put("ownerMemberId", num(row.get("owner_member_id")));
            m.put("reviewNote", row.get("review_note"));
            m.put("reviewedTime", row.get("reviewed_time") == null ? null : row.get("reviewed_time").toString());
        }
        return m;
    }

    private Map<String, Object> orderView(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", row.get("order_no"));
        m.put("gameId", row.get("game_id"));
        m.put("amountFen", num(row.get("amount_fen")));
        m.put("creatorShareBps", (int) num(row.get("creator_share_bps")));
        m.put("channelFeeBearer", (int) num(row.get("channel_fee_bearer")));
        m.put("channelFeeFen", num(row.get("channel_fee_fen")));
        m.put("creatorNetFen", num(row.get("creator_net_fen")));
        m.put("platformShareFen", num(row.get("platform_share_fen")));
        m.put("currency", row.get("currency"));
        m.put("provider", row.get("provider"));
        m.put("state", row.get("state"));
        m.put("licenseStatus", row.get("license_status"));
        m.put("paidAt", row.get("paid_at") == null ? null : row.get("paid_at").toString());
        return m;
    }

    private Map<String, Object> sessionResultView(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", row.get("session_id"));
        m.put("gameId", row.get("game_id"));
        m.put("nodeId", nullableLong(row.get("node_id")));
        m.put("status", row.get("status"));
        m.put("passed", num(row.get("passed")) == 1L);
        m.put("score", row.get("score") == null ? null : (int) num(row.get("score")));
        m.put("durationMs", num(row.get("duration_ms")));
        m.put("xpAwarded", (int) num(row.get("xp_awarded")));
        m.put("medalRank", row.get("medal_rank") == null ? null : (int) num(row.get("medal_rank")));
        m.put("nodeCompleted", num(row.get("node_completed")) == 1L);
        m.put("trafficEligible", num(row.get("traffic_eligible")) == 1L);
        m.put("fraudReason", row.get("fraud_reason"));
        m.put("resultDigest", row.get("result_digest"));
        return m;
    }

    private Map<String, Object> settlementView(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settlementDate", String.valueOf(row.get("settlement_date")));
        m.put("gameId", row.get("game_id"));
        m.put("creatorMemberId", num(row.get("creator_member_id")));
        m.put("totalPlays", (int) num(row.get("total_plays")));
        m.put("validPlays", (int) num(row.get("valid_plays")));
        m.put("cappedPlays", (int) num(row.get("capped_plays")));
        m.put("rewardMode", row.get("reward_mode"));
        m.put("settledPoints", (int) num(row.get("settled_points")));
        m.put("settledFen", num(row.get("settled_fen")));
        m.put("status", row.get("status"));
        m.put("note", row.get("note"));
        m.put("settledAt", row.get("settled_at") == null ? null : row.get("settled_at").toString());
        return m;
    }

    private Map<String, Object> lockGame(String gameId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM external_game WHERE game_id=? FOR UPDATE", gameId);
        if (rows.isEmpty()) throw AppGapException.notFound("EXTERNAL_GAME_NOT_FOUND");
        return rows.get(0);
    }

    private static void validateConfigNumbers(int bps, int bearer, int pts, long fen, int userCap, int gameCap, int minDur) {
        if (bps < 0 || bps > 10000) throw AppGapException.badRequest("creatorShareBps");
        if (bearer != 0 && bearer != 1) throw AppGapException.badRequest("channelFeeBearer");
        if (pts < 0 || fen < 0 || userCap < 0 || gameCap < 0 || minDur < 0) throw AppGapException.badRequest("trafficConfig");
    }

    private Timestamp nowTs() { return new Timestamp(clock.getAsLong()); }

    private static void requireActor(Long memberId) {
        if (memberId == null || memberId <= 0) throw AppGapException.unauthorized();
    }

    private static String requireGameId(String gameId) {
        String s = trimOrNull(gameId);
        if (s == null || s.length() > 64 || !s.matches("^[A-Za-z0-9._-]+$")) {
            throw AppGapException.badRequest("gameId");
        }
        return s;
    }

    private static String requireKey(String val, String field) {
        String s = trimOrNull(val);
        if (s == null || s.length() < 8 || s.length() > 80 || !s.matches("^[A-Za-z0-9._:-]+$")) {
            throw AppGapException.badRequest(field);
        }
        return s;
    }

    private static String normalizeEnum(String val, Set<String> allowed, String field) {
        String s = trimOrNull(val);
        if (s == null) throw AppGapException.badRequest(field);
        String upper = s.toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) throw AppGapException.badRequest(field);
        return upper;
    }

    private static String str(Object o, String fallback) { return o == null ? fallback : String.valueOf(o); }
    private static String trimOrNull(String s) { if (s == null) return null; String t = s.trim(); return t.isEmpty() ? null : t; }
    private static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof Boolean b) return b ? 1L : 0L;
        String s = String.valueOf(o).trim();
        if ("true".equalsIgnoreCase(s)) return 1L;
        if ("false".equalsIgnoreCase(s)) return 0L;
        return Long.parseLong(s);
    }
    private static Long nullableLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof Boolean b) return b ? 1L : 0L;
        String t = trimOrNull(String.valueOf(o));
        return t == null ? null : Long.parseLong(t);
    }
    private static long longVal(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b ? 1L : 0L;
        try { return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { throw AppGapException.badRequest("number"); }
    }
    private static int intVal(Object o, int def) {
        long v = longVal(o, def);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw AppGapException.badRequest("int");
        return (int) v;
    }
    private static boolean boolVal(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.longValue() != 0L;
        String s = String.valueOf(o).trim();
        if ("1".equals(s)) return true;
        if ("0".equals(s)) return false;
        return Boolean.parseBoolean(s);
    }
    private static Date toSqlDate(Object o) {
        if (o instanceof Date d) return d;
        if (o instanceof LocalDate ld) return Date.valueOf(ld);
        return Date.valueOf(String.valueOf(o).substring(0, 10));
    }
    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return hex(buf);
    }
    private static String sha256Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(Character.forDigit((b >>> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                b.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
