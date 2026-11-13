package com.chengyinhub.business.citygame;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.citygame.CityR491Store.Outcome;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.geo.CoordSys;
import com.chengyinhub.common.utils.geo.GeoCoordinates;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Three small CITY surfaces:
 * <ul>
 *   <li>/api/city-game/merchant/operations/** — a store asks to pause or restore its points; the intent goes to
 *       platform review (IN_REVIEW) and never changes the running board by itself (runtimeApplied is always false);</li>
 *   <li>/api/city-value/visits/** — a player begins a visit at an available public POI point of the current page and
 *       leaves feedback; visits observe, they award no points, goals or budget;</li>
 *   <li>/api/city/walking/target — the reviewed public coordinates of one city POI as a short-lived WGS84 target.</li>
 * </ul>
 */
@Service
public class CityMerchantValueService {
    static final String MERCHANT_DOMAIN = "merchant-operations";

    private final CityR491Store store;
    private final IMerchantAccessService merchants;

    public CityMerchantValueService(CityR491Store store, IMerchantAccessService merchants) {
        this.store = store;
        this.merchants = merchants;
    }

    // ---- merchant operations ----

    private MerchantAccessContext manager(long memberId) {
        try {
            MerchantAccessContext access = merchants.require(memberId, MerchantPermission.PROJECT_MANAGE);
            if (access == null || access.getMerchantId() == null) throw new CityGameException(403, "NO_AUTHORITY", "没有门店管理权限");
            return access;
        } catch (ServiceException e) {
            throw new CityGameException(403, "NO_AUTHORITY", "没有门店管理权限");
        }
    }

