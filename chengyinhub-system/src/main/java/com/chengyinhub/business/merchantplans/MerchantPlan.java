package com.chengyinhub.business.merchantplans;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3a MON-07 / MON-15: one configured merchant plan. Prices are in fen; {@code visitFeeFen > 0} makes it a
 * per-visit plan (a ledger entry per staff-verified visit, invoiced monthly).
 */
public record MerchantPlan(String code, String nameZh, String nameEn, int monthlyPriceFen, Integer visitFeeFen,
                           Map<String, Object> features, boolean offered, int sortOrder) {

    public static final String FREE = "FREE";
    public static final String BASIC = "BASIC";
    public static final String PRO = "PRO";
    public static final String PER_VISIT = "PER_VISIT";
    public static final List<String> CODES = List.of(FREE, BASIC, PRO, PER_VISIT);

    /** Feature flags and their kinds. Free carries today's features (all extras off). */
    public static final String F_WEEKLY_PROOF_DETAIL = "weeklyProofDetail";
    public static final String F_EXTRA_NODE_GAMES = "extraNodeGames";
    public static final String F_DEALS_OFFERS_PER_MONTH = "dealsOffersPerMonth";
    public static final List<String> BOOLEAN_FEATURES = List.of(F_WEEKLY_PROOF_DETAIL);
    public static final List<String> COUNT_FEATURES = List.of(F_EXTRA_NODE_GAMES, F_DEALS_OFFERS_PER_MONTH);
    public static final int COUNT_MAX = 100;

    /** Free's features: exactly what every merchant has today. */
    public static Map<String, Object> todaysFeatures() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(F_WEEKLY_PROOF_DETAIL, false);
        m.put(F_EXTRA_NODE_GAMES, 0);
        m.put(F_DEALS_OFFERS_PER_MONTH, 0);
        return m;
    }

    public boolean paid() { return monthlyPriceFen > 0; }

    public boolean perVisit() { return visitFeeFen != null && visitFeeFen > 0; }

    /** A feature value, with Free's value for anything missing. */
    public Object feature(String key) {
        Object v = features == null ? null : features.get(key);
        return v != null ? v : todaysFeatures().get(key);
    }

    /**
     * Validates and normalises a feature map: only the known keys, booleans for flags, 0..100 for counts.
     * Missing keys take Free's value.
     */
    public static Map<String, Object> normaliseFeatures(Map<String, Object> raw) {
        Map<String, Object> out = todaysFeatures();
        if (raw == null) return out;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (BOOLEAN_FEATURES.contains(k)) {
                if (!(v instanceof Boolean b)) throw new IllegalArgumentException(k + " must be true or false");
                out.put(k, b);
            } else if (COUNT_FEATURES.contains(k)) {
                if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                        || n.intValue() < 0 || n.intValue() > COUNT_MAX) {
                    throw new IllegalArgumentException(k + " must be a whole number 0.." + COUNT_MAX);
                }
                out.put(k, n.intValue());
            } else {
                throw new IllegalArgumentException("unknown feature " + k);
            }
        }
        return out;
    }

    /** The wire shape for the app, the mini-program and the console. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("nameZh", nameZh);
        m.put("nameEn", nameEn);
        m.put("monthlyPriceFen", monthlyPriceFen);
        m.put("visitFeeFen", visitFeeFen);
        m.put("perVisit", perVisit());
        Map<String, Object> f = new LinkedHashMap<>();
        for (String k : todaysFeatures().keySet()) f.put(k, feature(k));
        m.put("features", f);
        m.put("offered", offered);
        m.put("sortOrder", sortOrder);
        return m;
    }
}
