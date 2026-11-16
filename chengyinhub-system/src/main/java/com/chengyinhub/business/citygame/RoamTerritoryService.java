package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.support.UgcSubmissionSafety;
import com.chengyinhub.common.utils.GeoDistanceUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Merged CITY + Roam territory service.
 *
 * <p>Board spaces map to real places ({@code roam_poi.id <-> city_game_point.place_id <-> city_game_property}).
 * Players claim spaces via geotagged photo + GPS check-in within the configured geofence. When other players
 * check in via {@code /api/roam/checkin}, {@code /api/roam/territory/checkin} or CITY arrival confirmation, the
 * holder's yield is paid in city coin through the points-governance credit-only service, after commit
 * (non-cashable, own daily cap, idempotent per check-in; MKT-12 / MON-10). The territory's
 * {@code owner_revenue_points} / {@code revenue_total_points} stay a yield score for the leaderboard; no points are paid.
 * A member who loses a territory to a takeover is told through {@link com.chengyinhub.business.growthloops.ICityEventNotifyService}.
 */
@Service
public class RoamTerritoryService {
    public static final int DEFAULT_RADIUS_M = 80;
    public static final int DEFAULT_PROTECTION_SECONDS = 3600;
    public static final long DEFAULT_TOLL_POINTS = 5L;
    public static final long DEFAULT_REVENUE_POINTS = 5L;
    public static final double MAX_ACCURACY_M = 150.0;
    public static final double MIN_TELEPORT_DIST_M = 500.0;
    public static final double TELEPORT_BLOCK_SPEED_MPS = 45.0;
    public static final double HIGH_SPEED_FLAG_MPS = 20.0;
    public static final long VISITOR_REVENUE_COOLDOWN_MS = 24L * 3600L * 1000L;
    private static final char[] GEOHASH_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

    private final JdbcTemplate jdbc;
    private final CityGameMapper mapper;
    private final UgcSubmissionSafety submissionSafety;
    private final LongSupplier clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.service.impl.InGameChannelService inGameChannelService;
    /** Holder yield (points-governance MON-10). Absent: no yield is paid. */
    @Autowired(required = false)
    private com.chengyinhub.business.citycoin.CityCoinService cityCoinService;
    /** CITY territory pushes (growth-loops GRO-08). Absent: no push. */
    @Autowired(required = false)
    private com.chengyinhub.business.growthloops.ICityEventNotifyService cityNotify;

    public static final String REVENUE_MODE = "CITY_COIN";

    void setCityCoinService(com.chengyinhub.business.citycoin.CityCoinService cityCoinService) {
        this.cityCoinService = cityCoinService;
    }

    void setCityNotify(com.chengyinhub.business.growthloops.ICityEventNotifyService cityNotify) {
        this.cityNotify = cityNotify;
    }

    private CityMonopolyService monopoly;

    @Autowired
    public RoamTerritoryService(JdbcTemplate jdbc, CityGameMapper mapper,
                                @Autowired(required = false) UgcSubmissionSafety submissionSafety) {
        this(jdbc, mapper, submissionSafety, System::currentTimeMillis);
    }

    RoamTerritoryService(JdbcTemplate jdbc, CityGameMapper mapper,
                         UgcSubmissionSafety submissionSafety, LongSupplier clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.submissionSafety = submissionSafety;
        this.clock = clock;
    }

    /**
     * City Monopoly numbers (CITY-MONOPOLY-SPEC). When present and {@code city.monopoly.enabled}, the legacy
     * client-priced claim is closed and every visitor rent goes through the capped, anti-abuse rent engine.
     */
    @Autowired(required = false)
    public void setMonopoly(CityMonopolyService monopoly) {
        this.monopoly = monopoly;
        if (monopoly != null) monopoly.setTerritories(this);
    }

    private boolean monopolyActive(String boardId) {
        return monopoly != null && monopoly.enabled(boardId);
    }

    /**
     * CITY-7 (owner decision 2026-11-16): City Monopoly pays 积分 only, so its boards say POINTS; classic territory keeps
     * the non-cashable city coin ({@link #REVENUE_MODE}).
     */
    String revenueModeFor(String boardId) {
        return monopolyActive(boardId) ? "POINTS" : REVENUE_MODE;
    }

    /** Plausibility evaluation outcome for GPS coordinates and speed jumps. */
    public static final class PlausibilityVerdict {
        public final String status;
        public final String reasonCode;
        public final Double distanceM;
        public final Double impliedSpeedMps;

        PlausibilityVerdict(String status, String reasonCode, Double distanceM, Double impliedSpeedMps) {
            this.status = status;
            this.reasonCode = reasonCode;
            this.distanceM = distanceM;
            this.impliedSpeedMps = impliedSpeedMps;
        }
    }

