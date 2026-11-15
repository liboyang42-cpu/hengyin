package com.chengyinhub.business.merchantplans;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProof;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProofService;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;

/**
 * S3a: verified-visit billing for merchants on a per-visit plan.
 *
 * <p>A billable visit is exactly a "verified visit" of the weekly proof: one player on one local day (Asia/Shanghai)
 * with at least one staff verification at the shop (ticket, coupon or map-node redemption), inside the period of a
 * per-visit plan. One ledger row per (shop, player, day) in {@code cy_merchant_visit_charge}, with the plan's visit
 * fee at accrual time. Invoices are monthly ({@code cy_merchant_visit_invoice}), one per shop and month, and only for
 * months that have ended.
 *
 * <p>Money: accrual and invoicing run only while {@link MerchantPlansMoneyGate} is open. Closed, nothing is written,
 * so no visit made while billing was off can be charged later. Nothing here collects a payment.
 */
@Service
public class MerchantVisitBillingService {
    private static final Logger log = LoggerFactory.getLogger(MerchantVisitBillingService.class);

    private final JdbcTemplate jdbc;
    private final MerchantPlansMoneyGate gate;
    private final MerchantPlanService plans;
    private final MerchantWeeklyProofService proof;
    private final IMerchantAccessService access;
    private final OpsConsoleAudit audit;

    public MerchantVisitBillingService(JdbcTemplate jdbc, MerchantPlansMoneyGate gate, MerchantPlanService plans,
                                       MerchantWeeklyProofService proof, IMerchantAccessService access, OpsConsoleAudit audit) {
        this.jdbc = jdbc;
        this.gate = gate;
        this.plans = plans;
        this.proof = proof;
        this.access = access;
        this.audit = audit;
    }

    /** The distinct players with a staff verification inside [from, to), by local day. Pure; tested directly. */
    public static Set<String> billableVisits(List<MerchantWeeklyProof.Touch> touches, Instant from, Instant to) {
        Set<String> out = new LinkedHashSet<>();
        if (touches == null) return out;
        for (MerchantWeeklyProof.Touch t : touches) {
            if (t == null || !t.kind().staffVerified() || t.at().isBefore(from) || !t.at().isBefore(to)) continue;
            out.add(t.memberId() + "@" + t.at().atZone(MerchantWeeklyProof.ZONE).toLocalDate());
        }
        return out;
    }

    /** Accrues one day. Idempotent. Closed switch: writes nothing. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> accrueDay(LocalDate day) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("day", day.toString());
        if (!gate.open()) {
            result.put("status", "CLOSED");
            result.put("written", 0);
            return result;
        }
        if (day.isAfter(LocalDate.now(MerchantWeeklyProof.ZONE))) throw new IllegalArgumentException("day is in the future");
        Instant start = MerchantWeeklyProof.startOf(day), end = MerchantWeeklyProof.startOf(day.plusDays(1));
        String month = YearMonth.from(day).toString();
        int written = 0, skippedInvoiced = 0;
        List<Map<String, Object>> subs = jdbc.queryForList("SELECT s.merchant_id, s.plan_code, s.period_start, s.period_end,"
                + " m.member_id AS owner_id FROM cy_merchant_plan_subscription s JOIN mms_merchant m ON m.id = s.merchant_id"
                + " WHERE s.period_start < ? AND s.period_end > ? ORDER BY s.merchant_id, s.id",
                java.sql.Timestamp.from(end), java.sql.Timestamp.from(start));
        for (Map<String, Object> s : subs) {
            MerchantPlan plan = plans.plan(String.valueOf(s.get("plan_code")));
            if (plan == null || !plan.perVisit() || !(s.get("owner_id") instanceof Number owner)) continue;
            long merchant = ((Number) s.get("merchant_id")).longValue();
            if (invoiced(merchant, month)) { skippedInvoiced++; continue; }
            Instant from = max(start, MerchantPlanService.instant(s.get("period_start")));
            Instant to = min(end, MerchantPlanService.instant(s.get("period_end")));
            if (!from.isBefore(to)) continue;
            for (String visit : billableVisits(proof.touches(owner.longValue(), merchant, from, to), from, to)) {
                long member = Long.parseLong(visit.substring(0, visit.indexOf('@')));
                if (insertCharge(merchant, member, day, plan, month)) written++;
            }
        }
        result.put("status", "ACCRUED");
        result.put("written", written);
        result.put("skippedInvoicedShops", skippedInvoiced);
        if (written > 0) log.info("visit billing {}: {} charges", day, written);
        return result;
    }

    /** Issues one invoice per shop for an ended month. Money switch must be open. One audit row per run. */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> issueInvoices(YearMonth month, Long sysUserId, String operator, String reason) {
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("operator is required");
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new IllegalArgumentException("reason is required");
        gate.requireOpen();
        if (!month.isBefore(YearMonth.now(MerchantWeeklyProof.ZONE))) throw new IllegalArgumentException("the month has not ended");
        List<Map<String, Object>> issued = new ArrayList<>();
        for (Map<String, Object> g : jdbc.queryForList("SELECT merchant_id, COUNT(*) AS visits, SUM(fee_fen) AS amount"
                + " FROM cy_merchant_visit_charge WHERE invoice_month = ? AND invoice_id IS NULL GROUP BY merchant_id"
                + " ORDER BY merchant_id", month.toString())) {
            long merchant = ((Number) g.get("merchant_id")).longValue();
            if (invoiced(merchant, month.toString())) continue;
            long visits = ((Number) g.get("visits")).longValue(), amount = ((Number) g.get("amount")).longValue();
            jdbc.update("INSERT INTO cy_merchant_visit_invoice (merchant_id, invoice_month, visit_count, amount_fen, status,"
                    + " issued_by, issued_time) VALUES (?, ?, ?, ?, 'ISSUED', ?, CURRENT_TIMESTAMP)",
                    merchant, month.toString(), visits, amount, operator);
            Long id = jdbc.queryForObject("SELECT id FROM cy_merchant_visit_invoice WHERE merchant_id = ? AND invoice_month = ?",
                    Long.class, merchant, month.toString());
            jdbc.update("UPDATE cy_merchant_visit_charge SET invoice_id = ? WHERE merchant_id = ? AND invoice_month = ?"
                    + " AND invoice_id IS NULL", id, merchant, month.toString());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("invoiceId", id);
            row.put("merchantId", merchant);
            row.put("month", month.toString());
            row.put("visits", visits);
            row.put("amountFen", amount);
            issued.add(row);
        }
        audit.record(sysUserId, "MERCHANT_VISIT_INVOICE_ISSUE", "MERCHANT_VISIT_INVOICE", 0L,
                Map.of("month", month.toString()), Map.of("invoices", issued), why);
        return issued;
    }

