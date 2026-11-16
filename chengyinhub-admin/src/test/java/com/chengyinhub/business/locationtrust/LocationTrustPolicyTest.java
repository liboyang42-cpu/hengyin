package com.chengyinhub.business.locationtrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** P8 scoring rules. Honest players first: every "honest_*" case must stay ACCEPT with defaults. */
class LocationTrustPolicyTest {
    private static final LocationTrustConfig CFG = LocationTrustConfig.defaults();
    private static final long NOW = 1_800_000_000_000L;
    private static final double LAT = 31.2304, LNG = 121.4737;
    /** ~111 m per 0.001° latitude. */
    private static final double M_PER_DEG = 111_195d;

    private static LocationFix full(double lat, double lng, Double acc, Long ts, Boolean sim, Boolean accessory) {
        return new LocationFix(lat, lng, acc, ts, sim, accessory);
    }

    private static LocationTrustPolicy.Baseline baseAgo(long seconds, double metersNorth) {
        return new LocationTrustPolicy.Baseline(LAT - metersNorth / M_PER_DEG, LNG, NOW - seconds * 1000L, null);
    }

    private static LocationTrustPolicy.Verdict eval(LocationTrustKind kind, LocationFix fix, LocationTrustPolicy.Baseline b) {
        return LocationTrustPolicy.evaluate(kind, fix, b, 0, NOW, CFG);
    }

