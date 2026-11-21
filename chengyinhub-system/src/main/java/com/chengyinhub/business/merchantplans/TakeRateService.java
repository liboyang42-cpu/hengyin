package com.chengyinhub.business.merchantplans;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;

/**
 * S3a MON-04: the platform take as a setting per product type, edited in the admin console and audited.
 *
 * <p><b>Today's values are the defaults.</b> The table {@code cy_take_rate} starts empty, and a product without a row
 * keeps exactly the value the code uses today: the generic {@code settlement_rule} row for tickets, each theme's
 * frozen rate for bundles, and 100% minus {@code chengyin.creator-earnings.sale-share-bps} for templates. A read
 * error also falls back to today's value. Nothing changes until someone edits a row.
 *
 * <p>The existing code keeps its method signatures: {@code ISettlementRuleService#getActiveRule} passes generic
 * rows through {@link #applyTo(SettlementRule)}, so {@code IPricingService#platformRate()}, the registration fee
 * snapshot, activity settlement and every other reader of the generic rule pick a configured take up unchanged.
 * Per-merchant rules (negotiated contracts) are never touched. A configured take moves the difference into the
 * merchant share so the shares keep their sum, and a take that would make the merchant share negative is refused.
 *
 * <p>Snapshots stay snapshots: themes and registrations that already froze a rate keep it.
 */
@Service
public class TakeRateService {
    private static final Logger log = LoggerFactory.getLogger(TakeRateService.class);
    public static final int BPS_MAX = 10000;
    public static final int REASON_MAX = 255;
    static final long CACHE_MILLIS = 10_000L;

    private final JdbcTemplate jdbc;
    private final SettlementRuleMapper rules;
    private final OpsConsoleAudit audit;
    private final int templateCreatorShareBps;

    private volatile Map<String, Integer> cache;
    private volatile long cachedAt;

