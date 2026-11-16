package com.chengyinhub.business.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chengyinhub.common.constant.RewardPolicy;

/** GAME-2: "progress is free, prizes need presence"; every switch defaults to the safe value. */
class RewardPresencePolicyTest {

    private static final double LAT = 31.2304, LNG = 121.4737;

    @Test
    void defaultsAreSafe() {
        RewardPresencePolicy p = RewardPresencePolicy.defaults();
        assertFalse(p.remotePaysFull());
        assertEquals(RewardPolicy.XP_BADGE_ONLY, p.policyFor(false));
        assertEquals(RewardPolicy.FULL, p.policyFor(true));
        assertEquals(80d, p.rewardRadiusMeters(null));
    }

    @Test
    void missingOrBrokenConfigFallsBackToSafeValues() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put(RewardPresencePolicy.KEY_REMOTE_POLICY, "nonsense");
        cfg.put(RewardPresencePolicy.KEY_RADIUS, "abc");
        RewardPresencePolicy p = RewardPresencePolicy.read(cfg::get);
        assertFalse(p.remotePaysFull());
        assertEquals(80d, p.rewardRadiusMeters(null));
        RewardPresencePolicy throwing = RewardPresencePolicy.read(k -> { throw new IllegalStateException("db down"); });
        assertFalse(throwing.remotePaysFull());
    }

    @Test
    void fullSwitchRestoresPreviousBehaviour() {
        RewardPresencePolicy p = RewardPresencePolicy.read(k -> RewardPresencePolicy.KEY_REMOTE_POLICY.equals(k) ? "FULL" : null);
        assertTrue(p.remotePaysFull());
        assertEquals(RewardPolicy.FULL, p.policyFor(false));
    }

    @Test
    void radiusIsClampedAndTheLargerStationRadiusWins() {
        assertEquals(30d, RewardPresencePolicy.read(k -> RewardPresencePolicy.KEY_RADIUS.equals(k) ? "5" : null).rewardRadiusMeters(null));
        assertEquals(1000d, RewardPresencePolicy.read(k -> RewardPresencePolicy.KEY_RADIUS.equals(k) ? "99999" : null).rewardRadiusMeters(null));
        // the legacy station radius (50 m) is smaller than 80 m
        assertEquals(80d, RewardPresencePolicy.defaults().rewardRadiusMeters(ArrivalPolicy.legacy()));
    }

    @Test
    void withinUsesRadiusPlusCappedAccuracy() {
        // ≈ 67 m north
        assertTrue(RewardPresencePolicy.within(31.2310, LNG, 10d, LAT, LNG, 80d));
        // ≈ 111 m north: outside 80 m even with 15 m accuracy, inside with 50 m slack (capped from 500)
        assertFalse(RewardPresencePolicy.within(31.2314, LNG, 15d, LAT, LNG, 80d));
        assertTrue(RewardPresencePolicy.within(31.2314, LNG, 500d, LAT, LNG, 80d));
        // ≈ 2 km
        assertFalse(RewardPresencePolicy.within(31.2484, LNG, 10d, LAT, LNG, 80d));
        // missing fix = not present
        assertFalse(RewardPresencePolicy.within(null, null, null, LAT, LNG, 80d));
    }
}
