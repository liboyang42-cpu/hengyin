package com.chengyinhub.business.locationtrust;

import com.chengyinhub.common.utils.GeoDistanceUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure scoring rules for one location write (no I/O, unit-tested in {@code LocationTrustPolicyTest}).
 *
 * <p>Rules, in the order they are checked. Each one adds a reason code; the decision is the most severe action
 * among the reasons that fired.
 * <ol>
 *   <li>{@code INVALID_FIX} / {@code INVALID_ACCURACY}: non-finite or out-of-range numbers. REJECT.</li>
 *   <li>{@code ACCURACY_POOR}: accuracy worse than {@code pointMaxAccuracyMeters} on a point reward. REJECT
 *       (retryable: step outside and try again). Other kinds only flag it.</li>
 *   <li>{@code FUTURE_FIX}: device fix time more than {@code maxFutureSkewSeconds} ahead of the server. REJECT.</li>
 *   <li>{@code STALE_FIX}: fix older than {@code staleFixSeconds} (offline queue). {@code staleAction}, FLAG by default.</li>
 *   <li>{@code SIMULATED}: the phone marked the fix as produced by software. {@code simulatedAction}, NO_REWARD.</li>
 *   <li>{@code ACCESSORY}: produced by an external accessory (car, GPS puck). {@code accessoryAction}, FLAG.</li>
 *   <li>{@code IDENTICAL_REPEAT}: the same coordinates to 6 decimals and same accuracy repeated
 *       {@code identicalFixRepeat} times. {@code identicalAction}, FLAG.</li>
 *   <li>{@code SPEED_IMPOSSIBLE}: implied speed from the last clean fix. Within {@code footWindowSeconds} the
 *       on-foot limit applies; over a longer gap transit is plausible and the vehicle limit applies; moves
 *       within the jitter allowance (GAME-4: both accuracies + 50 m, at most {@code minMoveMeters}) are never
 *       judged. A rolling {@code windowMinutes} window also caps total displacement at the vehicle speed. Journeys and presence always use the
 *       vehicle limit (riding between stations is allowed). NO_REWARD.</li>
 * </ol>
 * Missing signals (old clients, the mini-program) add no reason at all.
 */
public final class LocationTrustPolicy {

    public static final String INVALID_FIX = "INVALID_FIX";
    public static final String INVALID_ACCURACY = "INVALID_ACCURACY";
    public static final String ACCURACY_POOR = "ACCURACY_POOR";
    public static final String FUTURE_FIX = "FUTURE_FIX";
    public static final String STALE_FIX = "STALE_FIX";
    public static final String SIMULATED = "SIMULATED";
    public static final String ACCESSORY = "ACCESSORY";
    public static final String IDENTICAL_REPEAT = "IDENTICAL_REPEAT";
    public static final String SPEED_IMPOSSIBLE = "SPEED_IMPOSSIBLE";

    private LocationTrustPolicy() {
    }

    /** The last clean fix this member reported (any kind); the server receive time is authoritative. */
    public record Baseline(double lat, double lng, long receivedAtMs, Long clientTimestampMs, Double accuracyMeters) {
        public Baseline(double lat, double lng, long receivedAtMs, Long clientTimestampMs) {
            this(lat, lng, receivedAtMs, clientTimestampMs, null);
        }
    }

    /** GAME-4: accuracies are clamped to this range for the jitter allowance; a missing one counts as the top. */
    static final double JITTER_ACCURACY_MIN = 20d;
    static final double JITTER_ACCURACY_MAX = 150d;
    static final double JITTER_MARGIN_METERS = 50d;
    /** GAME-4: the rolling window check ignores windows shorter than this (one fix right after another). */
    static final long WINDOW_MIN_SECONDS = 30L;

    /** Result of scoring. {@code speedMps} is null when there was no usable baseline. */
    public record Verdict(LocationTrustDecision decision, List<String> reasons, Double speedMps) {
        public Verdict {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public static Verdict accept() {
            return new Verdict(LocationTrustDecision.ACCEPT, List.of(), null);
        }

        public boolean credited() {
            return decision == LocationTrustDecision.ACCEPT;
        }

        public boolean rejected() {
            return decision == LocationTrustDecision.REJECT;
        }

        public boolean flagged() {
            return !reasons.isEmpty();
        }

        public String reasonText() {
            return String.join(",", reasons);
        }
    }

