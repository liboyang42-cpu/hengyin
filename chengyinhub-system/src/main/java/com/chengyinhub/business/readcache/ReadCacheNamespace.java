package com.chengyinhub.business.readcache;

import java.util.Locale;
import java.util.Map;

/**
 * What the read cache holds. Only hot, shared, non-personal reads: nothing per member, nothing about money.
 * Each namespace has a version in Redis; any write to one of its source tables moves the version on
 * (see {@link ReadCacheInvalidationInterceptor}), so every older entry stops being read at once.
 */
public final class ReadCacheNamespace {
    /** Published roam places per map tile (the shared columns only; found / pending are added per member). */
    public static final String POI = "poi";
    /** The shared part of POST /api/config/features (sys_config flags and texts). */
    public static final String FEATURES = "features";
    /** POST /api/common/banner. */
    public static final String BANNER = "banner";
    /** POST /api/category/list without a keyword. */
    public static final String CATEGORY = "category";

    /** Source table -> namespace. A write to the table invalidates the namespace. */
    static final Map<String, String> TABLES = Map.of(
            "roam_poi", POI,
            "npc_profile", POI,
            "sys_config", FEATURES,
            "sys_banner", BANNER,
            "sys_category", CATEGORY);

    private ReadCacheNamespace() {
    }

    /** Namespace whose data lives in {@code table}, or null. */
    static String forTable(String table) {
        if (table == null) return null;
        String t = table.toLowerCase(Locale.ROOT);
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        return TABLES.get(t);
    }
}
