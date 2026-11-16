package com.chengyinhub.business.shopgames;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.locationtrust.LocationSignals;
import com.chengyinhub.business.locationtrust.LocationTrustKind;
import com.chengyinhub.business.locationtrust.LocationTrustMessages;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.support.RewardPresencePolicy;
import com.chengyinhub.common.utils.GeoDistanceUtils;
import com.chengyinhub.system.service.ISysConfigService;

/**
 * S5 shop games: launching a shop's game for a player at the shop, and recording the result.
 *
 * <ul>
 *   <li>One play per player, shop and local day. Launching again the same day returns that play, so a player
 *       cannot re-roll for an easier game; the game is picked from the shop's ready games by a fixed rotation of
 *       (player, day), never by the player, the plan or a sponsor.</li>
 *   <li>Location: a roam check-in launches only after the check-in's own location trust passed. Other launches
 *       (journey shop node, City Monopoly landing) score the fix with {@link LocationTrustKind#SHOP_VISIT} and need
 *       the player within the place's radius plus {@link #RADIUS_SLACK_M}.</li>
 *   <li>A win ({@link ShopGameCatalog#PASS_ROUNDS} of {@link ShopGameCatalog#ROUNDS}) awards points only through
 *       {@link IPointsAwardService#award}, which applies the points governance daily cap and monthly budget.</li>
 *   <li>W7 GAME-8 / MERCHANT-5: the switch {@value #KEY_PLAY_ENABLED} (sys_config, default {@code false}) opens shop
 *       games. While it is off no play row is written and {@code result} answers 409 {@code SHOP_GAME_NOT_OPEN}.</li>
 *   <li>The result is judged by the server: {@code finish} reads the player's own advanced runtime session on the
 *       play's template, started after the launch. Rounds sent by the client are ignored.</li>
 *   <li>Owner decision 2 (2026-11-16, "progress is free, prizes need presence"): a judged win always finishes the
 *       play, but points need a fix within {@link RewardPresencePolicy} of the shop; device-reported games
 *       ({@link ShopGameCatalog.Game#deviceReported()}) and runtime-capped evidence pay no points.</li>
 * </ul>
 */
@Service
public class ShopGamePlayService {

    public static final String SOURCE_ROAM_CHECKIN = "ROAM_CHECKIN";
    public static final String SOURCE_JOURNEY_NODE = "JOURNEY_NODE";
    public static final String SOURCE_CITY_LANDING = "CITY_LANDING";
    /** W7: the place page's own "play today's game" button; same location trust and radius as a landing. */
    public static final String SOURCE_PLACE_PAGE = "PLACE_PAGE";
    public static final List<String> SOURCES = List.of(SOURCE_ROAM_CHECKIN, SOURCE_JOURNEY_NODE, SOURCE_CITY_LANDING,
            SOURCE_PLACE_PAGE);
    /** sys_config switch; anything but {@code true} keeps shop games closed (safe default). */
    public static final String KEY_PLAY_ENABLED = "shopgames.play.enabled";
    public static final String REASON_NOT_OPEN = "NOT_OPEN";
    public static final int PLAY_MINUTES = 30;
    public static final int RADIUS_SLACK_M = 50;
    public static final int DEFAULT_RADIUS_M = 80;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(ShopGamePlayService.class);

    private final JdbcTemplate jdbc;
    private final ShopGameProfileService profiles;
    @Autowired(required = false)
    private LocationTrustService locationTrust;
    @Autowired(required = false)
    private IPointsAwardService points;
    @Autowired(required = false)
    private IAdvancedGameRuntimeService runtime;
    @Autowired(required = false)
    private ISysConfigService sysConfig;
    private Function<String, String> configLookup;
    private Clock clock = Clock.system(ZONE);

    public ShopGamePlayService(JdbcTemplate jdbc, ShopGameProfileService profiles) {
        this.jdbc = jdbc;
        this.profiles = profiles;
    }

    /** For tests. */
    ShopGamePlayService(JdbcTemplate jdbc, ShopGameProfileService profiles, LocationTrustService trust,
                        IPointsAwardService points, Clock clock) {
        this(jdbc, profiles);
        this.locationTrust = trust;
        this.points = points;
        this.clock = clock;
    }

    /** For tests: sys_config values and the runtime reward-cap check. */
    ShopGamePlayService withConfig(Function<String, String> lookup, IAdvancedGameRuntimeService runtimeService) {
        this.configLookup = lookup;
        this.runtime = runtimeService;
        return this;
    }

