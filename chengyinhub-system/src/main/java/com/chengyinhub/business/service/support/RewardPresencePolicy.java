package com.chengyinhub.business.service.support;

import java.util.function.Function;

import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.utils.GeoDistanceUtils;

/**
 * GAME-2 "progress is free, prizes need presence" (owner 2026-11-16, decision 2).
 *
 * <p>After the start a stop never refuses completion (owner 2026-11-13). Only the reward set depends on
 * evidence: points, merchant coupons, drops and podium medals need a fix within the reward radius of the stop;
 * without it the stop completes with {@link RewardPolicy#XP_BADGE_ONLY}.
 *
 * <p>Switches ({@code sys_config}, read on every call, a missing or malformed value falls back to the safe default):
 * <ul>
 *   <li>{@value #KEY_REMOTE_POLICY}: {@code XP_BADGE_ONLY} (default, safe) or {@code FULL} (pre-2026-11-16 behaviour);</li>
 *   <li>{@value #KEY_RADIUS}: reward radius in metres, default {@value #DEFAULT_RADIUS_METERS}, clamped 30–1000.
 *       The frozen station radius wins when it is larger.</li>
 * </ul>
 */
public final class RewardPresencePolicy {

    public static final String KEY_REMOTE_POLICY = "play.arrive.remote_reward_policy";
    public static final String KEY_RADIUS = "play.arrive.reward_radius_m";
    public static final double DEFAULT_RADIUS_METERS = 80d;
    /** Accuracy slack added to the radius is capped so a coarse fix cannot widen the circle much. */
    public static final double MAX_ACCURACY_SLACK_METERS = 50d;
    private static final double MIN_RADIUS = 30d;
    private static final double MAX_RADIUS = 1000d;
    /** Value written on the progress row and returned as {@code rewardCeilingReason}. */
    public static final String REASON_REMOTE = "REMOTE";

    private final RewardPolicy remotePolicy;
    private final double configuredRadius;

    private RewardPresencePolicy(RewardPolicy remotePolicy, double configuredRadius) {
        this.remotePolicy = remotePolicy;
        this.configuredRadius = configuredRadius;
    }

    public static RewardPresencePolicy defaults() {
        return new RewardPresencePolicy(RewardPolicy.XP_BADGE_ONLY, DEFAULT_RADIUS_METERS);
    }

    /** @param lookup returns the raw sys_config value for a key, or null */
    public static RewardPresencePolicy read(Function<String, String> lookup) {
        if (lookup == null) return defaults();
        RewardPolicy policy = RewardPolicy.XP_BADGE_ONLY;
        String rawPolicy = safe(lookup, KEY_REMOTE_POLICY);
        if (rawPolicy != null && "FULL".equalsIgnoreCase(rawPolicy)) policy = RewardPolicy.FULL;
        double radius = DEFAULT_RADIUS_METERS;
        String rawRadius = safe(lookup, KEY_RADIUS);
        if (rawRadius != null) {
            try {
                double parsed = Double.parseDouble(rawRadius);
                if (Double.isFinite(parsed)) radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, parsed));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        return new RewardPresencePolicy(policy, radius);
    }

    private static String safe(Function<String, String> lookup, String key) {
        try {
            String v = lookup.apply(key);
            return v == null || v.isBlank() ? null : v.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when the switch says remote completions still pay in full (today's behaviour). */
    public boolean remotePaysFull() {
        return remotePolicy == RewardPolicy.FULL;
    }

    /** Reward radius for a stop: the larger of the configured radius and the frozen station radius. */
    public double rewardRadiusMeters(ArrivalPolicy station) {
        double stationRadius = station == null ? 0d : station.radiusMeters();
        return Math.max(configuredRadius, Double.isFinite(stationRadius) ? stationRadius : 0d);
    }

    /**
     * Whether a fix counts as "at the stop" for prizes. Any missing or invalid input is "not present".
     * A missing accuracy adds no slack; a reported one adds at most {@value #MAX_ACCURACY_SLACK_METERS} m.
     */
    public static boolean within(Double playerLat, Double playerLng, Double accuracy,
            Double stopLat, Double stopLng, double radiusMeters) {
        if (playerLat == null || playerLng == null || stopLat == null || stopLng == null) return false;
        double dist = GeoDistanceUtils.haversineMeters(playerLat, playerLng, stopLat, stopLng);
        if (!Double.isFinite(dist)) return false;
        double slack = accuracy == null || !Double.isFinite(accuracy) || accuracy < 0d
                ? 0d : Math.min(accuracy, MAX_ACCURACY_SLACK_METERS);
        return dist <= radiusMeters + slack;
    }

    /** The reward set for a completion, given whether presence was proven. */
    public RewardPolicy policyFor(boolean present) {
        return present || remotePaysFull() ? RewardPolicy.FULL : RewardPolicy.XP_BADGE_ONLY;
    }
}
