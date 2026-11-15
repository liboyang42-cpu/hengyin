package com.chengyinhub.business.locationtrust;

import java.util.Locale;
import java.util.function.Function;

/**
 * Location-trust thresholds. Read from {@code sys_config} keys {@code location.trust.*} (see
 * {@code migration_20261115_location_trust.sql}); a missing or malformed value falls back to the default
 * below, and a value outside its sane range is clamped to that range.
 *
 * <p>Defaults only stop clearly impossible input (owner rule for this package): faster than 15 m/s on foot,
 * a fix time in the future, accuracy worse than 200 m for a point reward, a fix the phone itself marks as
 * simulated. Everything else is written to the review trail and otherwise accepted.
 */
public record LocationTrustConfig(
        Mode mode,
        double footSpeedMps,
        long footWindowSeconds,
        double vehicleSpeedMps,
        double minMoveMeters,
        long speedBaselineWindowSeconds,
        double pointMaxAccuracyMeters,
        long maxFutureSkewSeconds,
        long staleFixSeconds,
        LocationTrustAction staleAction,
        LocationTrustAction simulatedAction,
        LocationTrustAction accessoryAction,
        int identicalFixRepeat,
        LocationTrustAction identicalAction,
        double revealSpeedMps,
        double revealTileEdgeMeters,
        double revealTrackRadiusMeters,
        int revealWindowMinutes,
        int revealWindowBaseTiles,
        boolean journeyRequireFixAfterStart,
        int claimPhotoMaxAgeMinutes,
        double claimRadiusMeters,
        int claimFixWindowMinutes,
        int claimPhashRejectHamming,
        int claimPhashReviewHamming,
        int claimDuplicateWindowDays) {

    public static final String PREFIX = "location.trust.";

    /** ENFORCE applies decisions; SHADOW scores and logs but always lets the write through; OFF skips scoring. */
    public enum Mode { ENFORCE, SHADOW, OFF }

    public static LocationTrustConfig defaults() {
        return read(key -> null);
    }

    /** @param lookup returns the raw sys_config value for a full key, or null */
    public static LocationTrustConfig read(Function<String, String> lookup) {
        Reader r = new Reader(lookup);
        return new LocationTrustConfig(
                r.mode("mode", Mode.ENFORCE),
                r.dbl("footSpeedMps", 15d, 5d, 100d),
                r.lng("footWindowSeconds", 300L, 30L, 3600L),
                r.dbl("vehicleSpeedMps", 35d, 15d, 300d),
                r.dbl("minMoveMeters", 300d, 50d, 5000d),
                r.lng("speedBaselineWindowSeconds", 7200L, 300L, 86400L),
                r.dbl("pointMaxAccuracyMeters", 200d, 50d, 5000d),
                r.lng("maxFutureSkewSeconds", 120L, 30L, 3600L),
                r.lng("staleFixSeconds", 1800L, 60L, 86400L),
                r.action("staleAction", LocationTrustAction.FLAG),
                r.action("simulatedAction", LocationTrustAction.NO_REWARD),
                r.action("accessoryAction", LocationTrustAction.FLAG),
                (int) r.lng("identicalFixRepeat", 6L, 3L, 100L),
                r.action("identicalAction", LocationTrustAction.FLAG),
                r.dbl("revealSpeedMps", 15d, 5d, 100d),
                r.dbl("revealTileEdgeMeters", 100d, 50d, 500d),
                r.dbl("revealTrackRadiusMeters", 600d, 200d, 5000d),
                (int) r.lng("revealWindowMinutes", 10L, 1L, 120L),
                (int) r.lng("revealWindowBaseTiles", 300L, 50L, 5000L),
                r.bool("journeyRequireFixAfterStart", false),
                (int) r.lng("claim.photoMaxAgeMinutes", 15L, 1L, 1440L),
                r.dbl("claim.radiusMeters", 300d, 50d, 5000d),
                (int) r.lng("claim.fixWindowMinutes", 15L, 1L, 1440L),
                (int) r.lng("claim.phashRejectHamming", 3L, 0L, 16L),
                (int) r.lng("claim.phashReviewHamming", 8L, 0L, 24L),
                (int) r.lng("claim.duplicateWindowDays", 365L, 1L, 3650L));
    }

    public boolean scoring() {
        return mode != Mode.OFF;
    }

    public boolean enforcing() {
        return mode == Mode.ENFORCE;
    }

    private static final class Reader {
        private final Function<String, String> lookup;

        Reader(Function<String, String> lookup) {
            this.lookup = lookup;
        }

        private String raw(String key) {
            try {
                String v = lookup.apply(PREFIX + key);
                return v == null || v.isBlank() ? null : v.trim();
            } catch (RuntimeException e) {
                return null;
            }
        }

        double dbl(String key, double def, double min, double max) {
            String v = raw(key);
            if (v == null) return def;
            try {
                double d = Double.parseDouble(v);
                if (!Double.isFinite(d)) return def;
                return Math.max(min, Math.min(max, d));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        long lng(String key, long def, long min, long max) {
            String v = raw(key);
            if (v == null) return def;
            try {
                return Math.max(min, Math.min(max, Long.parseLong(v)));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        boolean bool(String key, boolean def) {
            String v = raw(key);
            if (v == null) return def;
            return "true".equalsIgnoreCase(v) || "1".equals(v);
        }

        LocationTrustAction action(String key, LocationTrustAction def) {
            return LocationTrustAction.parse(raw(key), def);
        }

        Mode mode(String key, Mode def) {
            String v = raw(key);
            if (v == null) return def;
            try {
                return Mode.valueOf(v.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return def;
            }
        }
    }
}
