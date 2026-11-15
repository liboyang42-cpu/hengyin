package com.chengyinhub.business.merchantplans;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * S3a MON-07 / MON-15: merchant plans (Free, Basic, Pro, and a per-visit plan) as configured products with feature
 * flags per plan, each merchant's current plan, and plan purchase and renewal.
 *
 * <p>Money: purchase, renewal and paid grants run only while {@link MerchantPlansMoneyGate} is open. Closed, the plans
 * and the merchant's current plan stay readable and the answer says "available soon". Merchant self-checkout records
 * an order for operations to confirm once payment is received; it never calls a payment provider. Moving to Free is
 * always allowed (it charges nothing).
 *
 * <p>Every console change (plan settings, confirming an order, granting or ending a plan) writes one platform audit
 * row in the same transaction.
 */
@Service
public class MerchantPlanService {
    private static final Logger log = LoggerFactory.getLogger(MerchantPlanService.class);
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int MONTHS_MAX = 12;
    public static final int REASON_MAX = 255;
    static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final MerchantPlansMoneyGate gate;
    private final IMerchantAccessService access;
    private final OpsConsoleAudit audit;
    private final boolean selfCheckoutEnabled;

    public MerchantPlanService(JdbcTemplate jdbc, MerchantPlansMoneyGate gate, IMerchantAccessService access,
                               OpsConsoleAudit audit,
                               @Value("${merchant.commerce.self-checkout-enabled:false}") boolean selfCheckoutEnabled) {
        this.jdbc = jdbc;
        this.gate = gate;
        this.access = access;
        this.audit = audit;
        this.selfCheckoutEnabled = selfCheckoutEnabled;
    }

    // ---------------------------------------------------------------- catalogue