    private String config(String key) {
        try {
            if (configLookup != null) return configLookup.apply(key);
            return sysConfig == null ? null : sysConfig.selectConfigByKey(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether shop games are open. Missing, unreadable or anything but "true" means closed. */
    public boolean playOpen() {
        String v = config(KEY_PLAY_ENABLED);
        return v != null && "true".equalsIgnoreCase(v.trim());
    }

    /** Which ready game this player gets at this shop today: a fixed rotation of (player, shop, day). Pure. */
    public static int pick(long memberId, long merchantId, String day, int readyCount) {
        if (readyCount <= 0) return -1;
        return Math.floorMod((memberId + "|" + merchantId + "|" + day).hashCode(), readyCount);
    }

    String today() { return LocalDate.now(clock).toString(); }

    /**
     * Launch after a roam check-in that already passed location trust and the shop radius. Never throws: a
     * problem gives {@code available=false} with a reason, and the check-in itself is unaffected.
     */
    public Map<String, Object> launchAfterCheckin(long memberId, long poiId) {
        if (!playOpen()) return unavailable(poiId, REASON_NOT_OPEN);
        try {
            return issue(memberId, poiId, SOURCE_ROAM_CHECKIN);
        } catch (RuntimeException e) {
            log.warn("shop game launch after check-in failed poi={} reason={}", poiId, e.getClass().getSimpleName());
            return unavailable(poiId, "UNAVAILABLE");
        }
    }

    /** Launch from a journey shop node or a City Monopoly landing: scores the fix and checks the distance first. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> launch(Long memberId, Map<String, Object> body) {
        if (memberId == null || memberId <= 0) throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        if (body == null) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        Long poiArg = body.get("poiId") == null ? null : positive(body.get("poiId"));
        Long merchantArg = body.get("merchantId") == null ? null : positive(body.get("merchantId"));
        if ((poiArg == null) == (merchantArg == null)) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        long poiId = poiArg == null ? 0L : poiArg;
        String source = body.get("source") == null ? "" : body.get("source").toString().trim().toUpperCase(Locale.ROOT);
        if (!SOURCE_JOURNEY_NODE.equals(source) && !SOURCE_CITY_LANDING.equals(source) && !SOURCE_PLACE_PAGE.equals(source)) {
            throw new MerchantGapException(400, "INVALID_SOURCE", "请求无效");
        }
        if (!playOpen()) return unavailable(poiId, REASON_NOT_OPEN);
        Double lat = coordinate(body.get("lat"), 90);
        Double lng = coordinate(body.get("lng"), 180);
        if (lat == null || lng == null) throw new MerchantGapException(400, "LOCATION_REQUIRED", "需要定位才能开始这家店的小游戏");
        Map<String, Object> place = poiArg != null ? place(poiId) : shopPlace(merchantArg);
        if (place == null) return unavailable(poiId, "NOT_A_SHOP");
        if (locationTrust != null) {
            LocationTrustService.Assessment a = locationTrust.assess(memberId, null, LocationTrustKind.SHOP_VISIT,
                    poiArg != null ? "shopgame:poi:" + poiId : "shopgame:shop:" + merchantArg, LocationSignals.current(lat, lng));
            if (a.rejected()) {
                throw new MerchantGapException(422, LocationTrustMessages.code(a.reasons()), LocationTrustMessages.rejected(a.reasons()));
            }
            if (a.withheld()) {
                Map<String, Object> out = unavailable(poiId, "LOCATION_NOT_TRUSTED");
                out.put("trustMessage", LocationTrustMessages.withheld(a.reasons()));
                return out;
            }
        }
        double placeLat = ((Number) place.get("lat")).doubleValue();
        double placeLng = ((Number) place.get("lng")).doubleValue();
        int radius = place.get("radius_m") instanceof Number n && n.intValue() > 0 ? n.intValue() : DEFAULT_RADIUS_M;
        if (GeoDistanceUtils.haversineMeters(lat, lng, placeLat, placeLng) > radius + RADIUS_SLACK_M) {
            Map<String, Object> out = unavailable(poiId, "TOO_FAR");
            out.put("tooFar", true);
            return out;
        }
        return poiArg != null ? issue(memberId, poiId, source) : issueForShop(memberId, merchantArg, source);
    }

    Map<String, Object> issue(long memberId, long poiId, String source) {
        Long merchantId = profiles.merchantOfPoi(poiId);
        if (merchantId == null) return unavailable(poiId, "NOT_A_SHOP");
        return issueAt(memberId, merchantId, poiId > 0 ? poiId : null, source);
    }

    Map<String, Object> issueForShop(long memberId, long merchantId, String source) {
        return issueAt(memberId, merchantId, null, source);
    }

    private Map<String, Object> issueAt(long memberId, long merchantId, Long poiRef, String source) {
        long poiId = poiRef == null ? 0L : poiRef;
        String day = today();
        Map<String, Object> existing = todaysPlay(memberId, merchantId, day);
        if (existing != null) return describe(existing, poiId, true);
        ShopGameProfileService.Resolved r = profiles.resolved(merchantId);
        List<ShopGameProfileService.Entry> ready = new ArrayList<>();
        for (ShopGameProfileService.Entry e : profiles.withTemplates(merchantId, r.games())) {
            if (e.templateId() != null) ready.add(e);
        }
        if (ready.isEmpty()) {
            Map<String, Object> out = unavailable(poiId, "NOT_READY");
            out.put("merchantId", merchantId);
            out.put("games", ShopGameProfileService.gameMaps(profiles.withTemplates(merchantId, r.games())));
            return out;
        }
        ShopGameProfileService.Entry chosen = ready.get(pick(memberId, merchantId, day, ready.size()));
        Instant now = clock.instant();
        Timestamp expires = Timestamp.from(now.plusSeconds(PLAY_MINUTES * 60L));
        try {
            jdbc.update("INSERT INTO cy_shop_game_play (member_id, merchant_id, poi_id, game_key, template_id, source, status,"
                    + " rounds_won, points, play_day, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, 'ISSUED', NULL, 0, ?, ?, ?)",
                    memberId, merchantId, poiRef, chosen.key(), chosen.templateId(), source, day, Timestamp.from(now), expires);
        } catch (DuplicateKeyException raced) {
            // uk (member, shop, day): a parallel launch won; answer with that play.
        }
        Map<String, Object> row = todaysPlay(memberId, merchantId, day);
        if (row == null) throw new IllegalStateException("shop game play not readable after insert");
        return describe(row, poiId, false);
    }

    /**
     * Records the result of the player's own play: {@code {"playId":1,"sessionId":9,"lat":..,"lng":..,"accuracy":..}}.
     * Once per play. The outcome comes from the judged runtime session; a {@code roundsWon} in the body is ignored.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> finish(Long memberId, Map<String, Object> body) {
        if (memberId == null || memberId <= 0) throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        if (body == null) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        if (!playOpen()) throw new MerchantGapException(409, "SHOP_GAME_NOT_OPEN", "店铺小游戏还没开放");
        long playId = positive(body.get("playId"));
        List<Map<String, Object>> rows = jdbc.queryForList(PLAY_COLUMNS + " WHERE id = ? AND member_id = ? FOR UPDATE",
                playId, memberId);
        if (rows.isEmpty()) throw new MerchantGapException(404, "PLAY_NOT_FOUND", "没有找到这局小游戏");
        Map<String, Object> row = rows.get(0);
        String status = String.valueOf(row.get("status"));
        if (!"ISSUED".equals(status)) return result(row, true);
        Instant expires = instant(row.get("expires_at"));
        if (clock.instant().isAfter(expires)) {
            // Answered, not thrown: a throw would roll the EXPIRED mark back.
            jdbc.update("UPDATE cy_shop_game_play SET status = 'EXPIRED', finished_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ISSUED'", playId);
            Map<String, Object> out = result(row, false);
            out.put("status", "EXPIRED");
            out.put("won", false);
            out.put("points", 0);
            return out;
        }
        Judged judged = judge(memberId, row, body.get("sessionId"));
        boolean won = judged.won();
        String rewardCeilingReason = null;
        if (won) rewardCeilingReason = rewardCeiling(row, judged, body);
        int awarded = 0;
        if (won && rewardCeilingReason == null && points != null) {
            awarded = Math.max(0, points.award(memberId, ShopGameCatalog.POINTS_EVENT_TYPE, playId, "店铺小游戏"));
        }
        int roundsWon = won ? ShopGameCatalog.PASS_ROUNDS : 0;
        int n2 = jdbc.update("UPDATE cy_shop_game_play SET status = ?, rounds_won = ?, points = ?, finished_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'ISSUED'", won ? "WON" : "LOST", roundsWon, awarded, playId);
        if (n2 != 1) throw new IllegalStateException("shop game play changed while finishing");
        Map<String, Object> after = jdbc.queryForList(PLAY_COLUMNS + " WHERE id = ?", playId).get(0);
        Map<String, Object> out = result(after, false);
        out.put("capped", won && rewardCeilingReason == null && awarded == 0);
        out.put("rewardCeilingReason", rewardCeilingReason);
        return out;
    }

    /**
     * The server's verdict on a play: the runtime session that played its template. {@code untrusted} is set for a
     * shop session (W7 x-shop-game-runner), whose evidence check needs no journey ids.
     */
    record Judged(boolean won, Long activityId, Long topicId, Long nodeId, List<String> untrusted) {
        Judged(boolean won, Long activityId, Long topicId, Long nodeId) { this(won, activityId, topicId, nodeId, null); }
    }

    static final String SESSION_COLUMNS = "SELECT id, status, activity_id, topic_id, node_id, started_at FROM game_runtime_session";

    private Judged judge(long memberId, Map<String, Object> play, Object sessionArg) {
        Judged shop = judgeShopSession(memberId, play);
        if (shop != null) return shop;
        if (!(sessionArg instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() <= 0) {
            throw new MerchantGapException(409, "PLAY_NOT_JUDGED", "这局小游戏还没有结果");
        }
        Object tpl = play.get("template_id");
        if (!(tpl instanceof Number t)) throw new MerchantGapException(409, "PLAY_NOT_JUDGED", "这局小游戏还没有结果");
        List<Map<String, Object>> sessions = jdbc.queryForList(SESSION_COLUMNS
                + " WHERE id = ? AND owner_type = 1 AND owner_id = ? AND template_id = ?", n.longValue(), memberId, t.longValue());
        if (sessions.isEmpty()) throw new MerchantGapException(409, "PLAY_NOT_JUDGED", "这局小游戏还没有结果");
        Map<String, Object> session = sessions.get(0);
        Object started = session.get("started_at");
        if (started == null || instant(started).isBefore(instant(play.get("issued_at")))) {
            throw new MerchantGapException(409, "PLAY_NOT_JUDGED", "这局小游戏还没有结果");
        }
        int st = session.get("status") instanceof Number s ? s.intValue() : -1;
        if (st == 0) throw new MerchantGapException(409, "PLAY_NOT_FINISHED", "这局小游戏还没玩完");
        return new Judged(st == 1, longOrNull(session.get("activity_id")), longOrNull(session.get("topic_id")),
                longOrNull(session.get("node_id")));
    }

    /**
     * W7 x-shop-game-runner: a play started from the place page or a City Monopoly landing is judged from its own
     * shop session ({@code cy_shop_game_session}, bound to the play). Null when the play has none (journey shop node).
     * A running session whose deadline passed counts as lost; a running one before its deadline is not finished.
     */
    private Judged judgeShopSession(long memberId, Map<String, Object> play) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(ShopGameSessionService.SESSION_COLUMNS + " WHERE play_id = ? AND member_id = ?",
                    ((Number) play.get("id")).longValue(), memberId);
        } catch (org.springframework.dao.DataAccessException missingTable) {
            return null;
        }
        if (rows == null || rows.isEmpty()) return null;
        Map<String, Object> session = rows.get(0);
        int st = session.get("status") instanceof Number s ? s.intValue() : -1;
        if (st == 0) {
            Object deadline = session.get("deadline_at");
            if (deadline == null || !clock.instant().isAfter(instant(deadline))) {
                throw new MerchantGapException(409, "PLAY_NOT_FINISHED", "这局小游戏还没玩完");
            }
            return new Judged(false, null, null, null, List.of());
        }
        List<String> untrusted = runtime == null ? List.of("RUNTIME_UNAVAILABLE")
                : runtime.templateUntrustedEvidence(ShopGameSessionService.toSession(session));
        return new Judged(st == 1, null, null, null, untrusted == null ? List.of() : untrusted);
    }

    /**
     * Why a judged win pays no points, or null when it pays. Owner decision 2: prizes need presence at the shop;
     * games the device reports, and runtime evidence the runtime itself caps, pay none.
     */
    private String rewardCeiling(Map<String, Object> play, Judged judged, Map<String, Object> body) {
        ShopGameCatalog.Game g = ShopGameCatalog.game(String.valueOf(play.get("game_key")));
        if (g == null || g.deviceReported()) return "DEVICE_REPORTED";
        if (judged.untrusted() != null) {
            if (!judged.untrusted().isEmpty()) return "UNJUDGED_EVIDENCE";
        } else if (runtime != null && runtime.rewardCappedToXpBadge(play.get("member_id") instanceof Number m ? m.longValue() : null,
                judged.activityId(), judged.topicId(), judged.nodeId())) {
            return "UNJUDGED_EVIDENCE";
        }
        RewardPresencePolicy policy = RewardPresencePolicy.read(this::config);
        if (policy.remotePaysFull()) return null;
        Map<String, Object> place = playPlace(play);
        if (place == null) return RewardPresencePolicy.REASON_REMOTE;
        int radius = place.get("radius_m") instanceof Number r && r.intValue() > 0 ? r.intValue() : 0;
        double rewardRadius = Math.max(RewardPresencePolicy.DEFAULT_RADIUS_METERS, radius);
        boolean present = RewardPresencePolicy.within(coordinate(body.get("lat"), 90), coordinate(body.get("lng"), 180),
                body.get("accuracy") instanceof Number a ? a.doubleValue() : null,
                ((Number) place.get("lat")).doubleValue(), ((Number) place.get("lng")).doubleValue(),
                rewardRadius);
        return present ? null : RewardPresencePolicy.REASON_REMOTE;
    }

    private Map<String, Object> playPlace(Map<String, Object> play) {
        Object poi = play.get("poi_id");
        if (poi instanceof Number p && p.longValue() > 0) {
            Map<String, Object> place = place(p.longValue());
            if (place != null) return place;
        }
        Object merchant = play.get("merchant_id");
        return merchant instanceof Number m ? shopPlace(m.longValue()) : null;
    }

    private static Long longOrNull(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    static final String PLAY_COLUMNS = "SELECT id, member_id, merchant_id, poi_id, game_key, template_id, source, status,"
            + " rounds_won, points, play_day, issued_at, expires_at FROM cy_shop_game_play";

    private Map<String, Object> todaysPlay(long memberId, long merchantId, String day) {
        List<Map<String, Object>> rows = jdbc.queryForList(PLAY_COLUMNS
                + " WHERE member_id = ? AND merchant_id = ? AND play_day = ? ORDER BY id LIMIT 1", memberId, merchantId, day);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> place(long poiId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT lat, lng, radius_m FROM roam_poi WHERE id = ?"
                + " AND merchant_id IS NOT NULL AND merchant_id > 0 AND lat IS NOT NULL AND lng IS NOT NULL", poiId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> shopPlace(long merchantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT location_lat AS lat, location_lng AS lng, NULL AS radius_m"
                + " FROM mms_merchant WHERE id = ? AND location_lat IS NOT NULL AND location_lng IS NOT NULL", merchantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, Object> describe(Map<String, Object> row, long poiId, boolean again) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("playId", ((Number) row.get("id")).longValue());
        out.put("poiId", poiId > 0 ? poiId : null);
        out.put("merchantId", ((Number) row.get("merchant_id")).longValue());
        String key = String.valueOf(row.get("game_key"));
        ShopGameCatalog.Game g = ShopGameCatalog.game(key);
        Map<String, Object> game = g == null ? new LinkedHashMap<>(Map.of("key", key)) : g.toMap();
        game.put("templateId", row.get("template_id"));
        out.put("game", game);
        out.put("source", row.get("source"));
        out.put("status", row.get("status"));
        out.put("playedToday", again && !"ISSUED".equals(String.valueOf(row.get("status"))));
        out.put("expiresAt", instant(row.get("expires_at")).toString());
        out.put("rules", ShopGameCatalog.standardRules());
        return out;
    }

    private static Map<String, Object> result(Map<String, Object> row, boolean already) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playId", ((Number) row.get("id")).longValue());
        out.put("status", row.get("status"));
        out.put("won", "WON".equals(String.valueOf(row.get("status"))));
        out.put("roundsWon", row.get("rounds_won"));
        out.put("points", row.get("points") instanceof Number n ? n.intValue() : 0);
        out.put("alreadyFinished", already);
        return out;
    }

    static Map<String, Object> unavailable(long poiId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("poiId", poiId > 0 ? poiId : null);
        out.put("reason", reason);
        return out;
    }

    private static long positive(Object v) {
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() <= 0) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        }
        return n.longValue();
    }

    private static Double coordinate(Object v, double max) {
        if (!(v instanceof Number n)) return null;
        double d = n.doubleValue();
        return Double.isFinite(d) && Math.abs(d) <= max ? d : null;
    }

    static Instant instant(Object value) {
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof java.time.LocalDateTime l) return l.atZone(ZONE).toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (value instanceof Instant i) return i;
        throw new IllegalStateException("unreadable time " + value);
    }
}