    public List<Map<String, Object>> charges(Long merchantId, String month) {
        return filtered("SELECT id, merchant_id, member_id, visit_day, plan_code, fee_fen, invoice_month, invoice_id,"
                + " create_time FROM cy_merchant_visit_charge", merchantId, month, " ORDER BY visit_day DESC, id DESC LIMIT 500");
    }

    public List<Map<String, Object>> invoices(Long merchantId, String month) {
        return filtered("SELECT id, merchant_id, invoice_month, visit_count, amount_fen, status, issued_by, issued_time"
                + " FROM cy_merchant_visit_invoice", merchantId, month, " ORDER BY invoice_month DESC, id DESC LIMIT 200");
    }

    private List<Map<String, Object>> filtered(String select, Long merchantId, String month, String tail) {
        StringBuilder sql = new StringBuilder(select).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (merchantId != null) { sql.append(" AND merchant_id = ?"); args.add(merchantId); }
        if (month != null && !month.isBlank()) {
            YearMonth.parse(month.trim());
            sql.append(" AND invoice_month = ?");
            args.add(month.trim());
        }
        return jdbc.queryForList(sql.append(tail).toString(), args.toArray());
    }

    /** The shop's own billing: this month's billable visits and its invoices (no player identities). */
    public Map<String, Object> merchantView(Long principal) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.FINANCE_READ);
        long merchant = ctx.getMerchantId();
        String month = YearMonth.now(MerchantWeeklyProof.ZONE).toString();
        Map<String, Object> out = new LinkedHashMap<>();
        MerchantPlan current = plans.currentPlan(merchant);
        out.put("perVisit", current.perVisit());
        out.put("visitFeeFen", current.visitFeeFen());
        Map<String, Object> m = jdbc.queryForMap("SELECT COUNT(*) AS visits, COALESCE(SUM(fee_fen), 0) AS amount"
                + " FROM cy_merchant_visit_charge WHERE merchant_id = ? AND invoice_month = ?", merchant, month);
        out.put("month", month);
        out.put("visits", ((Number) m.get("visits")).longValue());
        out.put("amountFen", ((Number) m.get("amount")).longValue());
        out.put("invoices", jdbc.queryForList("SELECT invoice_month, visit_count, amount_fen, status FROM cy_merchant_visit_invoice"
                + " WHERE merchant_id = ? ORDER BY invoice_month DESC LIMIT 12", merchant));
        out.put("billingOpen", gate.open());
        return out;
    }

    private boolean insertCharge(long merchant, long member, LocalDate day, MerchantPlan plan, String month) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM cy_merchant_visit_charge WHERE merchant_id = ? AND member_id = ?"
                + " AND visit_day = ?", Integer.class, merchant, member, Date.valueOf(day));
        if (exists != null && exists > 0) return false;
        try {
            return jdbc.update("INSERT INTO cy_merchant_visit_charge (merchant_id, member_id, visit_day, plan_code, fee_fen,"
                    + " invoice_month, create_time) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    merchant, member, Date.valueOf(day), plan.code(), plan.visitFeeFen(), month) == 1;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }

    private boolean invoiced(long merchant, String month) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM cy_merchant_visit_invoice WHERE merchant_id = ? AND invoice_month = ?",
                Integer.class, merchant, month);
        return n != null && n > 0;
    }

    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
