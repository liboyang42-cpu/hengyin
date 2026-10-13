package com.chengyinhub.business.support;

import com.alibaba.fastjson2.JSON;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ended roam geometry expires after 30 days; aggregate XP/distance/badges remain. */
public final class RoamLocationRetention {
    public static final long THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final Set<String> GEOMETRY = Set.of("lat", "lng", "latitude", "longitude", "lastLat", "lastLng",
            "lastReportAt", "track", "polyline", "coordinates", "center", "pois", "foundPois", "allTiles", "pendingTiles");
    private RoamLocationRetention() {}
    public static String claimFingerprint(String kind, Long memberId, Object value) {
        com.chengyinhub.business.security.PiiCryptoService crypto = com.chengyinhub.business.security.PiiCryptoService.getInstance();
        if (crypto == null || memberId == null || memberId <= 0 || value == null)
            throw new IllegalStateException("Roam claim fingerprint configuration unavailable");
        String normalized = "tile".equals(kind) ? String.valueOf(value).toLowerCase(java.util.Locale.ROOT) : String.valueOf(value);
        return crypto.blindIndex("roam-claim-v1:" + kind + ":" + memberId + ":" + normalized);
    }
    public static String keyFingerprint() {
        com.chengyinhub.business.security.PiiCryptoService crypto = com.chengyinhub.business.security.PiiCryptoService.getInstance();
        if (crypto == null) throw new IllegalStateException("Roam claim fingerprint configuration unavailable");
        return crypto.blindIndex("roam-claim-key-sentinel-v1");
    }
    public static boolean expired(Date finishedAt, Date now) {
        return finishedAt != null && now != null && finishedAt.getTime() <= now.getTime() - THIRTY_DAYS_MILLIS;
    }
    public static String stripJson(String json) {
        if (json == null) return null;
        Object value = JSON.parse(json);
        strip(value);
        return JSON.toJSONString(value);
    }
    @SuppressWarnings("unchecked")
    public static void strip(Object value) {
        if (value instanceof Map) {
            Map<String,Object> map = (Map<String,Object>)value;
            map.keySet().removeIf(GEOMETRY::contains);
            for (Object child : map.values()) strip(child);
        } else if (value instanceof List) {
            for (Object child : (List<?>)value) strip(child);
        }
    }
}
