package com.chengyinhub.business.discovery;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * K2 discovery: reason and order for the "near you" list.
 *
 * <p>Switch off (default, {@code chengyin.discovery.scoring.enabled=false}): nearest first, exactly like the roam pins
 * today. Switch on: score = 0.5 x closeness + 0.2 x interest share for the item's kind (member_interest_profile, the live
 * profile fed by player events) + 0.2 x your club + 0.1 x new. Ties fall back to the distance order.
 *
 * <p>The inputs are distance, kind, age, club membership and the player's own interest profile. None of them can be
 * bought: there is no input for promotion slots, campaign budgets or operator sort.
 */
public final class DiscoveryScorer {
    static final int NEW_WINDOW_DAYS = 14;
    private static final long NEW_WINDOW_MILLIS = NEW_WINDOW_DAYS * 24L * 60L * 60L * 1000L;
    private static final double W_CLOSE = 0.5;
    private static final double W_INTEREST = 0.2;
    private static final double W_CLUB = 0.2;
    private static final double W_NEW = 0.1;

    private DiscoveryScorer() {}

    /** The plain reason: your club, else new, else near you. */
    static DiscoveryReason reason(DiscoveryItem item, Date now) {
        if (item.yourClub) return DiscoveryReason.YOUR_CLUB;
        if (isNew(item, now)) return DiscoveryReason.NEW;
        return DiscoveryReason.NEAR_YOU;
    }

    static boolean isNew(DiscoveryItem item, Date now) {
        return item.createdAt != null && now != null
                && now.getTime() - item.createdAt.getTime() <= NEW_WINDOW_MILLIS
                && item.createdAt.getTime() <= now.getTime();
    }

    /** Today's order: nearest first; ties by kind, then id. Stable for the same input. */
    static final Comparator<DiscoveryItem> DISTANCE_ORDER = Comparator
            .comparingDouble((DiscoveryItem i) -> i.distanceM)
            .thenComparing(i -> i.kind)
            .thenComparingLong(i -> i.id);

    /** Orders in place. {@code scoring=false} keeps today's distance order. */
    static void order(List<DiscoveryItem> items, boolean scoring, double reachM, String interestJson, Date now) {
        if (!scoring) {
            items.sort(DISTANCE_ORDER);
            return;
        }
        double reach = reachM > 0 ? reachM : 1;
        for (DiscoveryItem item : items) {
            double close = 1 - Math.min(item.distanceM, reach) / reach;
            item.score = W_CLOSE * close
                    + W_INTEREST * interestShare(interestJson, interestKey(item.kind))
                    + (item.yourClub ? W_CLUB : 0)
                    + (isNew(item, now) ? W_NEW : 0);
        }
        items.sort(Comparator.comparingDouble((DiscoveryItem i) -> -i.score).thenComparing(DISTANCE_ORDER));
    }

    /** member_interest_profile keys come from event bizType: roam (places), activity (events), coupon (offers). */
    static String interestKey(DiscoveryItem.Kind kind) {
        switch (kind) {
            case PLACE: return "roam";
            case EVENT: return "activity";
            default: return "coupon";
        }
    }

    /** Share of the player's positive interest weight on one key, 0..1. Bad JSON counts as no interest. */
    static double interestShare(String json, String key) {
        if (json == null || json.isEmpty()) return 0;
        JSONObject weights;
        try {
            weights = JSON.parseObject(json);
        } catch (RuntimeException malformed) {
            return 0;
        }
        if (weights == null) return 0;
        long total = 0;
        long mine = 0;
        for (Map.Entry<String, Object> e : weights.entrySet()) {
            long v = e.getValue() instanceof Number ? ((Number) e.getValue()).longValue() : 0;
            if (v <= 0) continue;
            total += v;
            if (key.equals(e.getKey())) mine = v;
        }
        return total <= 0 ? 0 : (double) mine / total;
    }

    /** Equirectangular distance in metres; fine at city scale (the box is at most 0.5 degrees). */
    static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double meanLat = Math.toRadians((lat1 + lat2) / 2);
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1) * Math.cos(meanLat);
        return 6_371_000d * Math.sqrt(dLat * dLat + dLng * dLng);
    }
}
