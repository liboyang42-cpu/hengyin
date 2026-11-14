package com.chengyinhub.business.citygame;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;

/**
 * Merchant side of the CITY value chain (/api/merchant/city-value/*). The only behaviour source is the player value
 * visit ({@code city_value_visit}, /api/city-value/visits) at the merchant's own points of the region's current board:
 * entry, explicit SKIP/EXIT with a reason, and SERVICE_RATING. A rated visit is the service success; no coupon
 * redemption is linked to a visit, so benefit records answer SOURCE_UNAVAILABLE instead of claiming a grant.
 * Goals, cohort, first-choice timing, net points and budget have no emitter or no unit, and are returned as
 * MISSING_* with no numbers (never 0). Reports are stored by request id; events come from a frozen snapshot.
 */
@Service
public class MerchantCityValueReportService {
    static final String SERVICE_TYPE = "CITY_NODE_COUPON_REDEMPTION";
    static final String SOURCE = "EXPLICIT_VISIT_AND_COMMITTED_CITY_NODE_AUDIT";
    static final List<String> REASONS = List.of("not_interested", "no_time", "service_unavailable", "prefer_not_to_say");
    private static final Pattern CURSOR = Pattern.compile("v2\\.([1-9][0-9]{0,18})\\.([0-9]{1,5})\\.([0-9a-f]{64})");
    private static final int EVENTS_PAGE = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CityR491Store store;
    private final IMerchantAccessService access;

    public MerchantCityValueReportService(CityR491Store store, IMerchantAccessService access) {
        this.store = store;
        this.access = access;
    }

    /** Scope + window the app sent, checked against the region's current board and the merchant's own points. */
    final class Scope {
        final long merchantId;
        final long actor;
        final String regionId, boardId, seasonId, rulesReleaseId, from, to;
        final Instant start, end;
        final List<String> points;

        Scope(long merchantId, long actor, Map<String, String> q, Map<String, Object> board, List<String> points, Instant start, Instant end) {
            this.merchantId = merchantId;
            this.actor = actor;
            this.regionId = q.get("regionId");
            this.boardId = q.get("boardId");
            this.seasonId = q.get("seasonId");
            this.rulesReleaseId = q.get("rulesReleaseId");
            this.from = q.get("from");
            this.to = q.get("to");
            this.start = start;
            this.end = end;
            this.points = points;
        }

        String hash() {
            return MerchantGapSupport.sha256(merchantId + "|" + regionId + "|" + boardId + "|" + seasonId + "|" + rulesReleaseId + "|" + from + "|" + to);
        }

        void echo(Map<String, Object> out) {
            out.put("regionId", regionId);
            out.put("boardId", boardId);
            out.put("seasonId", seasonId);
            out.put("rulesReleaseId", rulesReleaseId);
            out.put("from", from);
            out.put("to", to);
            out.put("merchantId", merchantId);
        }
    }