    /** The published relation between this merchant and the region's current board, or null. */
    private Map<String, Object> relation(long merchantId, String regionId) {
        Map<String, Object> board = store.mapper.selectCurrentBoard(regionId);
        if (!CityGameReadService.readable(board)) return null;
        List<String> points = store.jdbc.queryForList("SELECT point_id FROM city_game_point WHERE board_id = ? AND merchant_id = ? AND del_flag = 0 ORDER BY point_id",
                String.class, board.get("boardId"), merchantId);
        if (points.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("regionId", regionId);
        out.put("boardId", board.get("boardId"));
        out.put("seasonId", board.get("seasonId"));
        out.put("rulesReleaseId", board.get("rulesReleaseId"));
        out.put("pointIds", points);
        return out;
    }

    private Map<String, Object> row(long merchantId, String regionId) {
        store.jdbc.update("INSERT IGNORE INTO city_merchant_operation(merchant_id, region_id) VALUES (?,?)", merchantId, regionId);
        return store.jdbc.queryForMap("SELECT * FROM city_merchant_operation WHERE merchant_id = ? AND region_id = ?", merchantId, regionId);
    }

    private Map<String, Object> snapshot(long merchantId, Map<String, Object> row, Map<String, Object> relation, String requestId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", String.valueOf(merchantId));
        out.put("version", ((Number) row.get("version")).longValue());
        out.put("status", row.get("status"));
        out.put("effectiveState", row.get("effective_state"));
        out.put("kind", row.get("kind"));
        out.put("requestId", requestId != null ? requestId : row.get("request_id"));
        out.put("originalRequestId", row.get("request_id"));
        // The approval hash binds an intent to the exact relation it was shown with.
        out.put("approvalHash", relation == null ? null : CityR491Store.policyHash(relation));
        out.put("availability", relation == null ? "UNAVAILABLE" : "AVAILABLE");
        out.put("runtimeApplied", false);
        out.put("relation", relation);
        return out;
    }

    public Map<String, Object> availability(Long memberId, String regionId) {
        CityGameSupport.requireMember(memberId);
        CityGameSupport.requireId(regionId, "regionId");
        MerchantAccessContext access = manager(memberId);
        return snapshot(access.getMerchantId(), row(access.getMerchantId(), regionId), relation(access.getMerchantId(), regionId), null);
    }

    public Object intent(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        String requestId = CityR491Store.str(body, "requestId"), regionId = CityR491Store.str(body, "regionId"), kind = CityR491Store.str(body, "kind");
        String reason = CityR491Store.str(body, "reason"), hash = CityR491Store.str(body, "approvalHash");
        Long expected = CityR491Store.num(body, "expectedVersion");
        if (body == null || !body.keySet().equals(Set.of("requestId", "expectedVersion", "regionId", "kind", "reason", "approvalHash"))
                || !CityArrivalService.upperUuid(requestId) || !CityGameRules.id(regionId) || !Set.of("PAUSE_MERCHANT", "RESTORE").contains(kind)
                || reason == null || reason.trim().isEmpty() || reason.length() > 500 || !CityGameRules.hash(hash) || expected == null || expected < 0) {
            throw new CityGameException(400, "OBJECT_SCOPE", "门店指令无效");
        }
        MerchantAccessContext access = manager(memberId);
        long merchantId = access.getMerchantId();
        return store.once(memberId, MERCHANT_DOMAIN, requestId, CityR491Store.requestHash(body), () -> {
            Map<String, Object> relation = relation(merchantId, regionId);
            if (relation == null) return CityR491Store.refused(409, "PUBLISHED_MERCHANT_RELATION_UNAVAILABLE", requestId);
            Map<String, Object> current = row(merchantId, regionId);
            store.jdbc.queryForList("SELECT version FROM city_merchant_operation WHERE merchant_id = ? AND region_id = ? FOR UPDATE", merchantId, regionId);
            current = store.jdbc.queryForMap("SELECT * FROM city_merchant_operation WHERE merchant_id = ? AND region_id = ?", merchantId, regionId);
            if (((Number) current.get("version")).longValue() != expected) return CityR491Store.refused(409, "VERSION_CONFLICT", requestId);
            if (!hash.equals(CityR491Store.policyHash(relation))) return CityR491Store.refused(409, "APPROVAL_CHANGED", requestId);
            if ("IN_REVIEW".equals(current.get("status"))) return CityR491Store.refused(409, "PENDING_REVIEW", requestId);
            String effective = (String) current.get("effective_state");
            if ("PAUSE_MERCHANT".equals(kind) && "PAUSED".equals(effective) || "RESTORE".equals(kind) && !"PAUSED".equals(effective)) {
                return CityR491Store.refused(409, "INVALID_STATE", requestId);
            }
            store.jdbc.update("UPDATE city_merchant_operation SET version = version + 1, status = 'IN_REVIEW', kind = ?, request_id = ?, reason = ?, approval_hash = ?"
                    + " WHERE merchant_id = ? AND region_id = ?", kind, requestId, reason.trim(), hash, merchantId, regionId);
            return CityR491Store.ok(snapshot(merchantId, row(merchantId, regionId), relation, requestId));
        });
    }

    public Object merchantReceipt(Long memberId, String requestId) {
        CityGameSupport.requireMember(memberId);
        manager(memberId);
        if (!CityArrivalService.upperUuid(requestId)) throw new CityGameException(404, "CITY_RECEIPT_NOT_FOUND", "没有这条请求的回执");
        Outcome stored = store.receipt(memberId, MERCHANT_DOMAIN, requestId);
        if (stored.status == 200) {
            Map<String, Object> data = new LinkedHashMap<>(stored.data);
            data.put("requestId", requestId);
            data.put("originalRequestId", requestId);
            return new Outcome(200, null, data);
        }
        return stored;
    }

    // ---- city value visits ----

    private Map<String, Object> valueBoard(Map<String, String> q) {
        Map<String, Object> board = store.board(q.get("regionId"));
        if (!board.get("boardId").equals(q.get("boardId")) || !board.get("seasonId").equals(q.get("seasonId"))
                || !board.get("rulesReleaseId").equals(q.get("rulesReleaseId"))) {
            throw new CityGameException(409, "CITY_SNAPSHOT_CHANGED", "城市棋盘已更新");
        }
        return board;
    }

    /** POI points of the bounded page the app captured; re-derived server-side from the page binding. */
    private List<Map<String, Object>> pagePoints(Map<String, Object> board, Map<String, String> q, CityGameReadService reads) {
        Map<String, Object> page = reads.boundedPoints(board, q);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) page.get("points");
        if (!String.valueOf(page.get("watermark")).equals(q.get("mapWatermark")) || !String.valueOf(page.get("page")).equals(q.get("mapPage"))) {
            throw new CityGameException(409, "CITY_MAP_CURSOR_STALE", "地图页已变化");
        }
        return points;
    }