    public TakeRateService(JdbcTemplate jdbc, SettlementRuleMapper rules, OpsConsoleAudit audit,
                           @Value("${chengyin.creator-earnings.sale-share-bps:7000}") int templateCreatorShareBps) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.audit = audit;
        this.templateCreatorShareBps = templateCreatorShareBps;
    }

    // ---------------------------------------------------------------- reads used by the money code

    /** The configured take for a product and source (source row first, then the product-wide row), or null. */
    public Integer configuredBps(TakeProduct product, String source) {
        if (product == null) return null;
        Map<String, Integer> rows = rows();
        String s = source == null ? TakeProduct.ANY_SOURCE : source;
        Integer exact = rows.get(key(product, s));
        if (exact != null || s.isEmpty()) return exact;
        return rows.get(key(product, TakeProduct.ANY_SOURCE));
    }

    /** The configured take as a rate (scale 4), or {@code today} when nothing is configured. */
    public BigDecimal rateOr(TakeProduct product, String source, BigDecimal today) {
        Integer bps = configuredBps(product, source);
        return bps == null ? today : toRate(bps);
    }

    /**
     * Applies a configured take to a generic settlement rule (merchant 0, biz_type topic / topic_club / activity).
     * Any other rule, or a product with no configured take, is returned unchanged. Idempotent on the same object.
     */
    public SettlementRule applyTo(SettlementRule rule) {
        if (rule == null || rule.getMerchantId() == null || rule.getMerchantId() != 0L) return rule;
        TakeProduct.Mapped mapped = TakeProduct.ofSettlementBizType(rule.getBizType());
        if (mapped == null) return rule;
        Integer bps = configuredBps(mapped.product(), mapped.source());
        BigDecimal current = rule.getPlatformCommissionRate();
        if (bps == null || current == null || rule.getMerchantRate() == null) return rule;
        BigDecimal target = toRate(bps);
        if (target.compareTo(current) == 0) return rule;
        BigDecimal merchant = rule.getMerchantRate().add(current).subtract(target);
        if (merchant.signum() < 0) {
            log.error("configured take {} bps for {} would make the merchant share negative; keeping rule {} as stored",
                    bps, rule.getBizType(), rule.getId());
            return rule;
        }
        rule.setPlatformCommissionRate(target);
        rule.setMerchantRate(merchant);
        return rule;
    }

    /** Bundle line rate: the configured bundle take, or the theme's frozen rate (today's behaviour). */
    public BigDecimal bundleRate(BigDecimal themeFrozenRate) {
        return rateOr(TakeProduct.BUNDLE, TakeProduct.ANY_SOURCE, themeFrozenRate);
    }

    /** Creator share of a template sale in bps: 100% minus the configured template take, or the given default. */
    public int templateCreatorShareBps(int configuredShareBps) {
        Integer take = configuredBps(TakeProduct.TEMPLATE, TakeProduct.ANY_SOURCE);
        return take == null ? configuredShareBps : BPS_MAX - take;
    }

    /**
     * K1 club dues take as a rate (scale 4): the configured {@code club_dues} take, else today's club ticket take (the
     * stored {@code topic_club} rule, else {@code topic}). Null when neither can be read: the caller then sends the
     * share order to manual review instead of guessing.
     */
    public BigDecimal clubDuesRate() {
        Integer bps = configuredBps(TakeProduct.CLUB_DUES, TakeProduct.ANY_SOURCE);
        if (bps != null) return toRate(bps);
        SettlementRule rule = todayRule(TakeProduct.CLUB_DUES, TakeProduct.ANY_SOURCE);
        return rule == null ? null : rule.getPlatformCommissionRate();
    }

    // ---------------------------------------------------------------- admin console

    /** One row per product and source: today's value, the configured value (or null) and the value in force. */
    public List<Map<String, Object>> view() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> rows = rows();
        for (TakeProduct p : TakeProduct.values()) {
            List<String> sources = new ArrayList<>();
            sources.add(TakeProduct.ANY_SOURCE);
            sources.addAll(p.sources());
            for (String s : sources) {
                Integer today = todayBps(p, s);
                Integer configured = rows.get(key(p, s));
                Integer inForce = configuredBps(p, s);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("product", p.key());
                row.put("source", s);
                row.put("todayBps", today);
                row.put("todayBasis", basis(p, s));
                row.put("configuredBps", configured);
                row.put("effectiveBps", inForce != null ? inForce : today);
                out.add(row);
            }
        }
        return out;
    }

    /**
     * Sets (bps 0..10000) or clears (null, back to today's value) one take. Writes one audit row in the same
     * transaction. Returns false when nothing changed.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean set(String productKey, String sourceRaw, Integer bps, Long sysUserId, String operator, String reason) {
        TakeProduct product = TakeProduct.fromKey(productKey);
        if (product == null) throw new IllegalArgumentException("unknown product type");
        String source = product.normaliseSource(sourceRaw);
        if (source == null) throw new IllegalArgumentException("unknown source for " + product.key());
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("operator is required");
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new IllegalArgumentException("reason is required");
        if (why.length() > REASON_MAX) why = why.substring(0, REASON_MAX);
        if (bps != null && (bps < 0 || bps > BPS_MAX)) throw new IllegalArgumentException("take must be 0..10000 bps");
        if (bps != null) requireAffordable(product, source, bps);

        List<Integer> before = jdbc.queryForList(
                "SELECT rate_bps FROM cy_take_rate WHERE product_type = ? AND source = ? FOR UPDATE",
                Integer.class, product.key(), source);
        Integer old = before.isEmpty() ? null : before.get(0);
        if (java.util.Objects.equals(old, bps)) return false;
        if (bps == null) {
            jdbc.update("DELETE FROM cy_take_rate WHERE product_type = ? AND source = ?", product.key(), source);
        } else if (old == null) {
            jdbc.update("INSERT INTO cy_take_rate (product_type, source, rate_bps, update_by, update_time)"
                    + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)", product.key(), source, bps, operator);
        } else {
            jdbc.update("UPDATE cy_take_rate SET rate_bps = ?, update_by = ?, update_time = CURRENT_TIMESTAMP"
                    + " WHERE product_type = ? AND source = ?", bps, operator, product.key(), source);
        }
        audit.record(sysUserId, "TAKE_RATE_SET", "TAKE_RATE", 0L,
                snapshot(product, source, old), snapshot(product, source, bps), why);
        cache = null;
        log.warn("take rate {}/{} changed {} -> {} bps by {}", product.key(), source, old, bps, operator);
        return true;
    }

    // ---------------------------------------------------------------- helpers

    /** Today's value in bps, or null when it is not a single number (bundles follow each theme's frozen rate). */
    Integer todayBps(TakeProduct p, String source) {
        if (p == TakeProduct.TEMPLATE) return BPS_MAX - templateCreatorShareBps;
        SettlementRule rule = todayRule(p, source);
        if (rule == null || rule.getPlatformCommissionRate() == null) return null;
        return rule.getPlatformCommissionRate().movePointRight(4).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static String basis(TakeProduct p, String source) {
        if (p == TakeProduct.TEMPLATE) return "CREATOR_SHARE_CONFIG";
        if (p == TakeProduct.BUNDLE) return "THEME_FROZEN_RATE";
        if (TakeProduct.settlementBizTypeOf(p, source) == null) return "EACH_SOURCE";
        return "SETTLEMENT_RULE";
    }

    /** The stored generic rule (never with an override applied). Club themes fall back to the theme rule. */
    private SettlementRule todayRule(TakeProduct p, String source) {
        String bizType = TakeProduct.settlementBizTypeOf(p, source);
        if (bizType == null) return null;
        try {
            SettlementRule rule = rules.selectActiveRule(0L, bizType);
            if (rule == null && "topic_club".equals(bizType)) rule = rules.selectActiveRule(0L, "topic");
            return rule;
        } catch (RuntimeException e) {
            log.error("settlement rule read failed for {}", bizType, e);
            return null;
        }
    }

    /** A ticket take may not exceed platform + merchant share of the stored rule, or the merchant share goes negative. */
    private void requireAffordable(TakeProduct p, String source, int bps) {
        List<String> sources = new ArrayList<>();
        if (source.isEmpty() && !p.sources().isEmpty()) sources.addAll(p.sources());
        else sources.add(source);
        for (String s : sources) {
            if (TakeProduct.settlementBizTypeOf(p, s) == null) continue;
            SettlementRule rule = todayRule(p, s);
            if (rule == null || rule.getPlatformCommissionRate() == null || rule.getMerchantRate() == null) {
                throw new IllegalStateException("no generic settlement rule for " + p.key() + "/" + s);
            }
            BigDecimal room = rule.getPlatformCommissionRate().add(rule.getMerchantRate());
            if (toRate(bps).compareTo(room) > 0) {
                throw new IllegalArgumentException("take " + bps + " bps is above platform + merchant share ("
                        + room.movePointRight(4).intValue() + " bps) for " + p.key() + "/" + s);
            }
        }
    }

    private Map<String, Object> snapshot(TakeProduct p, String source, Integer bps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", p.key());
        m.put("source", source);
        m.put("rateBps", bps);
        return m;
    }

    public static BigDecimal toRate(int bps) {
        return BigDecimal.valueOf(bps).movePointLeft(4).setScale(4, RoundingMode.UNNECESSARY);
    }

    private static String key(TakeProduct p, String source) {
        return p.key() + "|" + source;
    }

    /** All configured rows, cached briefly. A read error means "nothing configured" (today's values). */
    private Map<String, Integer> rows() {
        Map<String, Integer> snapshot = cache;
        long now = System.currentTimeMillis();
        if (snapshot != null && now - cachedAt < CACHE_MILLIS) return snapshot;
        Map<String, Integer> fresh = new HashMap<>();
        try {
            for (Map<String, Object> row : jdbc.queryForList("SELECT product_type, source, rate_bps FROM cy_take_rate")) {
                TakeProduct p = TakeProduct.fromKey(String.valueOf(row.get("product_type")));
                Object source = row.get("source");
                String s = p == null ? null : p.normaliseSource(source == null ? "" : source.toString());
                if (s != null && row.get("rate_bps") instanceof Number n && n.intValue() >= 0 && n.intValue() <= BPS_MAX) {
                    fresh.put(key(p, s), n.intValue());
                }
            }
        } catch (RuntimeException e) {
            log.error("take rate read failed; using today's values", e);
            return Map.of();
        }
        cache = fresh;
        cachedAt = now;
        return fresh;
    }
}