    Scope scope(Long principal, Map<String, String> q) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.BASIC_READ);
        for (String key : List.of("regionId", "boardId", "seasonId", "rulesReleaseId")) {
            if (!CityGameRules.id(q.get(key))) throw new MerchantGapException(400, "INVALID_SCOPE", "范围参数无效");
        }
        Instant start = instant(q.get("from"));
        Instant end = instant(q.get("to"));
        if (start == null || end == null || !end.isAfter(start) || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0) {
            throw new MerchantGapException(400, "INVALID_WINDOW", "时间窗无效");
        }
        Map<String, Object> board = store.mapper.selectCurrentBoard(q.get("regionId"));
        if (!CityGameReadService.readable(board)) throw new MerchantGapException(409, "NO_CURRENT_BOARD", "城市棋盘未发布");
        if (!q.get("boardId").equals(String.valueOf(board.get("boardId"))) || !q.get("seasonId").equals(String.valueOf(board.get("seasonId")))
                || !q.get("rulesReleaseId").equals(String.valueOf(board.get("rulesReleaseId")))) {
            throw new MerchantGapException(409, "SCOPE_STALE", "棋盘已更新，请刷新");
        }
        List<String> points = store.jdbc.queryForList(
                "SELECT point_id FROM city_game_point WHERE board_id = ? AND merchant_id = ? AND del_flag = 0 ORDER BY point_id",
                String.class, q.get("boardId"), ctx.getMerchantId());
        if (points.isEmpty()) throw new MerchantGapException(403, "NO_BOARD_RELATION", "本店不在当前棋盘上");
        return new Scope(ctx.getMerchantId(), principal, q, board, points, start, end);
    }

    /** Canonical second-precision UTC ISO-8601 ("2026-11-13T00:00:00Z"), as ISO8601DateFormatter writes it. */
    static Instant instant(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")) return null;
        try {
            Instant parsed = Instant.parse(value);
            return DateTimeFormatter.ISO_INSTANT.format(parsed.truncatedTo(ChronoUnit.SECONDS)).equals(value) ? parsed : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Map<String, Object>> visits(Scope s) {
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(s.boardId);
        for (String p : s.points) {
            in.append(in.length() == 0 ? "?" : ",?");
            args.add(p);
        }
        args.add(Timestamp.from(s.start));
        args.add(Timestamp.from(s.end));
        return store.jdbc.queryForList("SELECT visit_id, point_id, entered_at, closed, feedback_kind, reason_code, rating FROM city_value_visit WHERE board_id = ? AND point_id IN ("
                + in + ") AND entered_at >= ? AND entered_at < ? ORDER BY entered_at, visit_id", args.toArray());
    }

    // ---- report ----

    public Map<String, Object> report(Long principal, Map<String, String> q) {
        if (q.keySet().size() != 7 || !CityGameRules.id(q.get("requestId"))) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        Scope s = scope(principal, q);
        String requestId = q.get("requestId");
        String requestHash = s.hash();
        Map<String, Object> stored = storedReport(s.merchantId, requestId);
        if (stored != null) return replay(stored, requestHash, principal);
        Map<String, Object> body = compute(s);
        try {
            return store.tx.execute(status -> {
                store.jdbc.update("INSERT INTO merchant_city_value_report(merchant_id, actor_id, request_id, request_hash, region_id, rules_release_id, body_json, create_time) VALUES (?,?,?,?,?,?,?,?)",
                        s.merchantId, principal, requestId, requestHash, s.regionId, s.rulesReleaseId, "{}", new Timestamp(store.now()));
                long resultId = store.jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "REPORTED");
                s.echo(out);
                out.put("resultId", resultId);
                out.putAll(body);
                store.jdbc.update("UPDATE merchant_city_value_report SET body_json = ? WHERE id = ?", json(out), resultId);
                return out;
            });
        } catch (DuplicateKeyException e) {
            return replay(storedReport(s.merchantId, requestId), requestHash, principal);
        }
    }

    public Map<String, Object> result(Long principal, long id, Map<String, String> q) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.BASIC_READ);
        if (id <= 0 || q.keySet().size() != 2 || !CityGameRules.id(q.get("regionId")) || !CityGameRules.id(q.get("rulesReleaseId"))) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        }
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM merchant_city_value_report WHERE id = ? AND merchant_id = ?", id, ctx.getMerchantId());
        if (rows.isEmpty()) throw new MerchantGapException(404, "RESULT_NOT_FOUND", "没有这份报告");
        Map<String, Object> row = rows.get(0);
        if (!q.get("regionId").equals(row.get("region_id")) || !q.get("rulesReleaseId").equals(row.get("rules_release_id"))) {
            throw new MerchantGapException(409, "SCOPE_MISMATCH", "报告不属于这个范围");
        }
        return parse((String) row.get("body_json"));
    }

    private Map<String, Object> storedReport(long merchantId, String requestId) {
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM merchant_city_value_report WHERE merchant_id = ? AND request_id = ?", merchantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> replay(Map<String, Object> row, String requestHash, long principal) {
        if (!requestHash.equals(row.get("request_hash"))) throw new MerchantGapException(409, "DUPLICATE_REQUEST", "请求号已用于其他范围");
        if (((Number) row.get("actor_id")).longValue() != principal) throw new MerchantGapException(409, "ACCOUNT_CHANGED", "请求号属于另一位操作者");
        return parse((String) row.get("body_json"));
    }

    Map<String, Object> compute(Scope s) {
        List<Map<String, Object>> visits = visits(s);
        int entries = visits.size();
        int meaningful = 0, success = 0, ratingSum = 0;
        Map<String, Integer> skip = new TreeMap<>(), exit = new TreeMap<>();
        Map<String, int[]> byService = new TreeMap<>();
        for (Map<String, Object> v : visits) {
            String kind = (String) v.get("feedback_kind");
            if (kind != null) meaningful++;
            int[] svc = byService.computeIfAbsent((String) v.get("point_id"), k -> new int[3]);
            if ("SERVICE_RATING".equals(kind) && v.get("rating") != null) {
                success++;
                int rating = ((Number) v.get("rating")).intValue();
                ratingSum += rating;
                svc[0]++;
                svc[1]++;
                svc[2] += rating;
            } else if ("SKIP".equals(kind)) {
                skip.merge((String) v.get("reason_code"), 1, Integer::sum);
            } else if ("EXIT".equals(kind)) {
                exit.merge((String) v.get("reason_code"), 1, Integer::sum);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceCollection", SOURCE);
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("entry", entries == 0 ? missing("MISSING_COVERAGE") : visitsMetric(entries, null));
        funnel.put("meaningfulAction", meaningful == 0 ? missing("MISSING_COVERAGE") : visitsMetric(meaningful, entries));
        funnel.put("serviceSuccess", success == 0 ? missing("MISSING_COVERAGE") : visitsMetric(success, entries));
        funnel.put("publicGoal", missing("MISSING_COVERAGE"));
        funnel.put("cooperativeGoal", missing("MISSING_COVERAGE"));
        out.put("funnel", funnel);
        out.put("timeToFirstEffectiveChoice", missing("MISSING_DEFINITION"));
        if (success == 0) {
            out.put("serviceRatings", missing("MISSING_COVERAGE"));
        } else {
            Map<String, Object> ratings = metric("MEASURED");
            ratings.put("sum", (long) ratingSum);
            ratings.put("count", success);
            ratings.put("mean", ratingSum + "/" + success);
            out.put("serviceRatings", ratings);
        }
        out.put("byCohort", missing("MISSING_DEFINITION"));
        Map<String, Object> group = new LinkedHashMap<>();
        if (entries == 0) {
            group.put("status", "MISSING_COVERAGE");
            group.put("services", null);
        } else {
            List<Map<String, Object>> services = new ArrayList<>();
            for (Map.Entry<String, int[]> e : byService.entrySet()) {
                Map<String, Object> svc = new LinkedHashMap<>();
                svc.put("serviceId", e.getKey());
                svc.put("serviceSuccess", e.getValue()[0]);
                svc.put("ratingCount", e.getValue()[1] == 0 ? null : e.getValue()[1]);
                svc.put("ratingSum", e.getValue()[1] == 0 ? null : e.getValue()[2]);
                services.add(svc);
            }
            group.put("status", "MEASURED");
            group.put("services", services);
        }
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("serviceType", SERVICE_TYPE);
        type.put("services", group);
        Map<String, Object> types = new LinkedHashMap<>();
        types.put("status", "MEASURED");
        types.put("types", List.of(type));
        out.put("byServiceType", types);
        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("skip", reasonCounts(entries, skip));
        reasons.put("exit", reasonCounts(entries, exit));
        out.put("skipExitReasons", reasons);
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("netPoints", missing("MISSING_UNIT"));
        cost.put("budget", missing("MISSING_UNIT"));
        out.put("visitCost", cost);
        return out;
    }

    private static Map<String, Object> reasonCounts(int entries, Map<String, Integer> counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (entries == 0) {
            out.put("status", "MISSING_COVERAGE");
            out.put("reasons", null);
            return out;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String reason : REASONS) {
            Integer n = counts.get(reason);
            if (n == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reasonCode", reason);
            item.put("visitCount", n);
            items.add(item);
        }
        out.put("status", "MEASURED");
        out.put("reasons", items);
        return out;
    }

    private static Map<String, Object> metric(String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        return m;
    }

    private static Map<String, Object> missing(String status) { return metric(status); }

    private static Map<String, Object> visitsMetric(int count, Integer denominator) {
        Map<String, Object> m = metric("MEASURED");
        m.put("visitCount", count);
        if (denominator != null && denominator > 0) m.put("rate", count + "/" + denominator);
        return m;
    }

    // ---- events (frozen snapshot) ----

    public Map<String, Object> events(Long principal, Map<String, String> q) {
        if (q.keySet().size() != 7 || !q.containsKey("cursor")) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        Scope s = scope(principal, q);
        String cursor = q.get("cursor");
        Map<String, Object> snapshot;
        int page;
        if (cursor.isEmpty()) {
            snapshot = freeze(s);
            page = 0;
        } else {
            Matcher m = CURSOR.matcher(cursor);
            if (!m.matches()) throw new MerchantGapException(400, "INVALID_CURSOR", "游标无效");
            snapshot = ownSnapshot(s, Long.parseLong(m.group(1)));
            page = Integer.parseInt(m.group(2));
            if (!cursor(snapshot, s, page).equals(cursor)) throw new MerchantGapException(400, "INVALID_CURSOR", "游标无效");
        }
        long snapshotId = ((Number) snapshot.get("id")).longValue();
        List<Map<String, Object>> facts = store.jdbc.queryForList(
                "SELECT * FROM merchant_city_value_fact WHERE snapshot_id = ? ORDER BY id LIMIT ? OFFSET ?",
                snapshotId, EVENTS_PAGE + 1, (long) page * EVENTS_PAGE);
        boolean more = facts.size() > EVENTS_PAGE && page < 99999;
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> f : facts.subList(0, Math.min(EVENTS_PAGE, facts.size()))) events.add(factView(f));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "PAGE");
        s.echo(out);
        out.put("snapshotId", snapshotId);
        out.put("snapshotCreatedAt", iso(snapshot.get("create_time")));
        out.put("lateEvents", "NEW_SNAPSHOT_REQUIRED");
        out.put("retryCursor", cursor(snapshot, s, page));
        out.put("nextCursor", more ? cursor(snapshot, s, page + 1) : null);
        out.put("events", events);
        return out;
    }

    public Map<String, Object> benefit(Long principal, long eventId, Map<String, String> q) {
        if (q.keySet().size() != 7 || eventId <= 0) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        Scope s = scope(principal, q);
        long snapshotId;
        try {
            snapshotId = Long.parseLong(q.get("snapshotId"));
        } catch (RuntimeException e) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "快照无效");
        }
        ownSnapshot(s, snapshotId);
        List<Map<String, Object>> rows = store.jdbc.queryForList(
                "SELECT * FROM merchant_city_value_fact WHERE id = ? AND snapshot_id = ? AND merchant_id = ?", eventId, snapshotId, s.merchantId);
        if (rows.isEmpty()) throw new MerchantGapException(404, "EVENT_NOT_FOUND", "没有这条事件");
        Map<String, Object> fact = rows.get(0);
        if (!"SERVICE_SUCCESS".equals(fact.get("kind"))) throw new MerchantGapException(409, "NOT_A_SERVICE_SUCCESS", "该事件不是服务成功");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "BENEFIT_RECORD");
        out.put("evidenceStatus", "SOURCE_UNAVAILABLE");
        s.echo(out);
        out.put("snapshotId", snapshotId);
        out.put("eventId", eventId);
        out.put("visitId", fact.get("visit_id"));
        out.put("serviceId", fact.get("service_id"));
        out.put("serviceType", fact.get("service_type"));
        out.put("occurredAt", fact.get("occurred_at"));
        out.put("sourceKind", null);
        out.put("benefit", null);
        out.put("sourceRecordId", null);
        out.put("couponId", null);
        return out;
    }

    private Map<String, Object> ownSnapshot(Scope s, long id) {
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM merchant_city_value_snapshot WHERE id = ? AND merchant_id = ?", id, s.merchantId);
        if (rows.isEmpty()) throw new MerchantGapException(404, "SNAPSHOT_NOT_FOUND", "没有这个快照");
        Map<String, Object> row = rows.get(0);
        if (!s.hash().equals(row.get("scope_hash"))) throw new MerchantGapException(409, "SCOPE_MISMATCH", "快照不属于这个范围");
        if (((Number) row.get("actor_id")).longValue() != s.actor) throw new MerchantGapException(409, "ACCOUNT_CHANGED", "快照属于另一位操作者");
        return row;
    }

    private Map<String, Object> freeze(Scope s) {
        List<Map<String, Object>> visits = visits(s);
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        return store.tx.execute(status -> {
            store.jdbc.update("INSERT INTO merchant_city_value_snapshot(merchant_id, actor_id, scope_hash, salt, fact_count, create_time) VALUES (?,?,?,?,?,?)",
                    s.merchantId, s.actor, s.hash(), HexFormat.of().formatHex(salt), 0, new Timestamp(store.now()));
            long id = store.jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            int n = 0;
            for (Map<String, Object> v : visits) {
                String at = iso(v.get("entered_at"));
                String visit = (String) v.get("visit_id");
                String point = (String) v.get("point_id");
                n += fact(id, s.merchantId, "ENTRY", at, visit, point, null, null);
                String kind = (String) v.get("feedback_kind");
                if (kind == null) continue;
                n += fact(id, s.merchantId, "MEANINGFUL_ACTION", at, visit, point, null, null);
                if ("SERVICE_RATING".equals(kind)) {
                    Integer rating = v.get("rating") == null ? null : ((Number) v.get("rating")).intValue();
                    n += fact(id, s.merchantId, "SERVICE_SUCCESS", at, visit, point, null, null);
                    n += fact(id, s.merchantId, "SERVICE_RATING", at, visit, point, null, rating);
                } else {
                    n += fact(id, s.merchantId, kind, at, visit, point, (String) v.get("reason_code"), null);
                }
            }
            store.jdbc.update("UPDATE merchant_city_value_snapshot SET fact_count = ? WHERE id = ?", n, id);
            return store.jdbc.queryForMap("SELECT * FROM merchant_city_value_snapshot WHERE id = ?", id);
        });
    }

    private int fact(long snapshot, long merchantId, String kind, String at, String visit, String point, String reason, Integer rating) {
        return store.jdbc.update("INSERT INTO merchant_city_value_fact(snapshot_id, merchant_id, kind, occurred_at, visit_id, service_id, service_type, reason_code, rating) VALUES (?,?,?,?,?,?,?,?,?)",
                snapshot, merchantId, kind, at, visit, point, SERVICE_TYPE, reason, rating);
    }

    private static Map<String, Object> factView(Map<String, Object> f) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", ((Number) f.get("id")).longValue());
        e.put("kind", f.get("kind"));
        e.put("occurredAt", f.get("occurred_at"));
        e.put("visitId", f.get("visit_id"));
        e.put("merchantId", ((Number) f.get("merchant_id")).longValue());
        e.put("serviceId", f.get("service_id"));
        e.put("serviceType", f.get("service_type"));
        e.put("reasonCode", f.get("reason_code"));
        e.put("rating", f.get("rating") == null ? null : ((Number) f.get("rating")).intValue());
        return e;
    }

    static String cursor(Map<String, Object> snapshot, Scope s, int page) {
        long id = ((Number) snapshot.get("id")).longValue();
        String mac = MerchantGapSupport.sha256(snapshot.get("salt") + "|" + id + "|" + page + "|" + s.actor + "|" + s.hash());
        return "v2." + id + "." + page + "." + mac;
    }

    private static String iso(Object value) {
        Instant at = value instanceof java.util.Date d ? d.toInstant()
                : value instanceof java.time.LocalDateTime l ? l.atZone(java.time.ZoneId.systemDefault()).toInstant() : null;
        return at == null ? null : DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS));
    }

    private static String json(Map<String, Object> body) {
        try {
            return MerchantGapSupport.JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) {
        try {
            return MerchantGapSupport.JSON.readValue(body, LinkedHashMap.class);
        } catch (Exception e) {
            throw new MerchantGapException(503, "RESULT_UNREADABLE", "报告暂不可读");
        }
    }
}
