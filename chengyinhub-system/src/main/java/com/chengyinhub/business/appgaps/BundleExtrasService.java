package com.chengyinhub.business.appgaps;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.business.service.support.FreeExploreBundleSwitches;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Free-explore bundle routes the app builds beyond the Phase 1 ledger (bundle-backend-spec B-01, 04, 09, 10, 16,
 * 17, 19, 20, 22, 23). Reads are holder-keyed and never lock. Writes are statements only (review, renewal intent,
 * appeal record): none moves money, consumes a unit or creates an order. Refund requests and transfers are money
 * or paid-entitlement moves and are refused in v1 ({@code BUNDLE_MONEY_WRITE_DISABLED}); their reads answer empty.
 * Not-yours and not-found stay the same {@code BUNDLE_NOT_FOUND} so ids cannot be enumerated.
 */
@Service
public class BundleExtrasService {
    static final String MONEY_WRITE_DISABLED = "BUNDLE_MONEY_WRITE_DISABLED";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STATUSES = Set.of("AVAILABLE", "RESERVED", "REDEEMED", "FORFEITED", "HELD", "REFUND_PENDING", "REFUNDED");

    private final JdbcTemplate jdbc;
    private final FreeExploreBundleSwitches switches;
    private final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BundleExtrasService(JdbcTemplate jdbc, FreeExploreBundleSwitches switches) {
        this(jdbc, switches, System::currentTimeMillis);
    }

    BundleExtrasService(JdbcTemplate jdbc, FreeExploreBundleSwitches switches, LongSupplier clock) {
        this.jdbc = jdbc;
        this.switches = switches;
        this.clock = clock;
    }

    private static FreeExploreBundleException refusal(String code, String message) { return new FreeExploreBundleException(code, message); }

    private void requireLedger() {
        if (!switches.ledgerEnabled()) throw refusal("BUNDLE_LEDGER_OFF", "自由探索套票暂未开放");
    }

    private static int limit(String value) {
        if (value == null || value.isEmpty()) return 20;
        if (!value.matches("[1-9][0-9]?")) throw refusal("BUNDLE_BAD_REQUEST", "limit 无效");
        int n = Integer.parseInt(value);
        if (n > 50) throw refusal("BUNDLE_BAD_REQUEST", "limit 无效");
        return n;
    }

    private static long cursor(String value) {
        if (value == null || value.isEmpty()) return Long.MAX_VALUE;
        if (!value.matches("[1-9][0-9]{0,17}")) throw refusal("BUNDLE_BAD_REQUEST", "cursor 无效");
        return Long.parseLong(value);
    }

    private static long id(Object value, String field) {
        String text = value == null ? null : String.valueOf(value);
        if (text == null || !text.matches("[1-9][0-9]{0,17}")) throw refusal("BUNDLE_BAD_REQUEST", field + " 无效");
        return Long.parseLong(text);
    }

    private static String key(Object value) {
        String text = value instanceof String ? ((String) value).trim() : null;
        if (text == null || text.isEmpty() || text.length() > 64) throw refusal("BUNDLE_BAD_REQUEST", "幂等键无效");
        return text;
    }

    // ---- B-01 shelf ----

    public Map<String, Object> shelf(String cursorParam, String limitParam) {
        int size = limit(limitParam);
        long before = cursor(cursorParam);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, t.name, t.img_url, (SELECT COUNT(*) FROM cms_topic_chapter c WHERE c.topic_id = t.id AND c.required = 1) AS chapters"
                        + " FROM cms_topic t WHERE t.product_type = 2 AND t.del_flag = 0 AND t.lifecycle = 3 AND t.id < ? ORDER BY t.id DESC LIMIT ?",
                before, size + 1);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(size, rows.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("topicId", String.valueOf(row.get("id")));
            item.put("topicName", row.get("name"));
            item.put("coverUrl", row.get("img_url"));
            item.put("chapterCount", ((Number) row.get("chapters")).intValue());
            item.put("unitsPerHolder", 1);
            // Price is decided per activity ticket at checkout; the shelf never invents a "from" price.
            item.put("fromPrice", null);
            item.put("validityDays", null);
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", rows.size() > size ? String.valueOf(rows.get(size - 1).get("id")) : null);
        out.put("saleEnabled", switches.saleActive());
        out.put("reservationEnabled", switches.reservationActive());
        return out;
    }