    public Map<String, Object> services(Long memberId, Map<String, String> q, CityGameReadService reads) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = valueBoard(q);
        Map<String, Object> page = reads.boundedPoints(board, q);
        if (!String.valueOf(page.get("watermark")).equals(q.get("mapWatermark")) || !String.valueOf(page.get("page")).equals(q.get("mapPage"))) {
            throw new CityGameException(409, "CITY_MAP_CURSOR_STALE", "地图页已变化");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) page.get("points");
        List<Map<String, Object>> services = new ArrayList<>();
        Set<Long> pois = new java.util.HashSet<>();
        for (Map<String, Object> p : points) {
            if (!"POI".equals(p.get("domain")) || !"AVAILABLE".equals(p.get("availability"))) continue;
            List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT place_id FROM city_game_point WHERE board_id = ? AND point_id = ? AND place_id IS NOT NULL",
                    board.get("boardId"), p.get("pointId"));
            if (rows.isEmpty()) continue;
            long poi = ((Number) rows.get(0).get("place_id")).longValue();
            if (!pois.add(poi)) continue;
            List<String> open = store.jdbc.queryForList("SELECT visit_id FROM city_value_visit WHERE member_id = ? AND board_id = ? AND point_id = ? AND closed = 0 LIMIT 1",
                    String.class, memberId, board.get("boardId"), p.get("pointId"));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("pointId", p.get("pointId"));
            s.put("serviceType", "CITY_NODE_COUPON_REDEMPTION");
            s.put("poiId", poi);
            s.put("canBegin", open.isEmpty());
            s.put("visitId", open.isEmpty() ? null : open.get(0));
            services.add(s);
        }
        Map<String, Object> mapPage = new LinkedHashMap<>();
        mapPage.put("watermark", page.get("watermark"));
        mapPage.put("page", page.get("page"));
        mapPage.put("pointIds", points.stream().map(p -> p.get("pointId")).collect(java.util.stream.Collectors.toList()));
        mapPage.put("complete", page.get("complete"));
        mapPage.put("pageLimitReached", page.get("pageLimitReached"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "AVAILABLE");
        out.put("regionId", board.get("regionId"));
        out.put("boardId", board.get("boardId"));
        out.put("seasonId", board.get("seasonId"));
        out.put("rulesReleaseId", board.get("rulesReleaseId"));
        out.put("services", services);
        out.put("mapPage", mapPage);
        return out;
    }

    public Map<String, Object> begin(Long memberId, Map<String, Object> body, CityGameReadService reads) {
        CityGameSupport.requireMember(memberId);
        String requestId = CityR491Store.str(body, "requestId"), pointId = CityR491Store.str(body, "pointId");
        Long poi = CityR491Store.num(body, "poiId");
        Object rawPage = body == null ? null : body.get("page");
        if (body == null || !body.keySet().equals(Set.of("requestId", "page", "pointId", "poiId")) || !CityGameRules.id(requestId) || !CityGameRules.id(pointId)
                || poi == null || poi <= 0 || !(rawPage instanceof Map)) {
            throw new CityGameException(400, "CITY_VALUE_INVALID", "到访请求无效");
        }
        Map<String, String> q = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) rawPage).entrySet()) q.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
        String hash = CityR491Store.requestHash(body);
        List<Map<String, Object>> previous = store.jdbc.queryForList("SELECT * FROM city_value_visit WHERE member_id = ? AND request_id = ?", memberId, requestId);
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.get(0).get("request_hash"))) throw new CityGameException(409, "CITY_REQUEST_REUSED", "请求编号已用于其他到访");
            return binding(previous.get(0));
        }
        Map<String, Object> board = valueBoard(q);
        if (!CityR491Store.open(board)) throw new CityGameException(409, "BOARD_NOT_OPEN", "棋盘未开放");
        List<Map<String, Object>> points = pagePoints(board, q, reads);
        boolean onPage = points.stream().anyMatch(p -> pointId.equals(p.get("pointId")) && "POI".equals(p.get("domain")) && "AVAILABLE".equals(p.get("availability")));
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT place_id FROM city_game_point WHERE board_id = ? AND point_id = ?", board.get("boardId"), pointId);
        if (!onPage || rows.isEmpty() || rows.get(0).get("place_id") == null || ((Number) rows.get(0).get("place_id")).longValue() != poi) {
            throw new CityGameException(409, "CITY_VALUE_POINT_UNAVAILABLE", "该点位当前不可到访");
        }
        Integer open = store.jdbc.queryForObject("SELECT COUNT(*) FROM city_value_visit WHERE member_id = ? AND board_id = ? AND point_id = ? AND closed = 0",
                Integer.class, memberId, board.get("boardId"), pointId);
        if (open != null && open > 0) throw new CityGameException(409, "CITY_VALUE_VISIT_OPEN", "已有进行中的到访");
        String visitId = UUID.randomUUID().toString();
        store.jdbc.update("INSERT INTO city_value_visit(visit_id, member_id, request_id, request_hash, board_id, point_id, poi_id, entered_at) VALUES (?,?,?,?,?,?,?,?)",
                visitId, memberId, requestId, hash, board.get("boardId"), pointId, poi, new Date(store.now()));
        return binding(store.jdbc.queryForMap("SELECT * FROM city_value_visit WHERE visit_id = ?", visitId));
    }

    private Map<String, Object> binding(Map<String, Object> visit) {
        Map<String, Object> board = store.mapper.selectBoard((String) visit.get("board_id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "BOUND");
        out.put("visitId", visit.get("visit_id"));
        out.put("rulesReleaseId", board.get("rulesReleaseId"));
        out.put("enteredAt", CityGameSupport.iso(visit.get("entered_at")));
        return out;
    }

    private Map<String, Object> ownVisit(long memberId, String visitId) {
        if (!CityGameRules.id(visitId)) throw new CityGameException(404, "CITY_VALUE_VISIT_NOT_FOUND", "到访不存在");
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_value_visit WHERE visit_id = ? AND member_id = ?", visitId, memberId);
        if (rows.isEmpty()) throw new CityGameException(404, "CITY_VALUE_VISIT_NOT_FOUND", "到访不存在");
        return rows.get(0);
    }

    public Map<String, Object> visit(Long memberId, String visitId) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> v = ownVisit(memberId, visitId);
        Map<String, Object> board = store.mapper.selectBoard((String) v.get("board_id"));
        boolean rated = v.get("rating") != null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "VISIT");
        out.put("visitId", v.get("visit_id"));
        out.put("rulesReleaseId", board.get("rulesReleaseId"));
        out.put("regionId", board.get("regionId"));
        out.put("boardId", board.get("boardId"));
        out.put("seasonId", board.get("seasonId"));
        out.put("pointId", v.get("point_id"));
        out.put("enteredAt", CityGameSupport.iso(v.get("entered_at")));
        out.put("poiId", ((Number) v.get("poi_id")).longValue());
        out.put("closed", ((Number) v.get("closed")).intValue() == 1);
        out.put("meaningfulAction", v.get("feedback_kind") != null ? "OBSERVED" : "NOT_OBSERVED");
        out.put("serviceSuccess", rated ? "OBSERVED" : "NOT_OBSERVED");
        out.put("publicGoal", "UNAVAILABLE");
        out.put("cooperativeGoal", "UNAVAILABLE");
        out.put("netPoints", "UNAVAILABLE");
        out.put("budget", "UNAVAILABLE");
        out.put("rating", rated ? ((Number) v.get("rating")).intValue() : null);
        return out;
    }

    public Map<String, Object> feedback(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        String visitId = CityR491Store.str(body, "visitId"), kind = CityR491Store.str(body, "kind");
        Map<String, Object> v = ownVisit(memberId, visitId);
        String reason = null;
        Integer rating = null;
        if ("SERVICE_RATING".equals(kind) && body.keySet().equals(Set.of("visitId", "kind", "rating"))) {
            Long value = CityR491Store.num(body, "rating");
            if (value == null || value < 0 || value > 100) throw new CityGameException(400, "CITY_VALUE_INVALID", "评分无效");
            rating = value.intValue();
        } else if (("SKIP".equals(kind) || "EXIT".equals(kind)) && body.keySet().equals(Set.of("visitId", "kind", "reasonCode"))) {
            reason = CityR491Store.str(body, "reasonCode");
            if (!Set.of("not_interested", "no_time", "service_unavailable", "prefer_not_to_say").contains(reason)) throw new CityGameException(400, "CITY_VALUE_INVALID", "原因无效");
        } else {
            throw new CityGameException(400, "CITY_VALUE_INVALID", "反馈无效");
        }
        if (((Number) v.get("closed")).intValue() == 1) {
            // A closed visit answers with its stored state; the same feedback again is a replay, other feedback is refused.
            if (kind.equals(v.get("feedback_kind")) && java.util.Objects.equals(reason, v.get("reason_code"))
                    && java.util.Objects.equals(rating, v.get("rating") == null ? null : ((Number) v.get("rating")).intValue())) {
                return visit(memberId, visitId);
            }
            throw new CityGameException(409, "CITY_VALUE_VISIT_CLOSED", "到访已结束");
        }
        store.jdbc.update("UPDATE city_value_visit SET closed = 1, feedback_kind = ?, reason_code = ?, rating = ? WHERE visit_id = ? AND closed = 0",
                kind, reason, rating, visitId);
        return visit(memberId, visitId);
    }

    // ---- walking target ----

    public Map<String, Object> walkingTarget(Long memberId, String poiParam) {
        CityGameSupport.requireMember(memberId);
        if (poiParam == null || !poiParam.matches("[1-9][0-9]{0,15}")) throw new CityGameException(400, "poi_id", "poi_id invalid");
        long poiId = Long.parseLong(poiParam);
        List<Map<String, Object>> rows = store.jdbc.queryForList(
                "SELECT id, name, lat, lng, city_code FROM roam_poi WHERE id = ? AND status = 1 AND (audit_status IS NULL OR audit_status = 1)", poiId);
        if (rows.isEmpty()) throw new CityGameException(404, "CITY_TARGET_UNAVAILABLE", "地点不可导航");
        Map<String, Object> poi = rows.get(0);
        // roam_poi stores reviewed GCJ-02 map coordinates; the target is converted once, on the server, to WGS84.
        GeoCoordinates.Parsed parsed = GeoCoordinates.parse(((java.math.BigDecimal) poi.get("lng")).doubleValue(),
                ((java.math.BigDecimal) poi.get("lat")).doubleValue(), CoordSys.GCJ02);
        if (!parsed.clean()) throw new CityGameException(404, "CITY_TARGET_UNAVAILABLE", "地点不可导航");
        long now = store.now();
        String revisionSource = poi.get("id") + "|" + poi.get("name") + "|" + poi.get("lat") + "|" + poi.get("lng");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("kind", "cityNode");
        out.put("id", poiId);
        out.put("title", poi.get("name"));
        out.put("longitude", parsed.point().lng());
        out.put("latitude", parsed.point().lat());
        out.put("datum", "wgs84");
        out.put("region", poi.get("city_code") == null ? "CN" : String.valueOf(poi.get("city_code")));
        out.put("authorityRevision", "city:" + poiId + ":" + CityGameRules.sha256(revisionSource).substring(0, 16));
        out.put("issuedAtMs", now);
        out.put("expiresAtMs", now + 120_000L);
        return out;
    }
}
