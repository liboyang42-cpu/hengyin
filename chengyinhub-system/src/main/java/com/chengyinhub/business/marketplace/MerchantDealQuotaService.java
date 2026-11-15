package com.chengyinhub.business.marketplace;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;
import com.chengyinhub.business.merchantplans.MerchantPlan;
import com.chengyinhub.business.merchantplans.MerchantPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T5 / S3a plan feature {@code dealsOffersPerMonth}: how many of its own coupons a shop may put forward for the
 * players' deals layer each month.
 *
 * <p>Today (and on Free, whose quota is 0) only an operator adds coupons to the deals layer; a shop cannot. A paid
 * plan lets the shop submit up to its quota per calendar month (Asia/Shanghai). A submission is stored paused
 * ({@code enabled = 0}, {@code create_by = merchant:<id>}): an operator still switches it on in 城市集市, so the deals
 * layer stays curated. The operator path ({@link MarketplaceAdminService}) is unchanged and has no quota.</p>
 */
@Service
public class MerchantDealQuotaService {
    private static final Logger log = LoggerFactory.getLogger(MerchantDealQuotaService.class);
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final String SUBMITTER_PREFIX = "merchant:";

    private final JdbcTemplate jdbc;
    private final IMerchantAccessService access;
    @Autowired(required = false)
    private MerchantPlanService plans;

    public MerchantDealQuotaService(JdbcTemplate jdbc, IMerchantAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    void plans(MerchantPlanService plans) {
        this.plans = plans;
    }

    /** The plan's monthly quota; Free, a missing plan or an unreadable plan = 0 (today: no self-submission). */
    int quota(long merchantId) {
        try {
            Object v = plans == null ? null : plans.feature(merchantId, MerchantPlan.F_DEALS_OFFERS_PER_MONTH);
            return v instanceof Number n ? Math.max(0, n.intValue()) : 0;
        } catch (Exception e) {
            log.warn("shop {} plan unreadable; no deal submissions (Free)", merchantId);
            return 0;
        }
    }

    static Timestamp monthStart(Instant now) {
        LocalDate first = now.atZone(ZONE).toLocalDate().withDayOfMonth(1);
        return Timestamp.from(first.atStartOfDay(ZONE).toInstant());
    }

    int usedThisMonth(long merchantId, Instant now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM marketplace_deal d JOIN sms_coupon c ON c.id = d.coupon_id"
                + " WHERE c.merchant_id = ? AND d.create_by = ? AND d.create_time >= ?", Integer.class,
                merchantId, SUBMITTER_PREFIX + merchantId, monthStart(now));
        return n == null ? 0 : n;
    }

    /** The shop's quota and use this month. */
    public Map<String, Object> view(Long principal) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.MARKETING_READ);
        long merchant = ctx.getMerchantId();
        return summary(merchant, quota(merchant), Instant.now());
    }

    /** Puts one of the shop's own coupons forward for the deals layer, within this month's quota. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.COUPON_MANAGE);
        long merchant = ctx.getMerchantId();
        long couponId = couponId(body);
        int quota = quota(merchant);
        if (quota <= 0) {
            throw new MerchantGapException(403, "PLAN_FEATURE_NOT_INCLUDED", "当前套餐不含自助提交优惠");
        }
        // Serialise submissions of one shop so two at once cannot both take the last place.
        jdbc.queryForList("SELECT id FROM mms_merchant WHERE id = ? FOR UPDATE", merchant);
        Integer own = jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon WHERE id = ? AND merchant_id = ?"
                + " AND del_flag = 0 AND scope_type = 1", Integer.class, couponId, merchant);
        if (own == null || own == 0) throw new MerchantGapException(404, "COUPON_NOT_FOUND", "没有找到这张优惠券");
        Integer listed = jdbc.queryForObject("SELECT COUNT(*) FROM marketplace_deal WHERE coupon_id = ?",
                Integer.class, couponId);
        if (listed != null && listed > 0) throw new MerchantGapException(409, "DEAL_ALREADY_SUBMITTED", "这张优惠券已提交过");
        Instant now = Instant.now();
        if (usedThisMonth(merchant, now) >= quota) {
            throw new MerchantGapException(409, "DEAL_QUOTA_USED", "本月可提交的优惠已用完");
        }
        String by = SUBMITTER_PREFIX + merchant;
        jdbc.update("INSERT INTO marketplace_deal (coupon_id, enabled, sort, create_by, create_time, update_by, update_time)"
                + " VALUES (?, 0, 0, ?, NOW(), ?, NOW())", couponId, by, by);
        Map<String, Object> out = summary(merchant, quota, now);
        out.put("couponId", couponId);
        out.put("state", "WAITING_FOR_REVIEW");
        return out;
    }

    private Map<String, Object> summary(long merchant, int quota, Instant now) {
        int used = quota > 0 ? usedThisMonth(merchant, now) : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", merchant);
        out.put("month", now.atZone(ZONE).toLocalDate().withDayOfMonth(1).toString().substring(0, 7));
        out.put("quota", quota);
        out.put("used", used);
        out.put("remaining", Math.max(0, quota - used));
        return out;
    }

    static long couponId(Map<String, Object> body) {
        if (body == null || body.size() != 1 || !(body.get("couponId") instanceof Number n)
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() <= 0) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        }
        return n.longValue();
    }
}