    @Test
    void honest_oldClientWithoutSignals_isAcceptedWithoutReasons() {
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN, LocationFix.of(LAT, LNG), null);
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
        assertFalse(v.flagged());
        assertNull(v.speedMps());
    }

    @Test
    void honest_walkingCheckinWithGoodFix_isAccepted() {
        // 1.2 km in 15 minutes = 1.3 m/s
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN,
                full(LAT, LNG, 12d, NOW - 2_000L, false, false), baseAgo(900, 1200));
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
        assertTrue(v.reasons().isEmpty());
        assertTrue(v.speedMps() < 2d);
    }

    @Test
    void honest_gpsJitterBelowMinMove_isNeverJudged() {
        // 250 m in 1 s would be 250 m/s, but under 300 m is treated as jitter
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.SHOP_VISIT, LocationFix.of(LAT, LNG), baseAgo(1, 250));
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
    }

    /** GAME-4: 290 m hops 3 s apart between two good fixes are no longer "jitter". */
    @Test
    void steppedJumpsAreJudged() {
        LocationTrustPolicy.Baseline before = new LocationTrustPolicy.Baseline(LAT - 290 / M_PER_DEG, LNG, NOW - 3_000L, null, 10d);
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN, full(LAT, LNG, 10d, null, null, null), before);
        assertTrue(v.reasons().contains(LocationTrustPolicy.SPEED_IMPOSSIBLE));
        assertEquals(LocationTrustDecision.ACCEPT_NO_REWARD, v.decision());
    }

    @Test
    void steppedJumpsKeepTheOldRuleInFlatMode() {
        LocationTrustConfig flat = LocationTrustConfig.read(k -> "location.trust.jitterMode".equals(k) ? "FLAT" : null);
        LocationTrustPolicy.Baseline before = new LocationTrustPolicy.Baseline(LAT - 290 / M_PER_DEG, LNG, NOW - 3_000L, null, 10d);
        LocationTrustPolicy.Verdict v = LocationTrustPolicy.evaluate(LocationTrustKind.CHECKIN,
                full(LAT, LNG, 10d, null, null, null), before, 0, NOW, flat);
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
    }

    @Test
    void jitterAllowanceNeverWidensForClientsWithoutAccuracy() {
        // both accuracies missing: 150 + 150 + 50 = 350 m, capped at the old 300 m rule
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.SHOP_VISIT, LocationFix.of(LAT, LNG), baseAgo(1, 320));
        assertTrue(v.reasons().contains(LocationTrustPolicy.SPEED_IMPOSSIBLE));
    }

    /** GAME-4: many small hops each under the allowance are caught by the 10-minute window. */
    @Test
    void windowCatchesManySmallHops() {
        LocationTrustPolicy.Baseline last = new LocationTrustPolicy.Baseline(LAT - 60 / M_PER_DEG, LNG, NOW - 3_000L, null, 10d);
        LocationTrustPolicy.Baseline windowStart = new LocationTrustPolicy.Baseline(LAT - 9_000 / M_PER_DEG, LNG, NOW - 120_000L, null, 10d);
        LocationTrustPolicy.Verdict v = LocationTrustPolicy.evaluate(LocationTrustKind.CHECKIN,
                full(LAT, LNG, 10d, null, null, null), last, windowStart, 0, NOW, CFG);
        assertTrue(v.reasons().contains(LocationTrustPolicy.SPEED_IMPOSSIBLE), "9 km in 2 minutes = 75 m/s");
        LocationTrustPolicy.Verdict walking = LocationTrustPolicy.evaluate(LocationTrustKind.CHECKIN,
                full(LAT, LNG, 10d, null, null, null), last,
                new LocationTrustPolicy.Baseline(LAT - 600 / M_PER_DEG, LNG, NOW - 540_000L, null, 10d), 0, NOW, CFG);
        assertEquals(LocationTrustDecision.ACCEPT, walking.decision());
    }

    @Test
    void honest_metroBetweenTwoCheckins_overLongGapUsesVehicleLimit() {
        // 12 km in 12 minutes = 16.7 m/s: over the on-foot 15 m/s, but the gap is > 5 min so 35 m/s applies
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN, LocationFix.of(LAT, LNG), baseAgo(720, 12_000));
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
    }

    @Test
    void honest_offlineQueuedWrite_usesDeviceClockGap() {
        // baseline fix taken 20 min ago, received 20 min ago; this fix was taken 1 min after it but uploaded now
        long baseTs = NOW - 1_200_000L;
        LocationTrustPolicy.Baseline b = new LocationTrustPolicy.Baseline(LAT - 600 / M_PER_DEG, LNG, baseTs, baseTs);
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.REVEAL, full(LAT, LNG, 10d, NOW - 60_000L, false, false), b);
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
    }

    @Test
    void honest_accessoryGps_isOnlyFlagged() {
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN, full(LAT, LNG, 5d, NOW, false, true), null);
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
        assertEquals(List.of(LocationTrustPolicy.ACCESSORY), v.reasons());
    }

    @Test
    void honest_staleQueuedFix_isOnlyFlagged() {
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN, full(LAT, LNG, 5d, NOW - 3_600_000L, null, null), null);
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
        assertEquals(List.of(LocationTrustPolicy.STALE_FIX), v.reasons());
    }

    @Test
    void honest_accuracy200IsStillOkForCheckin() {
        assertEquals(LocationTrustDecision.ACCEPT,
                eval(LocationTrustKind.CHECKIN, full(LAT, LNG, 200d, null, null, null), null).decision());
    }

    @Test
    void poorAccuracy_rejectsPointRewardButOnlyFlagsReveal() {
        LocationFix fix = full(LAT, LNG, 350d, null, null, null);
        LocationTrustPolicy.Verdict checkin = eval(LocationTrustKind.CHECKIN, fix, null);
        assertEquals(LocationTrustDecision.REJECT, checkin.decision());
        assertEquals(List.of(LocationTrustPolicy.ACCURACY_POOR), checkin.reasons());
        LocationTrustPolicy.Verdict reveal = eval(LocationTrustKind.REVEAL, fix, null);
        assertEquals(LocationTrustDecision.ACCEPT, reveal.decision());
        assertTrue(reveal.flagged());
    }

    @Test
    void invalidAccuracyOrCoordinates_reject() {
        assertEquals(LocationTrustDecision.REJECT,
                eval(LocationTrustKind.REVEAL, full(LAT, LNG, -1d, null, null, null), null).decision());
        assertEquals(LocationTrustDecision.REJECT,
                eval(LocationTrustKind.REVEAL, full(LAT, LNG, Double.NaN, null, null, null), null).decision());
        assertEquals(LocationTrustDecision.REJECT, eval(LocationTrustKind.REVEAL, LocationFix.of(Double.NaN, LNG), null).decision());
        assertEquals(LocationTrustDecision.REJECT, eval(LocationTrustKind.REVEAL, LocationFix.of(91d, LNG), null).decision());
    }

    @Test
    void futureFixTime_rejects() {
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN, full(LAT, LNG, 5d, NOW + 600_000L, null, null), null);
        assertEquals(LocationTrustDecision.REJECT, v.decision());
        assertEquals(List.of(LocationTrustPolicy.FUTURE_FIX), v.reasons());
        // within the 120 s skew allowance: fine
        assertEquals(LocationTrustDecision.ACCEPT,
                eval(LocationTrustKind.CHECKIN, full(LAT, LNG, 5d, NOW + 60_000L, null, null), null).decision());
    }

    @Test
    void simulatedFix_isKeptButNotRewarded() {
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN, full(LAT, LNG, 5d, NOW, true, false), null);
        assertEquals(LocationTrustDecision.ACCEPT_NO_REWARD, v.decision());
        assertEquals(List.of(LocationTrustPolicy.SIMULATED), v.reasons());
    }

    @Test
    void sofaTeleport_fasterThan15mpsOnFoot_isNotRewarded() {
        // 2 km in 60 s = 33 m/s within the on-foot window
        LocationTrustPolicy.Verdict v = eval(LocationTrustKind.CHECKIN, LocationFix.of(LAT, LNG), baseAgo(60, 2000));
        assertEquals(LocationTrustDecision.ACCEPT_NO_REWARD, v.decision());
        assertEquals(List.of(LocationTrustPolicy.SPEED_IMPOSSIBLE), v.reasons());
        assertTrue(v.speedMps() > 30d);
    }

    @Test
    void speedBoundary_15mpsOnFootIsAllowed_16IsNot() {
        assertEquals(LocationTrustDecision.ACCEPT,
                eval(LocationTrustKind.SHOP_VISIT, LocationFix.of(LAT, LNG), baseAgo(100, 1490)).decision());
        assertEquals(LocationTrustDecision.ACCEPT_NO_REWARD,
                eval(LocationTrustKind.SHOP_VISIT, LocationFix.of(LAT, LNG), baseAgo(100, 1610)).decision());
    }

    @Test
    void journeyNodes_allowTransitSpeedsButNotTeleports() {
        // 20 m/s on a metro between stations within 2 minutes: allowed for journeys
        assertEquals(LocationTrustDecision.ACCEPT,
                eval(LocationTrustKind.JOURNEY_NODE, LocationFix.of(LAT, LNG), baseAgo(120, 2400)).decision());
        // 10 km in 60 s
        assertEquals(LocationTrustDecision.ACCEPT_NO_REWARD,
                eval(LocationTrustKind.JOURNEY_NODE, LocationFix.of(LAT, LNG), baseAgo(60, 10_000)).decision());
    }

    @Test
    void baselineOutsideWindow_isIgnored() {
        assertNull(eval(LocationTrustKind.CHECKIN, LocationFix.of(LAT, LNG), baseAgo(3 * 3600, 500_000)).speedMps());
    }

    @Test
    void identicalRepeats_areFlaggedOnly() {
        LocationTrustPolicy.Verdict v = LocationTrustPolicy.evaluate(LocationTrustKind.CHECKIN,
                full(LAT, LNG, 5d, null, null, null), null, 5, NOW, CFG);
        assertEquals(LocationTrustDecision.ACCEPT, v.decision());
        assertEquals(List.of(LocationTrustPolicy.IDENTICAL_REPEAT), v.reasons());
        assertFalse(LocationTrustPolicy.evaluate(LocationTrustKind.CHECKIN,
                full(LAT, LNG, 5d, null, null, null), null, 4, NOW, CFG).flagged());
    }

    @Test
    void forgedOldTimestamps_canStretchTheGapOnlyBoundedly() {
        long ancient = NOW - 30L * 24 * 3600 * 1000;
        LocationTrustPolicy.Baseline b = new LocationTrustPolicy.Baseline(LAT, LNG, NOW - 60_000L, ancient);
        assertEquals(0L, LocationTrustPolicy.trustedClientDeltaMs(full(LAT, LNG, null, NOW, null, null), b, NOW, CFG));
    }

    @Test
    void configuredActionsAreHonoured() {
        LocationTrustConfig strict = LocationTrustConfig.read(Map.of(
                "location.trust.simulatedAction", "REJECT",
                "location.trust.accessoryAction", "no_reward")::get);
        assertEquals(LocationTrustDecision.REJECT, LocationTrustPolicy.evaluate(LocationTrustKind.REVEAL,
                full(LAT, LNG, null, null, true, null), null, 0, NOW, strict).decision());
        assertEquals(LocationTrustDecision.ACCEPT_NO_REWARD, LocationTrustPolicy.evaluate(LocationTrustKind.REVEAL,
                full(LAT, LNG, null, null, null, true), null, 0, NOW, strict).decision());
    }

    // ---- reveal ----

    @Test
    void revealBudget_idleHourIsCappedAtWalkingSpeed() {
        // was 200 + 3600*35/100 = 1460 tiles; now 200 + 3600*15/100 = 740
        assertEquals(740, LocationTrustPolicy.revealSessionBudget(NOW - 3_600_000L, NOW, 200, CFG));
        assertEquals(200, LocationTrustPolicy.revealSessionBudget(0L, NOW, 200, CFG));
        assertEquals(300 + 90, LocationTrustPolicy.revealWindowBudget(CFG));
    }

    @Test
    void revealTiles_farFromTrackAreDropped_noTrackKeepsAll() {
        String here = "wtw3sjq";              // central Shanghai
        String farAway = "wx4g0bm";           // Beijing
        double[] c = Geohash.decodeCenter(here);
        List<double[]> track = List.of(new double[] {c[0], c[1]});
        assertEquals(List.of(here), LocationTrustPolicy.tilesNearTrack(List.of(here, farAway), track, CFG));
        assertEquals(List.of(here, farAway), LocationTrustPolicy.tilesNearTrack(List.of(here, farAway), List.of(), CFG));
    }

    @Test
    void geohashDecode_matchesKnownCell() {
        double[] c = Geohash.decodeCenter("wtw3sjq");
        assertEquals(31.23, c[0], 0.01);
        assertEquals(121.47, c[1], 0.01);
        assertNull(Geohash.decodeCenter("wtw3sja"));   // 'a' is not in the alphabet
    }

    @Test
    void config_defaultsAndClamping() {
        LocationTrustConfig d = LocationTrustConfig.defaults();
        assertEquals(LocationTrustConfig.Mode.ENFORCE, d.mode());
        assertEquals(15d, d.footSpeedMps());
        assertEquals(200d, d.pointMaxAccuracyMeters());
        assertEquals(LocationTrustAction.NO_REWARD, d.simulatedAction());
        assertEquals(LocationTrustAction.FLAG, d.accessoryAction());
        assertFalse(d.journeyRequireFixAfterStart());
        LocationTrustConfig odd = LocationTrustConfig.read(Map.of(
                "location.trust.footSpeedMps", "0.1",
                "location.trust.pointMaxAccuracyMeters", "abc",
                "location.trust.mode", "shadow",
                "location.trust.simulatedAction", "BAN")::get);
        assertEquals(5d, odd.footSpeedMps());
        assertEquals(200d, odd.pointMaxAccuracyMeters());
        assertEquals(LocationTrustConfig.Mode.SHADOW, odd.mode());
        assertEquals(LocationTrustAction.NO_REWARD, odd.simulatedAction());
    }

    @Test
    void signals_parseLeniently() {
        LocationFix f = LocationSignals.parse(LAT, LNG, "12.5", "1800000000000", "1", "false");
        assertEquals(12.5d, f.accuracyMeters());
        assertEquals(1_800_000_000_000L, f.clientTimestampMs());
        assertTrue(f.simulated());
        assertFalse(f.accessory());
        LocationFix none = LocationSignals.parse(LAT, LNG, null, "x", "maybe", "");
        assertNull(none.accuracyMeters());
        assertNull(none.clientTimestampMs());
        assertNull(none.simulated());
        assertNull(none.accessory());
        assertTrue(LocationSignals.parse(LAT, LNG, "abc", null, null, null).accuracyMeters().isNaN());
    }
}