    public List<MerchantPlan> plans() {
        List<MerchantPlan> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT code, name_zh, name_en, monthly_price_fen, visit_fee_fen,"
                + " features_json, offered, sort_order FROM cy_merchant_plan ORDER BY sort_order, code")) {
            MerchantPlan p = toPlan(r);
            if (p != null) out.add(p);
        }
        return out;
    }

    public MerchantPlan plan(String code) {
        if (code == null) return null;
        for (MerchantPlan p : plans()) if (p.code().equals(code.trim().toUpperCase(java.util.Locale.ROOT))) return p;
        return null;
    }

    /** Free with today's features when the row is missing, so a merchant never loses what they have today. */
    MerchantPlan freePlan() {
        MerchantPlan free = plan(MerchantPlan.FREE);
        return free != null ? free : new MerchantPlan(MerchantPlan.FREE, "免费版", "Free", 0, null,
                MerchantPlan.todaysFeatures(), true, 0);
    }

    // ---------------------------------------------------------------- current plan

    /** The active subscription covering {@code at}, or null (= Free). */
    public Map<String, Object> activeSubscription(long merchantId, Instant at) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, merchant_id, plan_code, period_start, period_end,"
                + " source, order_no FROM cy_merchant_plan_subscription WHERE merchant_id = ? AND status = 'ACTIVE'"
                + " AND period_start <= ? AND period_end > ? ORDER BY period_end DESC, id DESC",
                merchantId, Timestamp.from(at), Timestamp.from(at));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The plan in force for a merchant now; Free when there is no active subscription or its plan is gone. */
    public MerchantPlan currentPlan(long merchantId) {
        Map<String, Object> sub = activeSubscription(merchantId, Instant.now());
        if (sub == null) return freePlan();
        MerchantPlan p = plan(String.valueOf(sub.get("plan_code")));
        return p != null ? p : freePlan();
    }

    /** One feature of the merchant's current plan (Free's value when unknown). For feature consumers. */
    public Object feature(long merchantId, String key) {
        return currentPlan(merchantId).feature(key);
    }

    // ---------------------------------------------------------------- merchant (app / mini-program)

    /** The plan page: offered plans, the shop's current plan and whether plans can be bought now. Read only. */
    public Map<String, Object> merchantView(Long principal) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.BASIC_READ);
        long merchant = ctx.getMerchantId();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> offered = new ArrayList<>();
        for (MerchantPlan p : plans()) if (p.offered()) offered.add(p.toMap());
        out.put("plans", offered);
        Map<String, Object> sub = activeSubscription(merchant, Instant.now());
        MerchantPlan current = currentPlan(merchant);
        Map<String, Object> cur = new LinkedHashMap<>();
        cur.put("code", current.code());
        cur.put("periodEnd", sub == null ? null : isoDay(sub.get("period_end")));
        cur.put("plan", current.toMap());
        out.put("current", cur);
        boolean open = gate.open();
        out.put("salesOpen", open);
        out.put("salesState", open ? "OPEN" : "AVAILABLE_SOON");
        out.put("merchantId", merchant);
        return out;
    }

    /**
     * Merchant self-checkout (mini-program console): records a pending order for operations to confirm once payment
     * is received. Shop owner only. Closed switch → 409 PLAN_SALES_CLOSED. Idempotent per (shop, requestId).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkout(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.BASIC_READ);
        if (!gate.open() || !selfCheckoutEnabled) {
            throw new MerchantGapException(409, MerchantPlansMoneyGate.CLOSED_CODE, MerchantPlansMoneyGate.CLOSED_MESSAGE);
        }
        if (ctx.getPrincipalMemberId() == null || !ctx.getPrincipalMemberId().equals(ctx.getOwnerMemberId())) {
            throw new MerchantGapException(403, "OWNER_ONLY", "只有店主可以购买套餐");
        }
        Object rawCode = body == null ? null : body.get("planCode");
        MerchantPlan plan = plan(rawCode == null ? null : rawCode.toString());
        if (plan == null || !plan.offered() || !plan.paid()) {
            throw new MerchantGapException(400, "PLAN_INVALID", "套餐无效");
        }
        int months = months(body == null ? null : body.get("months"));
        Object rawRequest = body.get("requestId");
        String requestId = rawRequest == null ? "" : rawRequest.toString();
        if (!requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        long merchant = ctx.getMerchantId();
        Map<String, Object> replay = orderByRequest(merchant, requestId);
        if (replay != null) return orderView(replay);
        String orderNo = "MPLAN_" + UUID.randomUUID().toString().replace("-", "");
        long amount = Math.multiplyExact((long) plan.monthlyPriceFen(), months);
        try {
            jdbc.update("INSERT INTO cy_merchant_plan_order (order_no, merchant_id, member_id, plan_code, months, amount_fen,"
                    + " status, request_id, create_time) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP)",
                    orderNo, merchant, ctx.getPrincipalMemberId(), plan.code(), months, amount, requestId);
        } catch (DuplicateKeyException race) {
            Map<String, Object> existing = orderByRequest(merchant, requestId);
            if (existing == null) throw race;
            return orderView(existing);
        }
        return orderView(orderByRequest(merchant, requestId));
    }

    // ---------------------------------------------------------------- console

    public Map<String, Object> adminView() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (MerchantPlan p : plans()) list.add(p.toMap());
        out.put("plans", list);
        out.put("salesOpen", gate.open());
        out.put("selfCheckoutEnabled", selfCheckoutEnabled);
        return out;
    }

    /**
     * Edits one plan: names, monthly price, per-visit fee, features, offered. Free stays free, keeps no visit fee and
     * stays offered, and none of its features can go below today's.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean updatePlan(String code, Map<String, Object> patch, Long sysUserId, String operator, String reason) {
        String why = reason(reason, operator);
        MerchantPlan before = plan(code);
        if (before == null) throw new IllegalArgumentException("unknown plan");
        if (patch == null) throw new IllegalArgumentException("nothing to change");
        String nameZh = text(patch, "nameZh", before.nameZh());
        String nameEn = text(patch, "nameEn", before.nameEn());
        int price = patch.containsKey("monthlyPriceFen") ? fen(patch.get("monthlyPriceFen"), false) : before.monthlyPriceFen();
        Integer visitFee = patch.containsKey("visitFeeFen") ? nullableFen(patch.get("visitFeeFen")) : before.visitFeeFen();
        @SuppressWarnings("unchecked")
        Map<String, Object> features = patch.containsKey("features")
                ? MerchantPlan.normaliseFeatures((Map<String, Object>) patch.get("features")) : before.features();
        boolean offered = patch.containsKey("offered") ? bool(patch.get("offered")) : before.offered();
        if (MerchantPlan.FREE.equals(before.code())) {
            if (price != 0 || visitFee != null || !offered) {
                throw new IllegalArgumentException("Free stays free, has no visit fee and stays offered");
            }
            Map<String, Object> today = MerchantPlan.todaysFeatures();
            for (String k : MerchantPlan.COUNT_FEATURES) {
                if (((Number) features.get(k)).intValue() < ((Number) today.get(k)).intValue())
                    throw new IllegalArgumentException("Free keeps today's features");
            }
        }
        if (price > 0 && visitFee != null) throw new IllegalArgumentException("a plan is monthly or per visit, not both");
        MerchantPlan after = new MerchantPlan(before.code(), nameZh, nameEn, price, visitFee, features, offered, before.sortOrder());
        if (after.toMap().equals(before.toMap())) return false;
        jdbc.update("UPDATE cy_merchant_plan SET name_zh = ?, name_en = ?, monthly_price_fen = ?, visit_fee_fen = ?,"
                + " features_json = ?, offered = ?, update_by = ?, update_time = CURRENT_TIMESTAMP WHERE code = ?",
                nameZh, nameEn, price, visitFee, json(features), offered ? 1 : 0, operator, before.code());
        audit.record(sysUserId, "MERCHANT_PLAN_UPDATE", "MERCHANT_PLAN", 0L, before.toMap(), after.toMap(), why);
        return true;
    }

    /** Orders, newest first; {@code status} optional (PENDING / PAID / CANCELLED). */
    public List<Map<String, Object>> orders(String status, Long merchantId) {
        StringBuilder sql = new StringBuilder("SELECT order_no, merchant_id, member_id, plan_code, months, amount_fen, status,"
                + " create_time, paid_time, confirmed_by FROM cy_merchant_plan_order WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status.trim().toUpperCase(java.util.Locale.ROOT)); }
        if (merchantId != null) { sql.append(" AND merchant_id = ?"); args.add(merchantId); }
        sql.append(" ORDER BY create_time DESC, order_no DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> subscriptions(Long merchantId) {
        if (merchantId == null) {
            return jdbc.queryForList("SELECT id, merchant_id, plan_code, period_start, period_end, source, order_no, status,"
                    + " create_by, create_time FROM cy_merchant_plan_subscription ORDER BY id DESC LIMIT 200");
        }
        return jdbc.queryForList("SELECT id, merchant_id, plan_code, period_start, period_end, source, order_no, status,"
                + " create_by, create_time FROM cy_merchant_plan_subscription WHERE merchant_id = ? ORDER BY id DESC LIMIT 200",
                merchantId);
    }

    /** Operations confirm that a pending order was paid: the plan starts or renews. Money switch must be open. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmPaid(String orderNo, Long sysUserId, String operator, String reason) {
        String why = reason(reason, operator);
        gate.requireOpen();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT order_no, merchant_id, plan_code, months, amount_fen, status"
                + " FROM cy_merchant_plan_order WHERE order_no = ? FOR UPDATE", orderNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("order not found");
        Map<String, Object> order = rows.get(0);
        if (!"PENDING".equals(order.get("status"))) throw new IllegalStateException("order is not pending");
        MerchantPlan plan = plan(String.valueOf(order.get("plan_code")));
        if (plan == null || !plan.paid()) throw new IllegalStateException("plan is no longer sold");
        long merchant = ((Number) order.get("merchant_id")).longValue();
        int months = ((Number) order.get("months")).intValue();
        if (jdbc.update("UPDATE cy_merchant_plan_order SET status = 'PAID', paid_time = CURRENT_TIMESTAMP, confirmed_by = ?"
                + " WHERE order_no = ? AND status = 'PENDING'", operator, orderNo) != 1) {
            throw new IllegalStateException("order changed meanwhile");
        }
        Map<String, Object> sub = startOrRenew(merchant, plan, months, "PURCHASE", orderNo, operator);
        audit.record(sysUserId, "MERCHANT_PLAN_ORDER_PAID", "MERCHANT_PLAN_ORDER", merchant, order, sub, why);
        return sub;
    }

    /**
     * Grants (or renews) a plan for a merchant from the console. A paid or per-visit plan needs the money switch
     * open. Free ends the current plan now and is always allowed.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> grant(Long merchantId, String planCode, Object monthsRaw, Long sysUserId, String operator,
                                     String reason) {
        String why = reason(reason, operator);
        if (merchantId == null || merchantId <= 0) throw new IllegalArgumentException("merchant is required");
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM mms_merchant WHERE id = ?", Integer.class, merchantId);
        if (exists == null || exists == 0) throw new IllegalArgumentException("merchant not found");
        MerchantPlan plan = plan(planCode);
        if (plan == null) throw new IllegalArgumentException("unknown plan");
        Map<String, Object> before = activeSubscription(merchantId, Instant.now());
        Map<String, Object> after;
        if (MerchantPlan.FREE.equals(plan.code())) {
            endActive(merchantId);
            after = Map.of("merchantId", merchantId, "planCode", MerchantPlan.FREE);
        } else {
            gate.requireOpen();
            after = startOrRenew(merchantId, plan, months(monthsRaw), "ADMIN", null, operator);
        }
        audit.record(sysUserId, "MERCHANT_PLAN_GRANT", "MERCHANT_PLAN_SUBSCRIPTION", merchantId, before, after, why);
        return after;
    }

    // ---------------------------------------------------------------- helpers

    /** Same plan: the period extends from the current end. Another plan: the current one ends now (no proration). */
    Map<String, Object> startOrRenew(long merchantId, MerchantPlan plan, int months, String source, String orderNo,
                                     String operator) {
        Instant now = Instant.now();
        Map<String, Object> current = activeSubscription(merchantId, now);
        Instant start = now;
        if (current != null && plan.code().equals(current.get("plan_code"))) {
            start = instant(current.get("period_end"));
        } else if (current != null) {
            endActive(merchantId);
        }
        Instant end = ZonedDateTime.ofInstant(start, ZONE).plusMonths(months).toInstant();
        jdbc.update("INSERT INTO cy_merchant_plan_subscription (merchant_id, plan_code, period_start, period_end, source,"
                + " order_no, status, create_by, create_time) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP)",
                merchantId, plan.code(), Timestamp.from(start), Timestamp.from(end), source, orderNo, operator);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", merchantId);
        out.put("planCode", plan.code());
        out.put("periodStart", start.toString());
        out.put("periodEnd", end.toString());
        out.put("source", source);
        out.put("orderNo", orderNo);
        log.warn("merchant {} plan {} {} until {} by {}", merchantId, plan.code(), source, end, operator);
        return out;
    }

    private void endActive(long merchantId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("UPDATE cy_merchant_plan_subscription SET status = 'ENDED', period_end = CASE WHEN period_end > ?"
                + " THEN ? ELSE period_end END WHERE merchant_id = ? AND status = 'ACTIVE'", now, now, merchantId);
    }

    private Map<String, Object> orderByRequest(long merchant, String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT order_no, plan_code, months, amount_fen, status"
                + " FROM cy_merchant_plan_order WHERE merchant_id = ? AND request_id = ?", merchant, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> orderView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderNo", row.get("order_no"));
        out.put("planCode", row.get("plan_code"));
        out.put("months", row.get("months"));
        out.put("amountFen", row.get("amount_fen"));
        out.put("status", row.get("status"));
        out.put("payment", "OPERATIONS_CONFIRM");
        return out;
    }

    private MerchantPlan toPlan(Map<String, Object> r) {
        String code = r.get("code") == null ? null : r.get("code").toString();
        if (code == null || !MerchantPlan.CODES.contains(code)) return null;
        Map<String, Object> features;
        try {
            Object raw = r.get("features_json");
            features = raw == null ? MerchantPlan.todaysFeatures()
                    : MerchantPlan.normaliseFeatures(JSON.readValue(raw.toString(), new TypeReference<Map<String, Object>>() {}));
        } catch (Exception broken) {
            log.error("plan {} has unreadable features; using Free's", code, broken);
            features = MerchantPlan.todaysFeatures();
        }
        Integer visit = r.get("visit_fee_fen") instanceof Number n ? n.intValue() : null;
        int price = r.get("monthly_price_fen") instanceof Number n ? n.intValue() : 0;
        boolean offered = r.get("offered") instanceof Number n ? n.intValue() == 1 : Boolean.TRUE.equals(r.get("offered"));
        int sort = r.get("sort_order") instanceof Number n ? n.intValue() : 0;
        return new MerchantPlan(code, String.valueOf(r.get("name_zh")), String.valueOf(r.get("name_en")),
                Math.max(0, price), visit != null && visit > 0 ? visit : null, features, offered, sort);
    }

    static int months(Object raw) {
        if (raw == null) return 1;
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 1 || n.intValue() > MONTHS_MAX) {
            throw new MerchantGapException(400, "MONTHS_INVALID", "购买月数须为 1 到 12");
        }
        return n.intValue();
    }

    private static String reason(String reason, String operator) {
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("operator is required");
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new IllegalArgumentException("reason is required");
        return why.length() > REASON_MAX ? why.substring(0, REASON_MAX) : why;
    }

    private static String text(Map<String, Object> patch, String key, String fallback) {
        if (!patch.containsKey(key)) return fallback;
        Object v = patch.get(key);
        String s = v == null ? "" : v.toString().trim();
        if (s.isEmpty() || s.length() > 32) throw new IllegalArgumentException(key + " must be 1..32 characters");
        return s;
    }

    private static int fen(Object v, boolean allowNull) {
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < 0 || n.longValue() > 100_000_000L) {
            throw new IllegalArgumentException("price must be whole fen, 0..100000000");
        }
        return n.intValue();
    }

    private static Integer nullableFen(Object v) {
        if (v == null) return null;
        int fen = fen(v, true);
        return fen == 0 ? null : fen;
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        throw new IllegalArgumentException("offered must be true or false");
    }

    static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot write features", e); }
    }

    static Instant instant(Object value) {
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof java.time.LocalDateTime l) return l.atZone(ZONE).toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        throw new IllegalStateException("unreadable time " + value);
    }

    private static String isoDay(Object value) {
        if (value == null) return null;
        return instant(value).atZone(ZONE).toLocalDate().toString();
    }
}