    // ---- B-04 wallet (my bundles) ----

    public Map<String, Object> wallet(Long memberId, String cursorParam, String limitParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        int size = limit(limitParam);
        long before = cursor(cursorParam);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.id, o.registration_id, o.topic_id, o.valid_from, o.valid_to, t.name AS topic_name, r.payment_status, r.registration_status"
                        + " FROM explore_bundle_order o JOIN cms_registration r ON r.id = o.registration_id LEFT JOIN cms_topic t ON t.id = o.topic_id"
                        + " WHERE o.holder_member_id = ? AND o.id < ? ORDER BY o.id DESC LIMIT ?", memberId, before, size + 1);
        long now = clock.getAsLong();
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(size, rows.size()))) {
            long registrationId = AppGapSupport.num(row.get("registration_id"));
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String status : new String[]{"AVAILABLE", "RESERVED", "REDEEMED", "FORFEITED", "HELD", "REFUND_PENDING", "REFUNDED"}) counts.put(status, 0);
            for (Map<String, Object> c : jdbc.queryForList(
                    "SELECT status, COUNT(*) AS n FROM explore_bundle_unit WHERE registration_id = ? AND holder_member_id = ? GROUP BY status",
                    registrationId, memberId)) {
                if (STATUSES.contains(c.get("status"))) counts.put((String) c.get("status"), ((Number) c.get("n")).intValue());
            }
            List<Map<String, Object>> next = jdbc.queryForList(
                    "SELECT id, start_at FROM explore_bundle_reservation WHERE registration_id = ? AND holder_member_id = ? AND status = 'BOOKED'"
                            + " AND start_at >= ? ORDER BY start_at LIMIT 1", registrationId, memberId, new Date(now));
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("registrationId", String.valueOf(registrationId));
            order.put("topicId", String.valueOf(row.get("topic_id")));
            order.put("topicName", row.get("topic_name"));
            order.put("validFrom", AppGapSupport.iso(AppGapSupport.millis(row.get("valid_from"))));
            order.put("validTo", AppGapSupport.iso(AppGapSupport.millis(row.get("valid_to"))));
            order.put("expired", AppGapSupport.millis(row.get("valid_to")) <= now);
            order.put("paymentStatus", ((Number) row.get("payment_status")).intValue());
            order.put("registrationStatus", ((Number) row.get("registration_status")).intValue());
            order.put("activation", activation(row, now));
            order.put("counts", counts);
            if (next.isEmpty()) order.put("nextReservation", null);
            else {
                Map<String, Object> reservation = new LinkedHashMap<>();
                reservation.put("reservationId", String.valueOf(next.get(0).get("id")));
                reservation.put("startAt", AppGapSupport.millis(next.get(0).get("start_at")));
                order.put("nextReservation", reservation);
            }
            orders.add(order);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", orders);
        out.put("nextCursor", rows.size() > size ? String.valueOf(rows.get(size - 1).get("id")) : null);
        return out;
    }

    /** B-06 activation state from payment/registration status and validity (no client re-derivation). */
    static String activation(Map<String, Object> row, long now) {
        int payment = ((Number) row.get("payment_status")).intValue(), registration = ((Number) row.get("registration_status")).intValue();
        if (payment == 4) return "REFUNDED";
        if (payment != 2 || registration != 2) return "PENDING_PAYMENT";
        return AppGapSupport.millis(row.get("valid_to")) <= now ? "EXPIRED" : "ACTIVE";
    }

    /** The bundle order this member holds for the registration; not-found and not-yours look the same. */
    private Map<String, Object> heldOrder(long memberId, long registrationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.*, r.payment_status, r.registration_status, r.verification_code FROM explore_bundle_order o"
                        + " JOIN cms_registration r ON r.id = o.registration_id WHERE o.registration_id = ? AND o.holder_member_id = ?",
                registrationId, memberId);
        if (rows.isEmpty()) throw refusal("BUNDLE_NOT_FOUND", "套票订单不存在");
        return rows.get(0);
    }

    private List<Map<String, Object>> units(long memberId, long registrationId) {
        return jdbc.queryForList(
                "SELECT u.id, u.chapter_id, u.unit_code, u.status, u.redemption_id, u.reservation_id, u.version, g.valid_from, g.valid_to"
                        + " FROM explore_bundle_unit u JOIN explore_bundle_grant g ON g.id = u.grant_id"
                        + " WHERE u.registration_id = ? AND u.holder_member_id = ? ORDER BY u.id", registrationId, memberId);
    }

    /** First 16 hex of SHA-256 over sorted unitId:status:version; changes only when a unit transition happens. */
    static String voucherVersion(List<Map<String, Object>> units) {
        TreeMap<Long, String> parts = new TreeMap<>();
        for (Map<String, Object> u : units) parts.put(AppGapSupport.num(u.get("id")), u.get("id") + ":" + u.get("status") + ":" + u.get("version"));
        return AppGapSupport.sha256(String.join("|", parts.values())).substring(0, 16);
    }

    // ---- B-09 / B-10 voucher ----

    public Map<String, Object> voucher(Long memberId, String registrationParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        long registrationId = id(registrationParam, "registrationId");
        Map<String, Object> order = heldOrder(memberId, registrationId);
        List<Map<String, Object>> units = units(memberId, registrationId);
        String version = voucherVersion(units);
        boolean active = "ACTIVE".equals(activation(order, clock.getAsLong()));
        List<Map<String, Object>> subs = new ArrayList<>();
        for (Map<String, Object> u : units) {
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("unitId", String.valueOf(u.get("id")));
            sub.put("chapterId", String.valueOf(u.get("chapter_id")));
            sub.put("unitCode", u.get("unit_code"));
            sub.put("status", u.get("status"));
            sub.put("validFrom", AppGapSupport.iso(AppGapSupport.millis(u.get("valid_from"))));
            sub.put("validTo", AppGapSupport.iso(AppGapSupport.millis(u.get("valid_to"))));
            sub.put("reservationId", u.get("reservation_id") == null ? null : String.valueOf(u.get("reservation_id")));
            subs.add(sub);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registrationId", String.valueOf(registrationId));
        out.put("version", version);
        out.put("voucherVersion", version);
        // The main code is the registration's existing verification code, which scan_qr_code_chapter already accepts;
        // no new redeemable token is minted. An order that is not active shows no code.
        String code = active ? (String) order.get("verification_code") : null;
        String validTo = AppGapSupport.iso(AppGapSupport.millis(order.get("valid_to")));
        out.put("mainCode", code);
        out.put("mainValidTo", code == null ? null : validTo);
        if (code == null) out.put("main", null);
        else {
            Map<String, Object> main = new LinkedHashMap<>();
            main.put("code", code);
            main.put("validTo", validTo);
            out.put("main", main);
        }
        out.put("subs", subs);
        return out;
    }

    public Map<String, Object> voucherVersion(Long memberId, String registrationParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        long registrationId = id(registrationParam, "registrationId");
        heldOrder(memberId, registrationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registrationId", String.valueOf(registrationId));
        out.put("voucherVersion", voucherVersion(units(memberId, registrationId)));
        out.put("version", out.get("voucherVersion"));
        out.put("pollIntervalHint", 5);
        return out;
    }

    // ---- B-16 waiver ----

    public Map<String, Object> waiver(Long memberId, String month) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        YearMonth target;
        try {
            target = month == null || month.isEmpty() ? YearMonth.from(Instant.ofEpochMilli(clock.getAsLong()).atZone(SHANGHAI)) : YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw refusal("BUNDLE_BAD_REQUEST", "month 无效");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT reservation_id FROM explore_bundle_waiver WHERE member_id = ? AND waiver_month = ?", memberId, target.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", target.toString());
        out.put("used", !rows.isEmpty());
        out.put("usedReservationId", rows.isEmpty() ? null : String.valueOf(rows.get(0).get("reservation_id")));
        out.put("monthlyLimit", 1);
        return out;
    }

    // ---- B-17 redemption result ----

    public Map<String, Object> redemptionResult(Long memberId, String registrationParam, String unitParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        long registrationId = id(registrationParam, "registrationId"), unitId = id(unitParam, "unitId");
        heldOrder(memberId, registrationId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id, u.status, u.redemption_id, r.merchant_id, r.create_time, r.status AS redemption_status, m.name AS merchant_name"
                        + " FROM explore_bundle_unit u LEFT JOIN redemption r ON r.id = u.redemption_id"
                        + " LEFT JOIN mms_merchant m ON m.member_id = r.merchant_id"
                        + " WHERE u.id = ? AND u.registration_id = ? AND u.holder_member_id = ?", unitId, registrationId, memberId);
        if (rows.isEmpty()) throw refusal("BUNDLE_NOT_FOUND", "单位不存在");
        Map<String, Object> u = rows.get(0);
        boolean redeemed = "REDEEMED".equals(u.get("status")) && u.get("redemption_id") != null && "ACTIVE".equals(u.get("redemption_status"));
        Map<String, Object> out = new LinkedHashMap<>();
        // REVIEW/REJECTED belong to the verification-decision domain, which does not exist yet; they are never synthesised.
        out.put("outcome", redeemed ? "SUCCESS" : "NONE");
        out.put("unitId", String.valueOf(unitId));
        out.put("status", u.get("status"));
        out.put("redemptionId", redeemed ? String.valueOf(u.get("redemption_id")) : null);
        out.put("merchantName", redeemed ? u.get("merchant_name") : null);
        out.put("at", redeemed && u.get("create_time") != null ? AppGapSupport.millis(u.get("create_time")) : null);
        out.put("reason", null);
        return out;
    }

    // ---- B-20 / B-22 reviews ----

    public Map<String, Object> submitReview(Long memberId, Map<String, Object> body) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        if (!AppGapSupport.exactKeys(body, Set.of("registrationId", "rating", "content", "idempotencyKey"))) throw refusal("BUNDLE_BAD_REQUEST", "评价请求无效");
        long registrationId = id(body.get("registrationId"), "registrationId");
        String idem = key(body.get("idempotencyKey"));
        Long rating = AppGapSupport.integer(body, "rating");
        String content = AppGapSupport.str(body, "content");
        if (rating == null || rating < 1 || rating > 5 || content == null || content.trim().isEmpty() || content.length() > 500) {
            throw refusal("BUNDLE_BAD_REQUEST", "评分或内容无效");
        }
        String fingerprint = AppGapSupport.sha256(registrationId + "|" + rating + "|" + content.trim());
        List<Map<String, Object>> replay = jdbc.queryForList("SELECT * FROM explore_bundle_review WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem);
        if (!replay.isEmpty()) return replayOrConflict(replay.get(0), fingerprint, BundleExtrasService::wireReview);
        Map<String, Object> order = heldOrder(memberId, registrationId);
        if (!"ACTIVE".equals(activation(order, clock.getAsLong())) && !"EXPIRED".equals(activation(order, clock.getAsLong()))) {
            throw refusal("BUNDLE_REVIEW_NOT_ELIGIBLE", "订单未生效，暂不能评价");
        }
        Integer redeemed = jdbc.queryForObject("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id = ? AND holder_member_id = ? AND status = 'REDEEMED'",
                Integer.class, registrationId, memberId);
        if (redeemed == null || redeemed == 0) throw refusal("BUNDLE_REVIEW_NOT_ELIGIBLE", "使用后才能评价");
        Date now = new Date(clock.getAsLong());
        try {
            jdbc.update("INSERT INTO explore_bundle_review(registration_id, holder_member_id, topic_id, rating, content, status, idempotency_key,"
                            + " request_fingerprint, create_time, update_time) VALUES (?,?,?,?,?,'PENDING',?,?,?,?)",
                    registrationId, memberId, order.get("topic_id"), rating, content.trim(), idem, fingerprint, now, now);
        } catch (DuplicateKeyException e) {
            List<Map<String, Object>> same = jdbc.queryForList("SELECT * FROM explore_bundle_review WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem);
            if (!same.isEmpty()) return replayOrConflict(same.get(0), fingerprint, BundleExtrasService::wireReview);
            throw refusal("BUNDLE_REVIEW_DUPLICATE", "这张套票已评价过");
        }
        return wireReview(jdbc.queryForMap("SELECT * FROM explore_bundle_review WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem));
    }

    public Map<String, Object> myReview(Long memberId, String registrationParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        long registrationId = id(registrationParam, "registrationId");
        Map<String, Object> order = heldOrder(memberId, registrationId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM explore_bundle_review WHERE registration_id = ? AND holder_member_id = ?", registrationId, memberId);
        Integer redeemed = jdbc.queryForObject("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id = ? AND holder_member_id = ? AND status = 'REDEEMED'",
                Integer.class, registrationId, memberId);
        String activation = activation(order, clock.getAsLong());
        String reason = !rows.isEmpty() ? "BUNDLE_REVIEW_DUPLICATE"
                : !("ACTIVE".equals(activation) || "EXPIRED".equals(activation)) ? "BUNDLE_ORDER_NOT_ACTIVE"
                : redeemed == null || redeemed == 0 ? "BUNDLE_REVIEW_NOT_ELIGIBLE" : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eligible", reason == null);
        out.put("reasonCode", reason);
        out.put("review", rows.isEmpty() ? null : wireReview(rows.get(0)));
        return out;
    }

    public Map<String, Object> reviewList(String topicParam, String cursorParam, String limitParam) {
        long topicId = id(topicParam, "topicId");
        int size = limit(limitParam);
        long before = cursor(cursorParam);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM explore_bundle_review WHERE topic_id = ? AND status = 'APPROVED' AND id < ? ORDER BY id DESC LIMIT ?", topicId, before, size + 1);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(size, rows.size()))) {
            Map<String, Object> review = wireReview(row);
            // Public list: no holder id, no idempotency key, no registration id.
            review.put("holderMemberId", null);
            review.put("registrationId", null);
            review.put("idempotencyKey", null);
            items.add(review);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", rows.size() > size ? String.valueOf(rows.get(size - 1).get("id")) : null);
        return out;
    }

    static Map<String, Object> wireReview(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(row.get("id")));
        out.put("registrationId", String.valueOf(row.get("registration_id")));
        out.put("holderMemberId", String.valueOf(row.get("holder_member_id")));
        out.put("topicId", String.valueOf(row.get("topic_id")));
        out.put("rating", ((Number) row.get("rating")).intValue());
        out.put("content", row.get("content"));
        out.put("status", row.get("status"));
        out.put("idempotencyKey", row.get("idempotency_key"));
        out.put("createTime", AppGapSupport.iso(AppGapSupport.millis(row.get("create_time"))));
        return out;
    }

    // ---- B-23 renewal intent ----

    public Map<String, Object> renewalIntent(Long memberId, Map<String, Object> body) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        Set<String> keys = body == null ? Set.of() : body.keySet();
        if (!(keys.equals(Set.of("registrationId", "intent", "note", "idempotencyKey")) || keys.equals(Set.of("registrationId", "intent", "idempotencyKey")))) {
            throw refusal("BUNDLE_BAD_REQUEST", "续费意向请求无效");
        }
        long registrationId = id(body.get("registrationId"), "registrationId");
        String idem = key(body.get("idempotencyKey"));
        String intent = AppGapSupport.str(body, "intent");
        String note = AppGapSupport.str(body, "note");
        if (!Set.of("WILL_RENEW", "MAYBE", "NO").contains(intent) || (note != null && note.length() > 200) || (body.get("note") != null && note == null)) {
            throw refusal("BUNDLE_BAD_REQUEST", "续费意向无效");
        }
        String cleaned = note == null || note.trim().isEmpty() ? null : note.trim();
        String fingerprint = AppGapSupport.sha256(registrationId + "|" + intent + "|" + (cleaned == null ? "" : cleaned));
        List<Map<String, Object>> replay = jdbc.queryForList("SELECT * FROM explore_bundle_renewal_intent WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem);
        if (!replay.isEmpty()) return replayOrConflict(replay.get(0), fingerprint, BundleExtrasService::wireRenewal);
        Map<String, Object> order = heldOrder(memberId, registrationId);
        long now = clock.getAsLong();
        String activation = activation(order, now);
        boolean inWindow = "ACTIVE".equals(activation)
                || ("EXPIRED".equals(activation) && now - AppGapSupport.millis(order.get("valid_to")) <= 30L * 24 * 3600 * 1000);
        if (!inWindow) throw refusal("BUNDLE_RENEWAL_NOT_ELIGIBLE", "当前不能登记续费意向");
        List<Long> latest = jdbc.queryForList("SELECT id FROM explore_bundle_renewal_intent WHERE registration_id = ? AND holder_member_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, registrationId, memberId);
        try {
            jdbc.update("INSERT INTO explore_bundle_renewal_intent(registration_id, holder_member_id, topic_id, intent, note, supersedes_id, idempotency_key,"
                            + " request_fingerprint, create_time) VALUES (?,?,?,?,?,?,?,?,?)",
                    registrationId, memberId, order.get("topic_id"), intent, cleaned, latest.isEmpty() ? null : latest.get(0), idem, fingerprint, new Date(now));
        } catch (DuplicateKeyException e) {
            return replayOrConflict(jdbc.queryForMap("SELECT * FROM explore_bundle_renewal_intent WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem),
                    fingerprint, BundleExtrasService::wireRenewal);
        }
        return wireRenewal(jdbc.queryForMap("SELECT * FROM explore_bundle_renewal_intent WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem));
    }

    public Map<String, Object> latestRenewal(Long memberId, String registrationParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        long registrationId = id(registrationParam, "registrationId");
        heldOrder(memberId, registrationId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM explore_bundle_renewal_intent WHERE registration_id = ? AND holder_member_id = ? ORDER BY id DESC LIMIT 1", registrationId, memberId);
        return rows.isEmpty() ? null : wireRenewal(rows.get(0));
    }

    static Map<String, Object> wireRenewal(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(row.get("id")));
        out.put("registrationId", String.valueOf(row.get("registration_id")));
        out.put("holderMemberId", String.valueOf(row.get("holder_member_id")));
        out.put("topicId", String.valueOf(row.get("topic_id")));
        out.put("intent", row.get("intent"));
        out.put("note", row.get("note"));
        out.put("supersedesId", row.get("supersedes_id") == null ? null : String.valueOf(row.get("supersedes_id")));
        out.put("idempotencyKey", row.get("idempotency_key"));
        out.put("createTime", AppGapSupport.iso(AppGapSupport.millis(row.get("create_time"))));
        return out;
    }

    // ---- B-19 voucher appeal (record only) ----

    public Map<String, Object> appeal(Long memberId, Map<String, Object> body) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        if (!AppGapSupport.exactKeys(body, Set.of("registrationId", "unitId", "reasonCode", "note", "evidenceRefs", "idempotencyKey"))) {
            throw refusal("BUNDLE_BAD_REQUEST", "申诉请求无效");
        }
        long registrationId = id(body.get("registrationId"), "registrationId"), unitId = id(body.get("unitId"), "unitId");
        String idem = key(body.get("idempotencyKey"));
        String reason = AppGapSupport.str(body, "reasonCode"), note = AppGapSupport.str(body, "note");
        Object evidence = body.get("evidenceRefs");
        if (!Set.of("MERCHANT_FORGOT_SCAN", "OTHER").contains(reason) || note == null || note.length() > 500 || !(evidence instanceof List)
                || ((List<?>) evidence).size() > 6 || !((List<?>) evidence).stream().allMatch(e -> e instanceof String && !((String) e).isEmpty() && ((String) e).length() <= 128)) {
            throw refusal("BUNDLE_BAD_REQUEST", "申诉内容无效");
        }
        Map<String, Object> content = new java.util.HashMap<>(body);
        content.remove("idempotencyKey");
        String fingerprint = AppGapSupport.sha256(AppGapSupport.canonical(content));
        List<Map<String, Object>> replay = jdbc.queryForList("SELECT * FROM explore_bundle_appeal WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem);
        if (!replay.isEmpty()) return replayOrConflict(replay.get(0), fingerprint, BundleExtrasService::wireAppeal);
        Map<String, Object> order = heldOrder(memberId, registrationId);
        long now = clock.getAsLong();
        if (!"ACTIVE".equals(activation(order, now))) throw refusal("BUNDLE_APPEAL_NOT_ELIGIBLE", "订单未生效");
        List<Map<String, Object>> unit = jdbc.queryForList(
                "SELECT u.status, u.reservation_id, r.start_at, r.merchant_id FROM explore_bundle_unit u LEFT JOIN explore_bundle_reservation r ON r.id = u.reservation_id"
                        + " WHERE u.id = ? AND u.registration_id = ? AND u.holder_member_id = ?", unitId, registrationId, memberId);
        if (unit.isEmpty()) throw refusal("BUNDLE_NOT_FOUND", "单位不存在");
        Map<String, Object> u = unit.get(0);
        if (!Set.of("AVAILABLE", "RESERVED").contains(u.get("status"))
                || (u.get("start_at") != null && AppGapSupport.millis(u.get("start_at")) > now)) {
            throw refusal("BUNDLE_APPEAL_NOT_ELIGIBLE", "该单位当前不能申诉");
        }
        Date stamp = new Date(now);
        try {
            jdbc.update("INSERT INTO explore_bundle_appeal(registration_id, unit_id, holder_member_id, merchant_id, reason_code, note, evidence_json, status,"
                            + " idempotency_key, request_fingerprint, create_time, update_time) VALUES (?,?,?,?,?,?,?,'OPEN',?,?,?,?)",
                    registrationId, unitId, memberId, u.get("merchant_id"), reason, note, JSON.toJSONString(evidence), idem, fingerprint, stamp, stamp);
        } catch (DuplicateKeyException e) {
            List<Map<String, Object>> same = jdbc.queryForList("SELECT * FROM explore_bundle_appeal WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem);
            if (!same.isEmpty()) return replayOrConflict(same.get(0), fingerprint, BundleExtrasService::wireAppeal);
            throw refusal("BUNDLE_APPEAL_DUPLICATE", "该单位已有处理中的申诉");
        }
        return wireAppeal(jdbc.queryForMap("SELECT * FROM explore_bundle_appeal WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem));
    }

    public Map<String, Object> appealReceipt(Long memberId, String idempotencyKey) {
        AppGapSupport.requireMember(memberId);
        String idem = key(idempotencyKey);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM explore_bundle_appeal WHERE holder_member_id = ? AND idempotency_key = ?", memberId, idem);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotencyKey", idem);
        out.put("receipt", rows.isEmpty() ? "NOT_FOUND" : "FOUND");
        out.put("appeal", rows.isEmpty() ? null : wireAppeal(rows.get(0)));
        return out;
    }

    public Map<String, Object> appeals(Long memberId, String registrationParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        long registrationId = id(registrationParam, "registrationId");
        heldOrder(memberId, registrationId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM explore_bundle_appeal WHERE registration_id = ? AND holder_member_id = ? ORDER BY id DESC LIMIT 50", registrationId, memberId)) {
            items.add(wireAppeal(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    static Map<String, Object> wireAppeal(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(row.get("id")));
        out.put("registrationId", String.valueOf(row.get("registration_id")));
        out.put("unitId", String.valueOf(row.get("unit_id")));
        out.put("holderMemberId", String.valueOf(row.get("holder_member_id")));
        out.put("reasonCode", row.get("reason_code"));
        out.put("status", row.get("status"));
        out.put("applyState", row.get("apply_state"));
        out.put("idempotencyKey", row.get("idempotency_key"));
        out.put("decisionReason", row.get("decision_reason"));
        out.put("version", ((Number) row.get("version")).intValue());
        return out;
    }

    // ---- refund and transfer: reads answer, writes refuse (money / paid entitlement moves are off in v1) ----

    public Map<String, Object> refundProgress(Long memberId, String registrationParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        long registrationId = id(registrationParam, "registrationId");
        heldOrder(memberId, registrationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registrationId", String.valueOf(registrationId));
        out.put("state", "NONE");
        out.put("refundNo", null);
        return out;
    }

    public Map<String, Object> transferPreview(Long memberId, String registrationParam) {
        AppGapSupport.requireMember(memberId);
        requireLedger();
        long registrationId = id(registrationParam, "registrationId");
        heldOrder(memberId, registrationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registrationId", String.valueOf(registrationId));
        out.put("eligible", false);
        out.put("ineligibleCode", MONEY_WRITE_DISABLED);
        out.put("channels", List.of());
        out.put("impact", List.of());
        return out;
    }

    public Map<String, Object> transferReceipt(Long memberId, String idempotencyKey) {
        AppGapSupport.requireMember(memberId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotencyKey", key(idempotencyKey));
        out.put("receipt", "NOT_FOUND");
        out.put("transfer", null);
        return out;
    }

    public static FreeExploreBundleException moneyWriteDisabled() {
        return refusal(MONEY_WRITE_DISABLED, "套票退款与转赠在当前版本不开放");
    }

    private static Map<String, Object> replayOrConflict(Map<String, Object> row, String fingerprint,
                                                        java.util.function.Function<Map<String, Object>, Map<String, Object>> wire) {
        if (!fingerprint.equals(row.get("request_fingerprint"))) throw refusal("BUNDLE_IDEMPOTENCY_CONFLICT", "该幂等键已用于不同的请求内容");
        return wire.apply(row);
    }

    static LocalDate today(long millis) { return Instant.ofEpochMilli(millis).atZone(SHANGHAI).toLocalDate(); }
}