    static boolean validCoord(Double lat, Double lng) {
        if (lat == null || lng == null || lat.isNaN() || lat.isInfinite() || lng.isNaN() || lng.isInfinite()) {
            return false;
        }
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            return false;
        }
        return Math.abs(lat) > 1e-6 || Math.abs(lng) > 1e-6;
    }

    static String encodeGeohash7(double lat, double lng) {
        double minLat = -90.0, maxLat = 90.0;
        double minLng = -180.0, maxLng = 180.0;
        boolean even = true;
        int bit = 0, ch = 0;
        StringBuilder sb = new StringBuilder(7);
        while (sb.length() < 7) {
            if (even) {
                double mid = (minLng + maxLng) / 2.0;
                if (lng >= mid) { ch = (ch << 1) | 1; minLng = mid; }
                else { ch = ch << 1; maxLng = mid; }
            } else {
                double mid = (minLat + maxLat) / 2.0;
                if (lat >= mid) { ch = (ch << 1) | 1; minLat = mid; }
                else { ch = ch << 1; maxLat = mid; }
            }
            even = !even;
            if (++bit == 5) {
                sb.append(GEOHASH_BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return sb.toString();
    }

    /** Pure location-plausibility anti-cheat check against previous timestamped position. */
    public static PlausibilityVerdict evaluatePlausibility(Double lat, Double lng, Double accuracyM,
                                                           Double prevLat, Double prevLng,
                                                           Long prevMillis, long nowMillis) {
        if (!validCoord(lat, lng)) {
            return new PlausibilityVerdict("BLOCKED", "INVALID_COORDINATES", null, null);
        }
        if (accuracyM != null && (accuracyM.isNaN() || accuracyM.isInfinite() || accuracyM < 0 || accuracyM > MAX_ACCURACY_M)) {
            return new PlausibilityVerdict("BLOCKED", "POOR_ACCURACY", null, null);
        }
        if (prevLat != null && prevLng != null && prevMillis != null && validCoord(prevLat, prevLng)) {
            double distM = GeoDistanceUtils.haversineMeters(prevLat, prevLng, lat, lng);
            double dtSec = Math.max(0.5, (nowMillis - prevMillis) / 1000.0);
            double speedMps = distM / dtSec;
            if (distM > MIN_TELEPORT_DIST_M && speedMps > TELEPORT_BLOCK_SPEED_MPS) {
                return new PlausibilityVerdict("BLOCKED", "TELEPORT_SPEED", distM, speedMps);
            }
            if (distM > MIN_TELEPORT_DIST_M && speedMps > HIGH_SPEED_FLAG_MPS) {
                return new PlausibilityVerdict("FLAGGED", "HIGH_SPEED", distM, speedMps);
            }
            return new PlausibilityVerdict("PASS", null, distM, speedMps);
        }
        return new PlausibilityVerdict("PASS", null, null, null);
    }

    private PlausibilityVerdict checkAndLogPlausibility(long memberId, String territoryId, String action,
                                                        Double lat, Double lng, Double accuracyM, long nowMillis) {
        List<Map<String, Object>> prevRows = jdbc.queryForList(
                "SELECT latitude, longitude, create_time FROM roam_territory_checkin WHERE member_id = ? ORDER BY create_time DESC LIMIT 1",
                memberId);
        Double prevLat = null, prevLng = null;
        Long prevMillis = null;
        if (!prevRows.isEmpty()) {
            Map<String, Object> p = prevRows.get(0);
            prevLat = dbl(p.get("latitude"));
            prevLng = dbl(p.get("longitude"));
            prevMillis = millis(p.get("create_time"));
        }
        PlausibilityVerdict v = evaluatePlausibility(lat, lng, accuracyM, prevLat, prevLng, prevMillis, nowMillis);
        logAudit(territoryId, memberId, action, v.status, v.reasonCode, lat, lng, accuracyM, v.distanceM, v.impliedSpeedMps,
                Map.of("prevLat", prevLat == null ? 0.0 : prevLat, "prevLng", prevLng == null ? 0.0 : prevLng));
        if ("BLOCKED".equals(v.status)) {
            throw new CityGameException(409, v.reasonCode, "定位异常或超出合理移动范围: " + v.reasonCode);
        }
        return v;
    }

    private void logAudit(String territoryId, long memberId, String action, String verdict, String reasonCode,
                          Double lat, Double lng, Double accuracyM, Double distanceM, Double impliedSpeedMps,
                          Map<String, Object> detail) {
        String sql = "INSERT INTO roam_territory_audit(territory_id, member_id, action, verdict, reason_code, latitude, longitude, accuracy_m, distance_m, implied_speed_mps, detail_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        String detailJson = detail == null ? null : JSON.toJSONString(detail);
        javax.sql.DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            jdbc.update(sql, territoryId, memberId, action, verdict, reasonCode, lat, lng, accuracyM, distanceM, impliedSpeedMps, detailJson);
            return;
        }
        // Write audit independently so blocked anti-cheat attempts persist even when @Transactional rolls back.
        try (java.sql.Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            if (!c.getAutoCommit()) c.setAutoCommit(true);
            ps.setObject(1, territoryId);
            ps.setLong(2, memberId);
            ps.setString(3, action);
            ps.setString(4, verdict);
            ps.setObject(5, reasonCode);
            ps.setObject(6, lat);
            ps.setObject(7, lng);
            ps.setObject(8, accuracyM);
            ps.setObject(9, distanceM);
            ps.setObject(10, impliedSpeedMps);
            ps.setObject(11, detailJson);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            jdbc.update(sql, territoryId, memberId, action, verdict, reasonCode, lat, lng, accuracyM, distanceM, impliedSpeedMps, detailJson);
        }
    }

    /** Unified Roam + CITY map nearby query: merges roam_poi, city_game_property and roam_territory. */
    public Map<String, Object> nearby(Long memberId, Double lat, Double lng, Integer radiusM,
                                      String boardId, String regionId, String seasonId) {
        CityGameSupport.requireMember(memberId);
        if (!validCoord(lat, lng)) throw new CityGameException(400, "INVALID_COORDINATES", "无效的坐标");
        int radius = radiusM == null || radiusM <= 0 ? 3000 : Math.min(radiusM, 50_000);
        Map<String, Object> board = resolveBoard(boardId, regionId);
        String resolvedBoardId = board == null ? null : (String) board.get("boardId");
        String resolvedSeasonId = seasonId != null && !seasonId.isEmpty() ? seasonId
                : (board == null ? null : (String) board.get("seasonId"));

        double dLat = radius / 111320.0;
        double dLng = radius / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        long now = clock.getAsLong();

        List<Map<String, Object>> items = new ArrayList<>();
        List<Long> seenPoiIds = new ArrayList<>();
        if (resolvedBoardId != null) {
            List<Map<String, Object>> boardSpaces = jdbc.queryForList(
                    "SELECT pt.board_id AS boardId, b.region_id AS regionId, b.season_id AS seasonId, pt.point_id AS pointId, pt.place_id AS poiId, "
                            + "pt.title AS pointTitle, pt.latitude, pt.longitude, pt.availability, pr.property_id AS propertyId, pr.track_index AS trackIndex, "
                            + "pr.role, pr.status AS propStatus, pr.holder_team_id AS holderTeamId, pr.protected_until AS propProtectedUntil, "
                            + "poi.radius_m AS poiRadiusM, poi.cover_img AS coverImg, "
                            + "t.territory_id AS territoryId, t.owner_member_id AS ownerMemberId, t.claim_photo_url AS claimPhotoUrl, "
                            + "t.checkin_toll_points AS checkinTollPoints, t.owner_revenue_points AS ownerRevenuePoints, "
                            + "t.checkin_count AS checkinCount, t.revenue_total_points AS revenueTotalPoints, t.status AS territoryStatus, "
                            + "t.protected_until AS territoryProtectedUntil, m.nickname AS ownerNickname, "
                            + "t.rules_kind AS rulesKind, COALESCE(t.tier, pt.tier) AS tier, t.upgrade_level AS upgradeLevel, "
                            + "t.decay_state AS decayState, COALESCE(t.district_id, pt.district_id) AS districtId "
                            + "FROM city_game_point pt "
                            + "JOIN city_game_board b ON b.board_id = pt.board_id "
                            + "LEFT JOIN city_game_property pr ON pr.board_id = pt.board_id AND pr.point_id = pt.point_id "
                            + "LEFT JOIN roam_poi poi ON poi.id = pt.place_id "
                            + "LEFT JOIN roam_territory t ON ((t.board_id = pt.board_id AND t.property_id = pr.property_id) OR (pt.place_id IS NOT NULL AND t.poi_id = pt.place_id)) AND t.status = 'ACTIVE' "
                            + "LEFT JOIN ums_member m ON m.id = t.owner_member_id "
                            + "WHERE pt.board_id = ? AND pt.del_flag = 0 AND pt.latitude BETWEEN ? AND ? AND pt.longitude BETWEEN ? AND ? "
                            + "ORDER BY pr.track_index ASC, pt.point_id ASC",
                    resolvedBoardId, lat - dLat, lat + dLat, lng - dLng, lng + dLng);
            for (Map<String, Object> row : boardSpaces) {
                if (row.get("poiId") instanceof Number) seenPoiIds.add(((Number) row.get("poiId")).longValue());
                items.add(wireSpaceItem(row, lat, lng, now));
            }
        }

        List<Map<String, Object>> roamPois = jdbc.queryForList(
                "SELECT poi.id AS poiId, poi.name AS pointTitle, poi.lat AS latitude, poi.lng AS longitude, poi.radius_m AS poiRadiusM, "
                        + "poi.cover_img AS coverImg, poi.city_code AS cityCode, "
                        + "t.territory_id AS territoryId, t.board_id AS boardId, t.season_id AS seasonId, t.property_id AS propertyId, "
                        + "t.point_id AS pointId, t.owner_member_id AS ownerMemberId, t.claim_photo_url AS claimPhotoUrl, "
                        + "t.checkin_toll_points AS checkinTollPoints, t.owner_revenue_points AS ownerRevenuePoints, "
                        + "t.checkin_count AS checkinCount, t.revenue_total_points AS revenueTotalPoints, t.status AS territoryStatus, "
                        + "t.protected_until AS territoryProtectedUntil, m.nickname AS ownerNickname, "
                        + "t.rules_kind AS rulesKind, t.tier AS tier, t.upgrade_level AS upgradeLevel, "
                        + "t.decay_state AS decayState, t.district_id AS districtId "
                        + "FROM roam_poi poi "
                        + "LEFT JOIN roam_territory t ON t.poi_id = poi.id AND t.status = 'ACTIVE' "
                        + "LEFT JOIN ums_member m ON m.id = t.owner_member_id "
                        + "WHERE poi.status = 1 AND poi.lat BETWEEN ? AND ? AND poi.lng BETWEEN ? AND ? "
                        + "LIMIT 120",
                lat - dLat, lat + dLat, lng - dLng, lng + dLng);
        for (Map<String, Object> row : roamPois) {
            Long pid = row.get("poiId") instanceof Number ? ((Number) row.get("poiId")).longValue() : null;
            if (pid != null && seenPoiIds.contains(pid)) continue;
            items.add(wireSpaceItem(row, lat, lng, now));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boardId", resolvedBoardId);
        out.put("regionId", board == null ? regionId : board.get("regionId"));
        out.put("seasonId", resolvedSeasonId);
        out.put("revenueMode", revenueModeFor(resolvedBoardId));
        out.put("items", items);
        out.put("territories", items);
        return out;
    }

    private Map<String, Object> wireSpaceItem(Map<String, Object> r, double curLat, double curLng, long now) {
        double pLat = dbl(r.get("latitude"));
        double pLng = dbl(r.get("longitude"));
        double distM = GeoDistanceUtils.haversineMeters(curLat, curLng, pLat, pLng);
        Long protMillis = millis(r.get("territoryProtectedUntil") != null ? r.get("territoryProtectedUntil") : r.get("propProtectedUntil"));
        boolean isProtected = protMillis != null && protMillis > now;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("territoryId", r.get("territoryId"));
        item.put("poiId", r.get("poiId"));
        item.put("boardId", r.get("boardId"));
        item.put("seasonId", r.get("seasonId"));
        item.put("propertyId", r.get("propertyId"));
        item.put("pointId", r.get("pointId"));
        item.put("trackIndex", r.get("trackIndex"));
        item.put("title", r.get("pointTitle"));
        item.put("latitude", pLat);
        item.put("longitude", pLng);
        item.put("radiusM", r.get("poiRadiusM") instanceof Number ? ((Number) r.get("poiRadiusM")).intValue() : DEFAULT_RADIUS_M);
        item.put("ownerMemberId", r.get("ownerMemberId"));
        item.put("ownerNickname", r.get("ownerNickname"));
        item.put("holderTeamId", r.get("holderTeamId"));
        item.put("claimed", r.get("territoryId") != null || r.get("ownerMemberId") != null);
        item.put("status", r.get("territoryId") != null ? (isProtected ? "PROTECTED" : "CLAIMED") : (r.get("propStatus") != null ? r.get("propStatus") : "UNCLAIMED"));
        item.put("protectedUntilMillis", protMillis);
        item.put("isProtected", isProtected);
        item.put("claimPhotoUrl", r.get("claimPhotoUrl"));
        item.put("checkinTollPoints", r.get("checkinTollPoints") instanceof Number ? ((Number) r.get("checkinTollPoints")).longValue() : DEFAULT_TOLL_POINTS);
        item.put("ownerRevenuePoints", r.get("ownerRevenuePoints") instanceof Number ? ((Number) r.get("ownerRevenuePoints")).longValue() : DEFAULT_REVENUE_POINTS);
        item.put("checkinCount", r.get("checkinCount") instanceof Number ? ((Number) r.get("checkinCount")).longValue() : 0L);
        item.put("revenueTotalPoints", r.get("revenueTotalPoints") instanceof Number ? ((Number) r.get("revenueTotalPoints")).longValue() : 0L);
        item.put("distanceM", Math.round(distM * 10.0) / 10.0);
        // CITY-1: which claim flow the app must run for this place, plus the City Monopoly facts it shows.
        item.put("claimFlow", monopolyActive((String) r.get("boardId")) ? "MONOPOLY" : "LEGACY");
        item.put("rulesKind", r.get("rulesKind"));
        item.put("tier", r.get("tier") instanceof Number ? ((Number) r.get("tier")).intValue() : null);
        item.put("upgradeLevel", r.get("upgradeLevel") instanceof Number ? ((Number) r.get("upgradeLevel")).intValue() : null);
        item.put("decayState", r.get("decayState"));
        item.put("districtId", r.get("districtId"));
        return item;
    }

    /**
     * Claim a territory / merged CITY board space with geotagged photo + GPS check-in.
     * Replaces staff-code-only arrival for territory claims when photo+GPS proof passes.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> claim(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        if (body == null) throw new CityGameException(400, "TERRITORY_INVALID", "请求体不能为空");
        if (monopolyActive(str(body, "boardId"))) {
            // Legacy claim let the client set its own toll/revenue; City Monopoly claims are quoted and evidence-checked.
            throw new CityGameException(409, "CLAIM_USE_MONOPOLY", "请使用城市大富翁占领流程",
                    Map.of("quotePath", "/api/city-game/territory/claim/quote", "commandPath", "/api/city-game/territory/claim/command"));
        }
        String requestId = str(body, "requestId");
        String photoUrl = str(body, "photoUrl");
        Double lat = dbl(body.get("lat"));
        Double lng = dbl(body.get("lng"));
        Double accuracyM = dbl(body.get("accuracyM"));
        if (!CityGameRules.id(requestId) || photoUrl == null || photoUrl.trim().isEmpty() || photoUrl.length() > 500) {
            throw new CityGameException(400, "TERRITORY_INVALID", "缺少有效的 requestId 或打卡照片凭证");
        }
        String cleanPhotoUrl = photoUrl.trim();
        if (submissionSafety != null) {
            submissionSafety.requireOwnImages(memberId, cleanPhotoUrl);
        }

        // Idempotent replay by (owner_member_id, claim_request_id)
        List<Map<String, Object>> existingReq = jdbc.queryForList(
                "SELECT * FROM roam_territory WHERE owner_member_id = ? AND claim_request_id = ? LIMIT 1",
                memberId, requestId);
        if (!existingReq.isEmpty()) {
            Map<String, Object> out = formatTerritoryRow(existingReq.get(0));
            out.put("idempotentReplay", true);
            return out;
        }

        long now = clock.getAsLong();
        PlausibilityVerdict plausibility = checkAndLogPlausibility(memberId, null, "CLAIM", lat, lng, accuracyM, now);

        Long poiId = num(body, "poiId");
        String boardId = str(body, "boardId");
        String propertyId = str(body, "propertyId");
        String pointId = str(body, "pointId");
        String title = str(body, "title");
        String cityCode = null;
        Double targetLat = null, targetLng = null;
        int radiusM = DEFAULT_RADIUS_M;
        String seasonId = str(body, "seasonId");
        int protectionSeconds = DEFAULT_PROTECTION_SECONDS;
        long tollPoints = num(body, "checkinTollPoints") != null ? Math.max(0L, Math.min(500L, num(body, "checkinTollPoints"))) : DEFAULT_TOLL_POINTS;
        long revenuePoints = num(body, "ownerRevenuePoints") != null ? Math.max(1L, Math.min(500L, num(body, "ownerRevenuePoints"))) : DEFAULT_REVENUE_POINTS;

        // 1) Resolve place from roam_poi if poiId provided
        if (poiId != null && poiId > 0) {
            List<Map<String, Object>> pois = jdbc.queryForList(
                    "SELECT id, name, lat, lng, radius_m, city_code FROM roam_poi WHERE id = ? AND status = 1", poiId);
            if (pois.isEmpty()) throw new CityGameException(404, "POI_NOT_FOUND", "地点不存在或已下线");
            Map<String, Object> poi = pois.get(0);
            targetLat = dbl(poi.get("lat"));
            targetLng = dbl(poi.get("lng"));
            if (poi.get("radius_m") instanceof Number) radiusM = Math.max(30, ((Number) poi.get("radius_m")).intValue());
            if (title == null || title.trim().isEmpty()) title = str(poi, "name");
            cityCode = str(poi, "city_code");
        }

        // 2) Resolve place<->property link on CITY board
        Map<String, Object> linkedSpace = resolveLinkedBoardSpace(boardId, propertyId, pointId, poiId);
        if (linkedSpace != null) {
            boardId = (String) linkedSpace.get("boardId");
            propertyId = (String) linkedSpace.get("propertyId");
            pointId = (String) linkedSpace.get("pointId");
            seasonId = (String) linkedSpace.get("seasonId");
            if (linkedSpace.get("protectionSeconds") instanceof Number) {
                protectionSeconds = Math.max(60, ((Number) linkedSpace.get("protectionSeconds")).intValue());
            }
            if (targetLat == null && linkedSpace.get("latitude") != null) {
                targetLat = dbl(linkedSpace.get("latitude"));
                targetLng = dbl(linkedSpace.get("longitude"));
            }
            if (title == null || title.trim().isEmpty()) title = (String) linkedSpace.get("pointTitle");
            if (poiId == null && linkedSpace.get("placeId") instanceof Number) {
                poiId = ((Number) linkedSpace.get("placeId")).longValue();
            }
        }

        if (targetLat == null || targetLng == null) {
            // MED-S8:没有 POI / 棋盘格目标时不再把玩家自己的定位(可能是住址)存成领地坐标。
            throw new CityGameException(400, "TERRITORY_TARGET_REQUIRED", "请选择要占领的地点");
        }
        if (title == null || title.trim().isEmpty()) title = "漫游领地";
        if (title.length() > 160) title = title.substring(0, 160);

        double distM = GeoDistanceUtils.haversineMeters(lat, lng, targetLat, targetLng);
        if (distM > radiusM) {
            logAudit(null, memberId, "CLAIM", "BLOCKED", "OUT_OF_GEOFENCE", lat, lng, accuracyM, distM, plausibility.impliedSpeedMps,
                    Map.of("radiusM", radiusM, "poiId", poiId == null ? 0 : poiId));
            throw new CityGameException(409, "OUT_OF_GEOFENCE", "超出地点围栏范围 (" + Math.round(distM) + "m > " + radiusM + "m)");
        }

        // 3) Enforce protection window before claiming / taking over
        Map<String, Object> existingActive = findActiveTerritoryForUpdate(poiId, boardId, propertyId);
        if (existingActive != null) {
            long currentOwner = ((Number) existingActive.get("owner_member_id")).longValue();
            Long existingProt = millis(existingActive.get("protected_until"));
            if (currentOwner != memberId && existingProt != null && existingProt > now) {
                throw new CityGameException(409, "TERRITORY_PROTECTED", "该领地仍在保护期内",
                        Map.of("protectedUntilMillis", existingProt));
            }
            // Mark previous ownership row superseded so new owner claims cleanly
            jdbc.update("UPDATE roam_territory SET status = 'REVOKED', version = version + 1 WHERE territory_id = ?",
                    existingActive.get("territory_id"));
        }

        if (boardId != null && propertyId != null) {
            Map<String, Object> prop = mapper.selectPropertyForUpdate(boardId, propertyId);
            if (prop != null) {
                Long propProt = millis(prop.get("protectedUntil"));
                if ("PROTECTED".equals(prop.get("status")) && propProt != null && propProt > now) {
                    throw new CityGameException(409, "PROPERTY_PROTECTED", "该棋盘据点处于保护窗口内",
                            Map.of("protectedUntilMillis", propProt));
                }
            }
        }

        String territoryId = UUID.randomUUID().toString();
        Timestamp nowTs = new Timestamp(now);
        Timestamp protectedUntilTs = new Timestamp(now + protectionSeconds * 1000L);
        String geohash7 = encodeGeohash7(targetLat, targetLng);

        jdbc.update("INSERT INTO roam_territory(territory_id, geohash7, poi_id, board_id, season_id, property_id, point_id, "
                        + "title, city_code, latitude, longitude, radius_m, owner_member_id, claim_photo_url, claim_request_id, "
                        + "checkin_toll_points, owner_revenue_points, revenue_mode, checkin_count, revenue_total_points, status, version, claimed_at, protected_until) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CITY_COIN',0,0,'ACTIVE',1,?,?)",
                territoryId, geohash7, poiId, boardId, seasonId, propertyId, pointId,
                title, cityCode, targetLat, targetLng, radiusM, memberId, cleanPhotoUrl, requestId,
                tollPoints, revenuePoints, nowTs, protectedUntilTs);

        String checkinId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roam_territory_checkin(checkin_id, territory_id, member_id, owner_member_id, request_id, checkin_kind, "
                        + "latitude, longitude, accuracy_m, distance_m, photo_url, points_spent, owner_revenue_points, revenue_mode, plausibility_status, plausibility_reason, implied_speed_mps, create_time) "
                        + "VALUES (?,?,?,?,?,'CLAIM',?,?,?,?,?,0,0,'CITY_COIN',?,?,?,?)",
                checkinId, territoryId, memberId, memberId, requestId,
                lat, lng, accuracyM, distM, cleanPhotoUrl, plausibility.status, plausibility.reasonCode, plausibility.impliedSpeedMps, nowTs);

        // 4) Reflect claim directly on linked CITY board space (photo+GPS replaces staff-code-only for claims)
        if (boardId != null && propertyId != null) {
            syncBoardPropertyOnClaim(boardId, propertyId, pointId, memberId, territoryId, cleanPhotoUrl, protectedUntilTs);
        }
        if (cityNotify != null && existingActive != null) {
            long formerOwner = ((Number) existingActive.get("owner_member_id")).longValue();
            if (formerOwner != memberId) cityNotify.lost(formerOwner, "terr:" + existingActive.get("territory_id"), title);
        }
        if (inGameChannelService != null) {
            boolean isTakeover = existingActive != null && ((Number) existingActive.get("owner_member_id")).longValue() != memberId;
            String evtType = isTakeover
                    ? com.chengyinhub.business.service.impl.InGameChannelService.EVENT_TAKEOVER
                    : com.chengyinhub.business.service.impl.InGameChannelService.EVENT_CLAIM;
            inGameChannelService.postRoamEvent(evtType, memberId, cityCode, title, null);
            if (boardId != null) {
                Map<String, Object> participation = mapper.selectParticipation(boardId, memberId);
                String teamId = participation == null ? null : (String) participation.get("teamId");
                if (teamId != null) {
                    inGameChannelService.postCityGameEvent(evtType, memberId, boardId, teamId, title, null);
                }
            }
        }

        Map<String, Object> created = jdbc.queryForMap("SELECT * FROM roam_territory WHERE territory_id = ?", territoryId);
        Map<String, Object> out = formatTerritoryRow(created);
        out.put("idempotentReplay", false);
        out.put("plausibilityStatus", plausibility.status);
        out.put("proofMethod", "PHOTO_GPS");
        out.put("protectionSeconds", protectionSeconds);
        return out;
    }

    void syncBoardPropertyOnClaim(String boardId, String propertyId, String pointId, long memberId,
                                          String territoryId, String photoUrl, Timestamp protectedUntilTs) {
        projectBoardHolder(boardId, propertyId, pointId, memberId, territoryId, photoUrl, protectedUntilTs, null);
    }

    /**
     * CITY-10: writes the holder's team onto the board property. A team holding that is not the projection of the
     * former City Monopoly holder ({@code formerHolder}) is left alone and logged as BOARD_HELD_BY_TEAM.
     */
    void projectBoardHolder(String boardId, String propertyId, String pointId, long memberId,
                            String territoryId, String photoUrl, Timestamp protectedUntilTs, Long formerHolder) {
        Map<String, Object> board = mapper.selectBoardForUpdate(boardId);
        if (board == null || !"OPEN".equals(board.get("lifecycle"))) return;
        Map<String, Object> prop = mapper.selectPropertyForUpdate(boardId, propertyId);
        if (prop == null) return;
        Map<String, Object> participation = mapper.selectParticipation(boardId, memberId);
        String teamId = participation == null ? null : (String) participation.get("teamId");
        // CITY-10: the board row is a projection of who holds the place. Never overwrite another team's holding.
        Object currentTeam = prop.get("holderTeamId");
        Map<String, Object> former = formerHolder == null ? null : mapper.selectParticipation(boardId, formerHolder);
        boolean projectionOfFormer = former != null && currentTeam != null && currentTeam.equals(former.get("teamId"));
        if (currentTeam != null && !currentTeam.equals(teamId) && !projectionOfFormer) {
            org.slf4j.LoggerFactory.getLogger(RoamTerritoryService.class).warn(
                    "event=BOARD_HELD_BY_TEAM board={} property={} team={} territory={}", boardId, propertyId, currentTeam, territoryId);
            return;
        }
        long expectedVer = ((Number) prop.get("stateVersion")).longValue();
        mapper.updateProperty(boardId, propertyId, expectedVer, "PROTECTED", teamId,
                new Date(protectedUntilTs.getTime() - 1000L), new Date(protectedUntilTs.getTime()), null);
        if (pointId != null) {
            mapper.bumpPointSlot(boardId, pointId);
            mapper.insertPointEvent(boardId, pointId, "SLOT", teamId);
        }
        mapper.advanceBoard(boardId);
        Map<String, Object> fresh = mapper.selectBoard(boardId);
        mapper.insertEvent(boardId, ((Number) fresh.get("eventSequence")).longValue(), "TERRITORY_CLAIMED_PHOTO",
                propertyId, teamId, ((Number) fresh.get("boardVersion")).longValue());
        mapper.insertBoardAudit(boardId, "BOARD_TERRITORY_CLAIM", memberId, "PLAYER", null,
                JSON.toJSONString(Map.of("territoryId", territoryId, "propertyId", propertyId, "pointId",
                        pointId == null ? "" : pointId, "photoUrl", photoUrl)));
    }

    /** Visitor check-in on a territory: charges the optional in-game point toll; the owner's yield is paid in city coin. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkin(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        if (body == null) throw new CityGameException(400, "CHECKIN_INVALID", "打卡请求无效");
        String requestId = str(body, "requestId");
        Double lat = dbl(body.get("lat"));
        Double lng = dbl(body.get("lng"));
        Double accuracyM = dbl(body.get("accuracyM"));
        String photoUrl = str(body, "photoUrl");
        if (!CityGameRules.id(requestId)) throw new CityGameException(400, "CHECKIN_INVALID", "requestId 无效");
        if (photoUrl != null && !photoUrl.trim().isEmpty() && submissionSafety != null) {
            submissionSafety.requireOwnImages(memberId, photoUrl.trim());
        }

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM roam_territory_checkin WHERE member_id = ? AND request_id = ? LIMIT 1", memberId, requestId);
        if (!existing.isEmpty()) {
            // CITY-4: a replay answers in the same camelCase shape as the first answer.
            return checkinReplay(existing.get(0), memberId);
        }

        long now = clock.getAsLong();
        PlausibilityVerdict plausibility = checkAndLogPlausibility(memberId, str(body, "territoryId"), "CHECKIN", lat, lng, accuracyM, now);

        Map<String, Object> territory = lockTargetTerritory(str(body, "territoryId"), num(body, "poiId"),
                str(body, "boardId"), str(body, "propertyId"));
        if (territory == null) throw new CityGameException(404, "TERRITORY_NOT_FOUND", "领地不存在");
        if (!"ACTIVE".equals(territory.get("status"))) {
            throw new CityGameException(409, "TERRITORY_INACTIVE", "领地当前不可打卡");
        }
        String territoryId = (String) territory.get("territory_id");
        double tLat = dbl(territory.get("latitude"));
        double tLng = dbl(territory.get("longitude"));
        int radiusM = ((Number) territory.get("radius_m")).intValue();
        double distM = GeoDistanceUtils.haversineMeters(lat, lng, tLat, tLng);
        if (distM > radiusM) {
            logAudit(territoryId, memberId, "CHECKIN", "BLOCKED", "OUT_OF_GEOFENCE", lat, lng, accuracyM, distM, plausibility.impliedSpeedMps,
                    Map.of("radiusM", radiusM));
            throw new CityGameException(409, "OUT_OF_GEOFENCE", "不在领地打卡范围内");
        }

        long ownerId = ((Number) territory.get("owner_member_id")).longValue();
        boolean selfVisit = ownerId == memberId;
        if (monopolyActive((String) territory.get("board_id"))) {
            Map<String, Object> rent = monopoly.onVisit(memberId, territory, lat, lng, accuracyM, "TERR_CHECKIN:" + requestId, str(body, "deviceKey"));
            long granted = rent.get("yield") instanceof Number ? ((Number) rent.get("yield")).longValue() : 0L;
            String checkinId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO roam_territory_checkin(checkin_id, territory_id, member_id, owner_member_id, request_id, checkin_kind, "
                            + "latitude, longitude, accuracy_m, distance_m, photo_url, points_spent, owner_revenue_points, revenue_mode, plausibility_status, plausibility_reason, implied_speed_mps, create_time) "
                            + "VALUES (?,?,?,?,?,'VISIT',?,?,?,?,?,0,?,'POINTS',?,?,?,?)",
                    checkinId, territoryId, memberId, ownerId, requestId, lat, lng, accuracyM, distM, photoUrl, granted,
                    plausibility.status, plausibility.reasonCode, plausibility.impliedSpeedMps, new Timestamp(now));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("idempotentReplay", false);
            out.put("checkinId", checkinId);
            out.put("territoryId", territoryId);
            out.put("ownerMemberId", ownerId);
            out.put("selfVisit", selfVisit);
            out.put("cooldownActive", false);
            out.put("visitorPointsSpent", 0L);
            out.put("ownerRevenuePoints", granted);
            out.put("revenueMode", "POINTS");
            out.put("rentState", rent.get("yieldEventId") != null ? "PENDING" : "NONE");
            out.put("ineligibleReason", rent.get("ineligibleReason"));
            out.put("visitorPoints", rent.get("visitorPoints") instanceof Number ? ((Number) rent.get("visitorPoints")).longValue() : 0L);
            out.put("territory", rent);
            out.put("plausibilityStatus", plausibility.status);
            out.put("distanceM", Math.round(distM * 10.0) / 10.0);
            return out;
        }
        boolean cooldownHit = !selfVisit && hasRecentRevenueCheckin(territoryId, memberId, now);
        long tollPoints = 0L;
        long ownerRevenue = 0L;
        if (!selfVisit && !cooldownHit) {
            tollPoints = Math.max(0L, ((Number) territory.get("checkin_toll_points")).longValue());
            ownerRevenue = Math.max(0L, ((Number) territory.get("owner_revenue_points")).longValue());
            if (tollPoints > 0) {
                long beforeVisitor = mapper.selectMemberPoints(memberId);
                if (beforeVisitor < tollPoints || mapper.deductMemberPoints(memberId, tollPoints) != 1) {
                    throw new CityGameException(409, "INSUFFICIENT_POINTS", "积分不足，无法支付过路费");
                }
                mapper.insertPointsDetail(memberId, beforeVisitor, tollPoints, "领地打卡消耗积分", "TERR_CHECKIN:" + requestId);
            }
            if (ownerRevenue > 0) {
                creditOwnerYield(ownerId, territoryId, "TERR_REV:" + requestId);
            }
            jdbc.update("UPDATE roam_territory SET checkin_count = checkin_count + 1, revenue_total_points = revenue_total_points + ?, last_checkin_at = ? WHERE territory_id = ?",
                    ownerRevenue, new Timestamp(now), territoryId);
        }

        String checkinId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roam_territory_checkin(checkin_id, territory_id, member_id, owner_member_id, request_id, checkin_kind, "
                        + "latitude, longitude, accuracy_m, distance_m, photo_url, points_spent, owner_revenue_points, revenue_mode, plausibility_status, plausibility_reason, implied_speed_mps, create_time) "
                        + "VALUES (?,?,?,?,?,'VISIT',?,?,?,?,?,?,?,'CITY_COIN',?,?,?,?)",
                checkinId, territoryId, memberId, ownerId, requestId,
                lat, lng, accuracyM, distM, photoUrl, tollPoints, ownerRevenue,
                plausibility.status, plausibility.reasonCode, plausibility.impliedSpeedMps, new Timestamp(now));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotentReplay", false);
        out.put("checkinId", checkinId);
        out.put("territoryId", territoryId);
        out.put("ownerMemberId", ownerId);
        out.put("selfVisit", selfVisit);
        out.put("cooldownActive", cooldownHit);
        out.put("visitorPointsSpent", tollPoints);
        out.put("ownerRevenuePoints", ownerRevenue);
        out.put("revenueMode", REVENUE_MODE);
        out.put("plausibilityStatus", plausibility.status);
        out.put("distanceM", Math.round(distM * 10.0) / 10.0);
        if (inGameChannelService != null) {
            inGameChannelService.postRoamEvent(
                    com.chengyinhub.business.service.impl.InGameChannelService.EVENT_CHECKIN,
                    memberId, str(territory, "city_code"), str(territory, "title"),
                    ownerRevenue > 0 ? "领主获得城市币收益" : null);
        }
        return out;
    }

    /**
     * Hook invoked when another player checks in via /api/roam/checkin or CITY arrival confirmation.
     * Pays the owner's yield in city coin (never points or money), idempotent per (visitorMemberId, sourceKey).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rewardExternalCheckin(long visitorMemberId, Long poiId, String boardId, String pointId,
                                                     Double lat, Double lng, String sourceKey, String checkinKind) {
        return rewardExternalCheckin(visitorMemberId, poiId, boardId, pointId, lat, lng, null, null, sourceKey, checkinKind);
    }

    /** Same hook with the client's horizontal accuracy and device key (alt-account rule); see {@link CityMonopolyService#onVisit}. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rewardExternalCheckin(long visitorMemberId, Long poiId, String boardId, String pointId,
                                                     Double lat, Double lng, Double accuracyM, String deviceKey,
                                                     String sourceKey, String checkinKind) {
        if (visitorMemberId <= 0 || sourceKey == null || sourceKey.isEmpty()) return Map.of("credited", false);
        List<Map<String, Object>> dup = jdbc.queryForList(
                "SELECT checkin_id AS checkinId, owner_revenue_points AS ownerRevenuePoints FROM roam_territory_checkin WHERE member_id = ? AND request_id = ? LIMIT 1",
                visitorMemberId, sourceKey);
        if (!dup.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>(dup.get(0));
            out.put("credited", true);
            out.put("idempotentReplay", true);
            return out;
        }
        Map<String, Object> territory = null;
        if (boardId != null && pointId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM roam_territory WHERE board_id = ? AND point_id = ? AND status = 'ACTIVE' ORDER BY claimed_at DESC LIMIT 1 FOR UPDATE",
                    boardId, pointId);
            if (!rows.isEmpty()) territory = rows.get(0);
        }
        if (territory == null && poiId != null && poiId > 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM roam_territory WHERE poi_id = ? AND status = 'ACTIVE' ORDER BY claimed_at DESC LIMIT 1 FOR UPDATE",
                    poiId);
            if (!rows.isEmpty()) territory = rows.get(0);
        }
        if (territory == null) return Map.of("credited", false, "reason", "NO_ACTIVE_TERRITORY");

        long ownerId = ((Number) territory.get("owner_member_id")).longValue();
        String territoryId = (String) territory.get("territory_id");
        long now = clock.getAsLong();
        if (monopolyActive((String) territory.get("board_id"))) {
            boolean staffVerified = "CITY_ARRIVAL".equals(checkinKind) && lat == null && lng == null;
            // A staff-confirmed CITY arrival is in-store evidence: it stands in for the GPS fix.
            double useLat = staffVerified ? dbl(territory.get("latitude")) : (lat == null ? Double.NaN : lat);
            double useLng = staffVerified ? dbl(territory.get("longitude")) : (lng == null ? Double.NaN : lng);
            Double acc = staffVerified ? Double.valueOf(0.0) : accuracyM;
            Map<String, Object> rent = monopoly.onVisit(visitorMemberId, territory, Double.isNaN(useLat) ? null : useLat,
                    Double.isNaN(useLng) ? null : useLng, acc, sourceKey, deviceKey);
            long granted = rent.get("yield") instanceof Number ? ((Number) rent.get("yield")).longValue() : 0L;
            boolean recorded = rent.get("yieldEventId") != null;
            if (recorded) {
                jdbc.update("INSERT INTO roam_territory_checkin(checkin_id, territory_id, member_id, owner_member_id, request_id, checkin_kind, "
                                + "latitude, longitude, accuracy_m, distance_m, photo_url, points_spent, owner_revenue_points, revenue_mode, plausibility_status, create_time) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,NULL,0,?,'POINTS','PASS',?)",
                        UUID.randomUUID().toString(), territoryId, visitorMemberId, ownerId, sourceKey, checkinKind,
                        Double.isNaN(useLat) ? dbl(territory.get("latitude")) : useLat, Double.isNaN(useLng) ? dbl(territory.get("longitude")) : useLng,
                        acc, 0.0, granted, new Timestamp(now));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("credited", recorded);
            out.put("territoryId", territoryId);
            out.put("ownerRevenuePoints", granted);
            out.put("revenueMode", "POINTS");
            out.put("territory", rent);
            return out;
        }
        if (ownerId == visitorMemberId || hasRecentRevenueCheckin(territoryId, visitorMemberId, now)) {
            return Map.of("credited", false, "territoryId", territoryId, "reason", ownerId == visitorMemberId ? "SELF_VISIT" : "COOLDOWN");
        }
        long ownerRevenue = Math.max(0L, ((Number) territory.get("owner_revenue_points")).longValue());
        if (ownerRevenue > 0) {
            creditOwnerYield(ownerId, territoryId, "EXT_REV:" + sourceKey);
        }
        jdbc.update("UPDATE roam_territory SET checkin_count = checkin_count + 1, revenue_total_points = revenue_total_points + ?, last_checkin_at = ? WHERE territory_id = ?",
                ownerRevenue, new Timestamp(now), territoryId);
        double useLat = lat != null ? lat : dbl(territory.get("latitude"));
        double useLng = lng != null ? lng : dbl(territory.get("longitude"));
        double distM = GeoDistanceUtils.haversineMeters(useLat, useLng, dbl(territory.get("latitude")), dbl(territory.get("longitude")));
        String checkinId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roam_territory_checkin(checkin_id, territory_id, member_id, owner_member_id, request_id, checkin_kind, "
                        + "latitude, longitude, accuracy_m, distance_m, photo_url, points_spent, owner_revenue_points, revenue_mode, plausibility_status, create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,NULL,0,?,'CITY_COIN','PASS',?)",
                checkinId, territoryId, visitorMemberId, ownerId, sourceKey, checkinKind,
                useLat, useLng, null, distM, ownerRevenue, new Timestamp(now));
        return Map.of("credited", true, "territoryId", territoryId, "ownerMemberId", ownerId,
                "ownerRevenuePoints", ownerRevenue, "revenueMode", REVENUE_MODE, "checkinId", checkinId);
    }

    /** Coordinate-based fallback hook when /api/roam/checkin lights a non-participating point near an active territory. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rewardExternalCheckinByCoords(long visitorMemberId, Double poiLat, Double poiLng,
                                                             Double curLat, Double curLng, String sourceKey) {
        return rewardExternalCheckinByCoords(visitorMemberId, poiLat, poiLng, curLat, curLng, null, null, sourceKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rewardExternalCheckinByCoords(long visitorMemberId, Double poiLat, Double poiLng,
                                                             Double curLat, Double curLng, Double accuracyM, String deviceKey,
                                                             String sourceKey) {
        if (visitorMemberId <= 0 || !validCoord(poiLat, poiLng)) return Map.of("credited", false);
        double dLat = 200.0 / 111320.0;
        double dLng = 200.0 / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(poiLat))));
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT territory_id, poi_id, board_id, point_id, latitude, longitude, radius_m "
                        + "FROM roam_territory WHERE status = 'ACTIVE' AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 10",
                poiLat - dLat, poiLat + dLat, poiLng - dLng, poiLng + dLng);
        String matchedId = null;
        double bestDist = Double.MAX_VALUE;
        for (Map<String, Object> c : candidates) {
            double d = GeoDistanceUtils.haversineMeters(poiLat, poiLng, dbl(c.get("latitude")), dbl(c.get("longitude")));
            int r = ((Number) c.get("radius_m")).intValue();
            if (d <= Math.max(r, DEFAULT_RADIUS_M) && d < bestDist) {
                bestDist = d;
                matchedId = (String) c.get("territory_id");
            }
        }
        if (matchedId == null) return Map.of("credited", false);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM roam_territory WHERE territory_id = ?", matchedId);
        return rewardExternalCheckin(visitorMemberId,
                row.get("poi_id") instanceof Number ? ((Number) row.get("poi_id")).longValue() : null,
                (String) row.get("board_id"), (String) row.get("point_id"), curLat, curLng, accuracyM, deviceKey, sourceKey, "ROAM_CHECKIN");
    }

    /** Season/board leaderboard ranking territory owners by total points revenue and holdings. */
    public Map<String, Object> leaderboard(String boardId, String regionId, String seasonId, Integer limit) {
        int capped = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        Map<String, Object> board = resolveBoard(boardId, regionId);
        String useBoardId = boardId != null && !boardId.isEmpty() ? boardId : (board == null ? null : (String) board.get("boardId"));
        String useSeasonId = seasonId != null && !seasonId.isEmpty() ? seasonId : (board == null ? null : (String) board.get("seasonId"));

        StringBuilder where = new StringBuilder(" WHERE t.status = 'ACTIVE'");
        List<Object> params = new ArrayList<>();
        if (useBoardId != null && !useBoardId.isEmpty()) {
            where.append(" AND t.board_id = ?");
            params.add(useBoardId);
        }
        if (useSeasonId != null && !useSeasonId.isEmpty()) {
            where.append(" AND t.season_id = ?");
            params.add(useSeasonId);
        }
        params.add(capped);
        List<Map<String, Object>> leaders = jdbc.queryForList(
                "SELECT t.owner_member_id AS ownerMemberId, COALESCE(MAX(m.nickname), CONCAT('Player#', t.owner_member_id)) AS ownerNickname, "
                        + "COUNT(*) AS territoryCount, COALESCE(SUM(t.checkin_count), 0) AS totalCheckins, "
                        + "COALESCE(SUM(t.revenue_total_points), 0) AS totalRevenuePoints "
                        + "FROM roam_territory t LEFT JOIN ums_member m ON m.id = t.owner_member_id "
                        + where + " GROUP BY t.owner_member_id ORDER BY totalRevenuePoints DESC, territoryCount DESC LIMIT ?",
                params.toArray());
        List<Map<String, Object>> topTerritories = jdbc.queryForList(
                "SELECT t.territory_id AS territoryId, t.title, t.board_id AS boardId, t.season_id AS seasonId, t.property_id AS propertyId, "
                        + "t.poi_id AS poiId, t.owner_member_id AS ownerMemberId, COALESCE(m.nickname, CONCAT('Player#', t.owner_member_id)) AS ownerNickname, "
                        + "t.checkin_count AS checkinCount, t.revenue_total_points AS revenueTotalPoints, t.protected_until AS protectedUntil "
                        + "FROM roam_territory t LEFT JOIN ums_member m ON m.id = t.owner_member_id "
                        + where + " ORDER BY t.revenue_total_points DESC, t.checkin_count DESC LIMIT ?",
                params.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boardId", useBoardId);
        out.put("seasonId", useSeasonId);
        out.put("revenueMode", revenueModeFor(useBoardId));
        out.put("leaders", leaders);
        out.put("topTerritories", topTerritories);
        return out;
    }

    public List<Map<String, Object>> myTerritories(Long memberId) {
        CityGameSupport.requireMember(memberId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM roam_territory WHERE owner_member_id = ? AND status != 'REVOKED' ORDER BY claimed_at DESC LIMIT 100",
                memberId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(formatTerritoryRow(r));
        return out;
    }

    // ---- Admin views & moderation ----

    public List<Map<String, Object>> adminList(String boardId, String seasonId, String status, Integer limit) {
        int capped = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        StringBuilder sql = new StringBuilder(
                "SELECT t.*, COALESCE(m.nickname, CONCAT('Player#', t.owner_member_id)) AS ownerNickname "
                        + "FROM roam_territory t LEFT JOIN ums_member m ON m.id = t.owner_member_id WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (boardId != null && !boardId.isEmpty()) { sql.append(" AND t.board_id = ?"); params.add(boardId); }
        if (seasonId != null && !seasonId.isEmpty()) { sql.append(" AND t.season_id = ?"); params.add(seasonId); }
        if (status != null && !status.isEmpty()) { sql.append(" AND t.status = ?"); params.add(status); }
        sql.append(" ORDER BY t.claimed_at DESC LIMIT ?");
        params.add(capped);
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminUpdateStatus(String territoryId, String status, Long tollPoints, Long revenuePoints,
                                                 Long adminUserId, String reason) {
        if (!CityGameRules.uuid(territoryId)) throw new CityGameException(400, "TERRITORY_INVALID", "territoryId 无效");
        if (status != null && !List.of("ACTIVE", "PAUSED", "REVOKED").contains(status)) {
            throw new CityGameException(400, "TERRITORY_INVALID", "status 无效");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM roam_territory WHERE territory_id = ? FOR UPDATE", territoryId);
        if (rows.isEmpty()) throw new CityGameException(404, "TERRITORY_NOT_FOUND", "领地不存在");
        Map<String, Object> current = rows.get(0);
        String nextStatus = status != null ? status : (String) current.get("status");
        long nextToll = tollPoints != null ? Math.max(0L, tollPoints) : ((Number) current.get("checkin_toll_points")).longValue();
        long nextRev = revenuePoints != null ? Math.max(0L, revenuePoints) : ((Number) current.get("owner_revenue_points")).longValue();
        jdbc.update("UPDATE roam_territory SET status = ?, checkin_toll_points = ?, owner_revenue_points = ?, version = version + 1 WHERE territory_id = ?",
                nextStatus, nextToll, nextRev, territoryId);
        logAudit(territoryId, ((Number) current.get("owner_member_id")).longValue(), "ADMIN_STATUS", "PASS", nextStatus,
                dbl(current.get("latitude")), dbl(current.get("longitude")), null, null, null,
                Map.of("adminUserId", adminUserId == null ? 0L : adminUserId, "reason", reason == null ? "" : reason));
        return formatTerritoryRow(jdbc.queryForMap("SELECT * FROM roam_territory WHERE territory_id = ?", territoryId));
    }

    public List<Map<String, Object>> adminAuditList(String verdict, Integer limit) {
        int capped = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        if (verdict != null && !verdict.isEmpty()) {
            return jdbc.queryForList("SELECT * FROM roam_territory_audit WHERE verdict = ? ORDER BY id DESC LIMIT ?", verdict, capped);
        }
        return jdbc.queryForList("SELECT * FROM roam_territory_audit ORDER BY id DESC LIMIT ?", capped);
    }

    // ---- helpers ----

    private Map<String, Object> resolveBoard(String boardId, String regionId) {
        if (boardId != null && !boardId.isEmpty()) return mapper.selectBoard(boardId);
        if (regionId != null && !regionId.isEmpty()) return mapper.selectCurrentBoard(regionId);
        List<Map<String, Object>> open = jdbc.queryForList(
                "SELECT board_id AS boardId, region_id AS regionId, season_id AS seasonId, protection_seconds AS protectionSeconds "
                        + "FROM city_game_board WHERE lifecycle = 'OPEN' ORDER BY release_id DESC LIMIT 1");
        return open.isEmpty() ? null : open.get(0);
    }

    private Map<String, Object> resolveLinkedBoardSpace(String boardId, String propertyId, String pointId, Long poiId) {
        if (boardId != null && propertyId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT b.board_id AS boardId, b.season_id AS seasonId, b.protection_seconds AS protectionSeconds, "
                            + "pr.property_id AS propertyId, pt.point_id AS pointId, pt.place_id AS placeId, pt.title AS pointTitle, "
                            + "pt.latitude, pt.longitude FROM city_game_board b "
                            + "JOIN city_game_property pr ON pr.board_id = b.board_id AND pr.property_id = ? "
                            + "JOIN city_game_point pt ON pt.board_id = pr.board_id AND pt.point_id = pr.point_id "
                            + "WHERE b.board_id = ? LIMIT 1",
                    propertyId, boardId);
            if (!rows.isEmpty()) return rows.get(0);
        }
        if (poiId != null && poiId > 0) {
            StringBuilder sql = new StringBuilder(
                    "SELECT b.board_id AS boardId, b.season_id AS seasonId, b.protection_seconds AS protectionSeconds, "
                            + "pr.property_id AS propertyId, pt.point_id AS pointId, pt.place_id AS placeId, pt.title AS pointTitle, "
                            + "pt.latitude, pt.longitude FROM city_game_point pt "
                            + "JOIN city_game_board b ON b.board_id = pt.board_id AND b.lifecycle = 'OPEN' "
                            + "LEFT JOIN city_game_property pr ON pr.board_id = pt.board_id AND pr.point_id = pt.point_id "
                            + "WHERE pt.place_id = ? AND pt.del_flag = 0 ");
            List<Object> params = new ArrayList<>();
            params.add(poiId);
            if (boardId != null && !boardId.isEmpty()) { sql.append("AND pt.board_id = ? "); params.add(boardId); }
            sql.append("ORDER BY b.release_id DESC LIMIT 1");
            List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
            if (!rows.isEmpty()) return rows.get(0);
        }
        if (boardId != null && pointId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT b.board_id AS boardId, b.season_id AS seasonId, b.protection_seconds AS protectionSeconds, "
                            + "pr.property_id AS propertyId, pt.point_id AS pointId, pt.place_id AS placeId, pt.title AS pointTitle, "
                            + "pt.latitude, pt.longitude FROM city_game_point pt "
                            + "JOIN city_game_board b ON b.board_id = pt.board_id "
                            + "LEFT JOIN city_game_property pr ON pr.board_id = pt.board_id AND pr.point_id = pt.point_id "
                            + "WHERE pt.board_id = ? AND pt.point_id = ? AND pt.del_flag = 0 LIMIT 1",
                    boardId, pointId);
            if (!rows.isEmpty()) return rows.get(0);
        }
        return null;
    }

    private Map<String, Object> findActiveTerritoryForUpdate(Long poiId, String boardId, String propertyId) {
        if (boardId != null && propertyId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM roam_territory WHERE board_id = ? AND property_id = ? AND status = 'ACTIVE' LIMIT 1 FOR UPDATE",
                    boardId, propertyId);
            if (!rows.isEmpty()) return rows.get(0);
        }
        if (poiId != null && poiId > 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM roam_territory WHERE poi_id = ? AND status = 'ACTIVE' LIMIT 1 FOR UPDATE", poiId);
            if (!rows.isEmpty()) return rows.get(0);
        }
        return null;
    }

    private Map<String, Object> lockTargetTerritory(String territoryId, Long poiId, String boardId, String propertyId) {
        if (CityGameRules.uuid(territoryId)) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM roam_territory WHERE territory_id = ? FOR UPDATE", territoryId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        return findActiveTerritoryForUpdate(poiId, boardId, propertyId);
    }

    /** CITY-4: rebuilds the check-in answer from the stored row (same keys as the first answer). */
    static Map<String, Object> checkinReplay(Map<String, Object> row, long memberId) {
        Map<String, Object> out = new LinkedHashMap<>();
        long owner = row.get("owner_member_id") instanceof Number ? ((Number) row.get("owner_member_id")).longValue() : 0L;
        boolean points = "POINTS".equals(row.get("revenue_mode"));
        long granted = row.get("owner_revenue_points") instanceof Number ? ((Number) row.get("owner_revenue_points")).longValue() : 0L;
        out.put("idempotentReplay", true);
        out.put("checkinId", row.get("checkin_id"));
        out.put("territoryId", row.get("territory_id"));
        out.put("ownerMemberId", owner);
        out.put("selfVisit", owner == memberId);
        out.put("cooldownActive", false);
        out.put("visitorPointsSpent", row.get("points_spent") instanceof Number ? ((Number) row.get("points_spent")).longValue() : 0L);
        out.put("ownerRevenuePoints", granted);
        out.put("revenueMode", row.get("revenue_mode"));
        if (points) out.put("rentState", granted > 0 ? "PENDING" : "NONE");
        out.put("plausibilityStatus", row.get("plausibility_status"));
        Object d = row.get("distance_m");
        out.put("distanceM", d instanceof Number ? Math.round(((Number) d).doubleValue() * 10.0) / 10.0 : null);
        return out;
    }

    private boolean hasRecentRevenueCheckin(String territoryId, long visitorMemberId, long nowMillis) {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM roam_territory_checkin WHERE territory_id = ? AND member_id = ? "
                        + "AND checkin_kind IN ('VISIT','ROAM_CHECKIN','CITY_ARRIVAL') AND owner_revenue_points > 0 AND create_time >= ?",
                Long.class, territoryId, visitorMemberId, new Timestamp(nowMillis - VISITOR_REVENUE_COOLDOWN_MS));
        return cnt != null && cnt > 0;
    }

    /**
     * Holder yield goes through the points-governance city coin service only, after the caller's transaction
     * commits (a rolled-back check-in pays nothing). Amount and daily cap come from the city coin config.
     */
    private void creditOwnerYield(long ownerId, String territoryId, String checkInRef) {
        if (cityCoinService == null) return;
        cityCoinService.creditHolderAfterCommit(ownerId, "terr:" + territoryId, yieldRef(checkInRef));
    }

    /** City coin refs are at most 80 chars; longer refs are replaced by a stable digest. */
    static String yieldRef(String ref) {
        if (ref.length() <= 80) return ref;
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "h:" + java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> formatTerritoryRow(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("territoryId", r.get("territory_id"));
        out.put("geohash7", r.get("geohash7"));
        out.put("poiId", r.get("poi_id"));
        out.put("boardId", r.get("board_id"));
        out.put("seasonId", r.get("season_id"));
        out.put("propertyId", r.get("property_id"));
        out.put("pointId", r.get("point_id"));
        out.put("title", r.get("title"));
        out.put("latitude", dbl(r.get("latitude")));
        out.put("longitude", dbl(r.get("longitude")));
        out.put("radiusM", ((Number) r.get("radius_m")).intValue());
        out.put("ownerMemberId", ((Number) r.get("owner_member_id")).longValue());
        out.put("claimPhotoUrl", r.get("claim_photo_url"));
        out.put("checkinTollPoints", ((Number) r.get("checkin_toll_points")).longValue());
        out.put("ownerRevenuePoints", ((Number) r.get("owner_revenue_points")).longValue());
        out.put("revenueMode", r.get("revenue_mode"));
        out.put("checkinCount", ((Number) r.get("checkin_count")).longValue());
        out.put("revenueTotalPoints", ((Number) r.get("revenue_total_points")).longValue());
        out.put("status", r.get("status"));
        out.put("version", ((Number) r.get("version")).longValue());
        out.put("claimedAtMillis", millis(r.get("claimed_at")));
        out.put("protectedUntilMillis", millis(r.get("protected_until")));
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static Long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static Double dbl(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static Long millis(Object v) {
        return v == null ? null : CityGameSupport.millis(v);
    }
}
