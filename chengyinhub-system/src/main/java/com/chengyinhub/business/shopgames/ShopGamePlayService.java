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
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.utils.GeoDistanceUtils;

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
 * </ul>
 */
@Service
public class ShopGamePlayService {

    public static final String SOURCE_ROAM_CHECKIN = "ROAM_CHECKIN";
    public static final String SOURCE_JOURNEY_NODE = "JOURNEY_NODE";
    public static final String SOURCE_CITY_LANDING = "CITY_LANDING";
    public static final List<String> SOURCES = List.of(SOURCE_ROAM_CHECKIN, SOURCE_JOURNEY_NODE, SOURCE_CITY_LANDING);
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

    /** Which ready game this player gets at this shop today. Pure. */
    public static int pick(long memberId, String day, int readyCount) {
        if (readyCount <= 0) return -1;
        return Math.floorMod((Long.toString(memberId) + "|" + day).hashCode(), readyCount);
    }

    String today() { return LocalDate.now(clock).toString(); }

    /**
     * Launch after a roam check-in that already passed location trust and the shop radius. Never throws: a
     * problem gives {@code available=false} with a reason, and the check-in itself is unaffected.
     */
    public Map<String, Object> launchAfterCheckin(long memberId, long poiId) {
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
        if (!SOURCE_JOURNEY_NODE.equals(source) && !SOURCE_CITY_LANDING.equals(source)) {
            throw new MerchantGapException(400, "INVALID_SOURCE", "请求无效");
        }
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
        ShopGameProfileService.Entry chosen = ready.get(pick(memberId, day, ready.size()));
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

    /** Records the result of the player's own play: {@code {"playId":1,"roundsWon":2}}. Once per play. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> finish(Long memberId, Map<String, Object> body) {
        if (memberId == null || memberId <= 0) throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        if (body == null) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        long playId = positive(body.get("playId"));
        Object rw = body.get("roundsWon");
        if (!(rw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.intValue() < 0 || n.intValue() > ShopGameCatalog.ROUNDS) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        }
        int roundsWon = n.intValue();
        List<Map<String, Object>> rows = jdbc.queryForList(PLAY_COLUMNS + " WHERE id = ? AND member_id = ? FOR UPDATE",
                playId, memberId);
        if (rows.isEmpty()) throw new MerchantGapException(404, "PLAY_NOT_FOUND", "没有找到这局小游戏");
        Map<String, Object> row = rows.get(0);
        String status = String.valueOf(row.get("status"));
        if (!"ISSUED".equals(status)) return result(row, true);
        Instant expires = instant(row.get("expires_at"));
        if (clock.instant().isAfter(expires)) {
            jdbc.update("UPDATE cy_shop_game_play SET status = 'EXPIRED', finished_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ISSUED'", playId);
            throw new MerchantGapException(409, "PLAY_EXPIRED", "这局小游戏已超时,明天再来试试");
        }
        boolean won = roundsWon >= ShopGameCatalog.PASS_ROUNDS;
        int awarded = 0;
        if (won && points != null) {
            awarded = Math.max(0, points.award(memberId, ShopGameCatalog.POINTS_EVENT_TYPE, playId, "店铺小游戏"));
        }
        int n2 = jdbc.update("UPDATE cy_shop_game_play SET status = ?, rounds_won = ?, points = ?, finished_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'ISSUED'", won ? "WON" : "LOST", roundsWon, awarded, playId);
        if (n2 != 1) throw new IllegalStateException("shop game play changed while finishing");
        Map<String, Object> after = jdbc.queryForList(PLAY_COLUMNS + " WHERE id = ?", playId).get(0);
        Map<String, Object> out = result(after, false);
        out.put("capped", won && awarded == 0);
        return out;
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