    public static Verdict evaluate(LocationTrustKind kind, LocationFix fix, Baseline baseline, int identicalBefore,
                                   long nowMs, LocationTrustConfig cfg) {
        return evaluate(kind, fix, baseline, null, identicalBefore, nowMs, cfg);
    }

    /**
     * @param windowStart GAME-4: the earliest clean fix within {@code windowMinutes}; total displacement since it
     *                    must stay within the vehicle speed. Null skips the window check.
     */
    public static Verdict evaluate(LocationTrustKind kind, LocationFix fix, Baseline baseline, Baseline windowStart,
                                   int identicalBefore, long nowMs, LocationTrustConfig cfg) {
        List<String> reasons = new ArrayList<>();
        LocationTrustDecision decision = LocationTrustDecision.ACCEPT;
        if (fix == null || !fix.validCoordinates()) {
            return new Verdict(LocationTrustDecision.REJECT, Collections.singletonList(INVALID_FIX), null);
        }
        Double acc = fix.accuracyMeters();
        if (acc != null) {
            if (!Double.isFinite(acc) || acc < 0d) {
                reasons.add(INVALID_ACCURACY);
                decision = decision.max(LocationTrustDecision.REJECT);
            } else if (acc > cfg.pointMaxAccuracyMeters()) {
                reasons.add(ACCURACY_POOR);
                if (kind.pointReward()) decision = decision.max(LocationTrustDecision.REJECT);
            }
        }
        Long ts = fix.clientTimestampMs();
        if (ts != null) {
            if (ts > nowMs + cfg.maxFutureSkewSeconds() * 1000L) {
                reasons.add(FUTURE_FIX);
                decision = decision.max(LocationTrustDecision.REJECT);
            } else if (nowMs - ts > cfg.staleFixSeconds() * 1000L) {
                reasons.add(STALE_FIX);
                decision = decision.max(cfg.staleAction().decision());
            }
        }
        if (Boolean.TRUE.equals(fix.simulated())) {
            reasons.add(SIMULATED);
            decision = decision.max(cfg.simulatedAction().decision());
        }
        if (Boolean.TRUE.equals(fix.accessory())) {
            reasons.add(ACCESSORY);
            decision = decision.max(cfg.accessoryAction().decision());
        }
        if (identicalBefore + 1 >= cfg.identicalFixRepeat()) {
            reasons.add(IDENTICAL_REPEAT);
            decision = decision.max(cfg.identicalAction().decision());
        }
        Double speed = null;
        if (baseline != null) {
            long serverDtMs = nowMs - baseline.receivedAtMs();
            if (serverDtMs >= 0L && serverDtMs <= cfg.speedBaselineWindowSeconds() * 1000L) {
                long dtMs = Math.max(serverDtMs, trustedClientDeltaMs(fix, baseline, nowMs, cfg));
                long dtSec = Math.max(1L, dtMs / 1000L);
                double dist = GeoDistanceUtils.haversineMeters(baseline.lat(), baseline.lng(), fix.lat(), fix.lng());
                if (Double.isFinite(dist)) {
                    speed = dist / dtSec;
                    if (dist > jitterMeters(fix, baseline, cfg) && speed > speedLimit(kind, dtSec, cfg)) {
                        reasons.add(SPEED_IMPOSSIBLE);
                        decision = decision.max(LocationTrustDecision.ACCEPT_NO_REWARD);
                    }
                }
            }
        }
        // GAME-4: many short hops can each stay under the jitter allowance; the window sees their sum.
        if (windowStart != null && !reasons.contains(SPEED_IMPOSSIBLE)) {
            long windowMs = nowMs - windowStart.receivedAtMs();
            if (windowMs >= WINDOW_MIN_SECONDS * 1000L && windowMs <= cfg.windowMinutes() * 60_000L) {
                double dist = GeoDistanceUtils.haversineMeters(windowStart.lat(), windowStart.lng(), fix.lat(), fix.lng());
                if (Double.isFinite(dist) && dist > jitterMeters(fix, windowStart, cfg)
                        && dist / (windowMs / 1000d) > cfg.vehicleSpeedMps()) {
                    reasons.add(SPEED_IMPOSSIBLE);
                    decision = decision.max(LocationTrustDecision.ACCEPT_NO_REWARD);
                }
            }
        }
        return new Verdict(decision, reasons, speed);
    }

    /**
     * GAME-4: how far two fixes may differ and still be GPS jitter. FLAT (old rule) = {@code minMoveMeters}.
     * ACCURACY (default) = both accuracies (clamped 20–150 m, missing = 150 m) + 50 m, never more than FLAT,
     * so a 290 m hop between two good fixes is judged while old clients without accuracy keep today's rule.
     */
    static double jitterMeters(LocationFix fix, Baseline before, LocationTrustConfig cfg) {
        if (cfg.jitterMode() == LocationTrustConfig.JitterMode.FLAT) return cfg.minMoveMeters();
        double allowance = clampAccuracy(fix.accuracyMeters()) + clampAccuracy(before == null ? null : before.accuracyMeters())
                + JITTER_MARGIN_METERS;
        return Math.min(cfg.minMoveMeters(), allowance);
    }

    private static double clampAccuracy(Double accuracy) {
        if (accuracy == null || !Double.isFinite(accuracy) || accuracy < 0d) return JITTER_ACCURACY_MAX;
        return Math.max(JITTER_ACCURACY_MIN, Math.min(JITTER_ACCURACY_MAX, accuracy));
    }

    /** On foot for short gaps; vehicles over long gaps and for journeys/presence. */
    static double speedLimit(LocationTrustKind kind, long dtSec, LocationTrustConfig cfg) {
        if (kind == LocationTrustKind.PRESENCE || kind == LocationTrustKind.JOURNEY_START
                || kind == LocationTrustKind.JOURNEY_NODE) {
            return cfg.vehicleSpeedMps();
        }
        return dtSec <= cfg.footWindowSeconds() ? cfg.footSpeedMps() : cfg.vehicleSpeedMps();
    }

    /**
     * Device clock gap between the two fixes, used only to be more lenient with offline-queued writes. Each
     * timestamp must itself be within the stale window of when the server received it, so a forged old
     * timestamp can stretch the gap by at most {@code staleFixSeconds + maxFutureSkewSeconds}.
     */
    static long trustedClientDeltaMs(LocationFix fix, Baseline baseline, long nowMs, LocationTrustConfig cfg) {
        Long now = fix.clientTimestampMs();
        Long before = baseline.clientTimestampMs();
        if (now == null || before == null) return 0L;
        long staleMs = cfg.staleFixSeconds() * 1000L;
        long skewMs = cfg.maxFutureSkewSeconds() * 1000L;
        if (nowMs - now > staleMs || now - nowMs > skewMs) return 0L;
        if (baseline.receivedAtMs() - before > staleMs || before - baseline.receivedAtMs() > skewMs) return 0L;
        return Math.max(0L, now - before);
    }

    // ---- fog reveal -------------------------------------------------------------------------------------

    /** Session-wide tile budget at the configured reveal speed: base + elapsed × speed / tile edge. */
    public static int revealSessionBudget(long startedAtMs, long nowMs, int baseTiles, LocationTrustConfig cfg) {
        long elapsedSec = startedAtMs <= 0L ? 0L : Math.max(0L, (nowMs - startedAtMs) / 1000L);
        double byTime = elapsedSec * cfg.revealSpeedMps() / cfg.revealTileEdgeMeters();
        return (int) Math.min(Integer.MAX_VALUE, baseTiles + (long) Math.ceil(byTime));
    }

    /** Rolling cap: tiles credited in the last {@code revealWindowMinutes} may not exceed base + window × speed. */
    public static int revealWindowBudget(LocationTrustConfig cfg) {
        double byTime = cfg.revealWindowMinutes() * 60d * cfg.revealSpeedMps() / cfg.revealTileEdgeMeters();
        return cfg.revealWindowBaseTiles() + (int) Math.ceil(byTime);
    }

    /**
     * Keeps only tiles whose centre is within {@code revealTrackRadiusMeters} of at least one track point.
     * An empty track means the client sent no fixes (old builds): the caller then skips this check.
     */
    public static List<String> tilesNearTrack(List<String> tiles, List<double[]> track, LocationTrustConfig cfg) {
        if (tiles == null || tiles.isEmpty()) return List.of();
        if (track == null || track.isEmpty()) return List.copyOf(tiles);
        List<String> kept = new ArrayList<>();
        for (String tile : tiles) {
            double[] c = Geohash.decodeCenter(tile);
            if (c == null) continue;
            for (double[] p : track) {
                if (GeoDistanceUtils.haversineMeters(c[0], c[1], p[0], p[1]) <= cfg.revealTrackRadiusMeters()) {
                    kept.add(tile);
                    break;
                }
            }
        }
        return kept;
    }
}
