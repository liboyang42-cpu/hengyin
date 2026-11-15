package com.chengyinhub.business.citygame;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** One test per City Monopoly rule (CITY-MONOPOLY-SPEC §2), against the approved defaults. Amounts are 积分. */
class CityMonopolyRulesTest {
    private static final CityMonopolyConfig C = CityMonopolyConfig.defaults();
    private static final double A_LAT = 31.2304, A_LNG = 121.4737;
    private static final long NOW = 1_800_000_000_000L;

    // ---------------------------------------------------------------- tiers & upgrades (§2.1, §2.3)

    @Test
    void tierClaimCostsAndBaseRent() {
        assertEquals(List.of(60L, 120L, 240L, 400L), List.of(CityMonopolyRules.claimCost(C, 1), CityMonopolyRules.claimCost(C, 2),
                CityMonopolyRules.claimCost(C, 3), CityMonopolyRules.claimCost(C, 4)));
        assertEquals(List.of(3, 5, 8, 12), List.of(CityMonopolyRules.baseYield(C, 1), CityMonopolyRules.baseYield(C, 2),
                CityMonopolyRules.baseYield(C, 3), CityMonopolyRules.baseYield(C, 4)));
        assertEquals(60L, CityMonopolyRules.claimCost(C, CityMonopolyRules.normalizeTier(null)), "unknown tier defaults to T1");
        assertEquals(400L, CityMonopolyRules.claimCost(C, 9), "tier clamps to T4");
    }

    @Test
    void upgradeCostsInvestedAndLevelMultipliers() {
        long[][] expected = {{30, 48, 72}, {60, 96, 144}, {120, 192, 288}, {200, 320, 480}};
        long[] invested = {210, 420, 840, 1400};
        for (int tier = 1; tier <= 4; tier++) {
            for (int lvl = 0; lvl < 3; lvl++) assertEquals(expected[tier - 1][lvl], CityMonopolyRules.upgradeCost(C, tier, lvl));
            assertEquals(-1, CityMonopolyRules.upgradeCost(C, tier, 3), "Lv3 is max");
            assertEquals(invested[tier - 1], CityMonopolyRules.investedAt(C, tier, 3));
        }
        assertEquals(1.0, CityMonopolyRules.levelYieldMultiplier(C, 0));
        assertEquals(1.4, CityMonopolyRules.levelYieldMultiplier(C, 1));
        assertEquals(1.8, CityMonopolyRules.levelYieldMultiplier(C, 2));
        assertEquals(2.4, CityMonopolyRules.levelYieldMultiplier(C, 3));
    }

    // ---------------------------------------------------------------- rent & multipliers (§2.4)

    private static CityMonopolyRules.YieldInput in(int tier, int lvl, CityMonopolyRules.SetState set, LocalTime t) {
        return new CityMonopolyRules.YieldInput(tier, lvl, set, t, CityMonopolyRules.Weather.CLEAR, 1.0, 1.0, false, 0, false, false);
    }

    @Test
    void rentQuickReferenceTableMatchesSpec() {
        int[][] table = {{3, 4, 5, 7}, {5, 7, 9, 12}, {8, 11, 14, 19}, {12, 17, 22, 29}};
        for (int tier = 1; tier <= 4; tier++) {
            for (int lvl = 0; lvl <= 3; lvl++) {
                assertEquals(table[tier - 1][lvl], CityMonopolyRules.computeYield(C, in(tier, lvl, CityMonopolyRules.SetState.NONE, LocalTime.of(12, 0))).raw(),
                        "T" + tier + " Lv" + lvl);
            }
        }
    }

    @Test
    void totalMultiplierIsCappedAtFour() {
        int[] capped = {12, 20, 32, 48};
        for (int tier = 1; tier <= 4; tier++) {
            CityMonopolyRules.YieldQuote q = CityMonopolyRules.computeYield(C, new CityMonopolyRules.YieldInput(tier, 3,
                    CityMonopolyRules.SetState.MONOPOLY, LocalTime.of(18, 0), CityMonopolyRules.Weather.LIGHT_RAIN, 2.0, 2.0, true, 50, false, false));
            assertTrue(q.product() > 4.0);
            assertTrue(q.capped());
            assertEquals(4.0, q.cappedProduct());
            assertEquals(capped[tier - 1], q.raw());
        }
    }

    @Test
    void eachMultiplierApplies() {
        LocalTime noon = LocalTime.of(12, 0);
        assertEquals(1.2, CityMonopolyRules.computeYield(C, in(1, 0, CityMonopolyRules.SetState.MAJORITY, noon)).multipliers().get("set"));
        assertEquals(1.5, CityMonopolyRules.computeYield(C, in(1, 0, CityMonopolyRules.SetState.MONOPOLY, noon)).multipliers().get("set"));
        assertEquals(1.1, CityMonopolyRules.timeMultiplier(C, LocalTime.of(6, 0)));
        assertEquals(1.1, CityMonopolyRules.timeMultiplier(C, LocalTime.of(9, 59)));
        assertEquals(1.0, CityMonopolyRules.timeMultiplier(C, LocalTime.of(10, 0)));
        assertEquals(1.2, CityMonopolyRules.timeMultiplier(C, LocalTime.of(17, 0)));
        assertEquals(1.0, CityMonopolyRules.timeMultiplier(C, LocalTime.of(22, 0)));
        assertEquals(0.0, CityMonopolyRules.timeMultiplier(C, LocalTime.of(23, 0)));
        assertEquals(0.0, CityMonopolyRules.timeMultiplier(C, LocalTime.of(3, 0)));
        assertEquals(0, CityMonopolyRules.computeYield(C, in(4, 3, CityMonopolyRules.SetState.MONOPOLY, LocalTime.of(2, 0))).raw(), "no rent 23-06");
        assertEquals(1.2, CityMonopolyRules.weatherMultiplier(C, CityMonopolyRules.Weather.LIGHT_RAIN));
        assertEquals(1.2, CityMonopolyRules.weatherMultiplier(C, CityMonopolyRules.Weather.MODERATE_RAIN));
        assertEquals(0.0, CityMonopolyRules.weatherMultiplier(C, CityMonopolyRules.Weather.RED_ALERT));
        CityMonopolyRules.YieldQuote team = CityMonopolyRules.computeYield(C, new CityMonopolyRules.YieldInput(2, 0, CityMonopolyRules.SetState.NONE,
                noon, CityMonopolyRules.Weather.CLEAR, 1.0, 1.0, true, 50, false, false));
        assertEquals(1.1, team.multipliers().get("team"));
        assertEquals(1.1, team.multipliers().get("charm"), 1e-9, "charm Lv50 = +10%");
        CityMonopolyRules.YieldQuote event = CityMonopolyRules.computeYield(C, new CityMonopolyRules.YieldInput(1, 0, CityMonopolyRules.SetState.NONE,
                noon, CityMonopolyRules.Weather.CLEAR, 9.0, 1.0, false, 0, false, false));
        assertEquals(2.0, event.multipliers().get("env"), "event multiplier clamps to 2.0");
    }

    @Test
    void setStateFromDistrictShare() {
        assertEquals(CityMonopolyRules.SetState.NONE, CityMonopolyRules.setState(C, 1, 4));
        assertEquals(CityMonopolyRules.SetState.MAJORITY, CityMonopolyRules.setState(C, 2, 4));
        assertEquals(CityMonopolyRules.SetState.MONOPOLY, CityMonopolyRules.setState(C, 4, 4));
        assertEquals(CityMonopolyRules.SetState.NONE, CityMonopolyRules.setState(C, 3, 0));
    }

    @Test
    void weatheringAndConcentrationPenaltyHalveRentAfterTheCap() {
        CityMonopolyRules.YieldQuote w = CityMonopolyRules.computeYield(C, new CityMonopolyRules.YieldInput(4, 3, CityMonopolyRules.SetState.MONOPOLY,
                LocalTime.of(18, 0), CityMonopolyRules.Weather.CLEAR, 2.0, 1.0, true, 50, true, false));
        assertEquals(24, w.raw(), "48 capped × 0.5 weathered");
        CityMonopolyRules.YieldQuote both = CityMonopolyRules.computeYield(C, new CityMonopolyRules.YieldInput(4, 3, CityMonopolyRules.SetState.MONOPOLY,
                LocalTime.of(18, 0), CityMonopolyRules.Weather.CLEAR, 2.0, 1.0, true, 50, true, true));
        assertEquals(12, both.raw());
    }

    // ---------------------------------------------------------------- daily owner cap (§2.5)

    @Test
    void ownerDailyCapSoftThresholdHalfRateAndHardMax() {
        assertEquals(200, CityMonopolyRules.ownerDailyThreshold(C, 0));
        assertEquals(250, CityMonopolyRules.ownerDailyThreshold(C, 25));
        assertEquals(300, CityMonopolyRules.ownerDailyThreshold(C, 50));
        assertEquals(300, CityMonopolyRules.ownerDailyMax(C, 0));
        assertEquals(400, CityMonopolyRules.ownerDailyMax(C, 50));
        CityMonopolyRules.CapResult under = CityMonopolyRules.applyOwnerDailyCap(C, 150, 40, 0);
        assertEquals(40, under.granted());
        assertNull(under.capReason());
        CityMonopolyRules.CapResult straddle = CityMonopolyRules.applyOwnerDailyCap(C, 190, 30, 0);
        assertEquals(10 + 10, straddle.granted(), "10 at 100% + 20 at 50%");
        assertEquals("OWNER_DAILY_SOFT", straddle.capReason());
        long granted = 0, raw = 0;
        for (int i = 0; i < 100; i++) {
            granted += CityMonopolyRules.applyOwnerDailyCap(C, raw, 29, 0).granted();
            raw += 29;
        }
        assertEquals(300, granted, "never more than T + 100 per day");
        assertEquals("OWNER_DAILY_MAX", CityMonopolyRules.applyOwnerDailyCap(C, raw, 29, 0).capReason());
        assertEquals(0, CityMonopolyRules.applyOwnerDailyCap(C, raw, 29, 0).granted());
    }

    // ---------------------------------------------------------------- visit eligibility, anti-self-farming & alts (§2.5, §2.11)

    private static CityMonopolyRules.VisitFacts eligible() {
        return new CityMonopolyRules.VisitFacts(false, false, false, 20, 10.0, 1.0, false, 30, 5, true, 2, 0, 0, false,
                0, 0, 0, 0, 0, 0, 0, false, false);
    }

    private static CityMonopolyRules.VisitFacts with(java.util.function.UnaryOperator<Object[]> edit) {
        CityMonopolyRules.VisitFacts f = eligible();
        Object[] a = {f.selfVisit(), f.sameTeam(), f.merchantStaff(), f.distanceM(), f.hAccM(), f.timeMultiplier(), f.safetyBlocked(),
                f.visitorAccountAgeDays(), f.visitorLevel(), f.visitorPhoneBound(), f.distinctOwnersVisited14d(), f.offboardCheckins14d(),
                f.otherYieldingAccountsOnDevice30d(), f.deviceFrozen(), f.visitorYieldingToday(), f.visitorThisPropertyToday(),
                f.pairThisPropertyInWindow(), f.pairOwnerInWindow(), f.propertyVisitsToday(), f.reciprocalAtoB(), f.reciprocalBtoA(),
                f.visitorBanned(), f.ownerBanned()};
        a = edit.apply(a);
        return new CityMonopolyRules.VisitFacts((boolean) a[0], (boolean) a[1], (boolean) a[2], (double) a[3], (Double) a[4], (double) a[5],
                (boolean) a[6], (long) a[7], (int) a[8], (boolean) a[9], (int) a[10], (int) a[11], (int) a[12], (boolean) a[13], (int) a[14],
                (int) a[15], (int) a[16], (int) a[17], (int) a[18], (int) a[19], (int) a[20], (boolean) a[21], (boolean) a[22]);
    }

    private static Object[] set(Object[] a, int i, Object v) { a[i] = v; return a; }

    @Test
    void eligibleVisitYields() {
        assertEquals(List.of(), CityMonopolyRules.yieldIneligibility(C, eligible()));
    }

    @Test
    void selfTeamAndStaffVisitsNeverYield() {
        assertEquals(List.of("SELF_VISIT"), CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 0, true))));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 1, true))).contains("SAME_TEAM"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 2, true))).contains("MERCHANT_STAFF"));
    }

    @Test
    void visitRadiusAccuracyQuietHoursAndSafety() {
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 3, 50.1))).contains("OUT_OF_RADIUS"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 3, 50.0))).isEmpty());
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 4, 35.1))).contains("POOR_ACCURACY"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 4, null))).contains("POOR_ACCURACY"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 5, 0.0))).contains("QUIET_HOURS"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 6, true))).contains("SAFETY_BLOCKED"));
    }

    @Test
    void altAccountVisitorGates() {
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 7, 6L))).contains("VISITOR_ACCOUNT_TOO_NEW"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 8, 2))).contains("VISITOR_LEVEL_TOO_LOW"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 9, false))).contains("VISITOR_PHONE_UNBOUND"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 10, 1))).contains("VISITOR_DIVERSITY"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(set(a, 10, 1), 11, 3))).isEmpty(), "3 off-board check-ins satisfy diversity");
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 12, 1))).contains("DEVICE_SHARED"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 13, true))).contains("DEVICE_FROZEN"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 21, true))).contains("VISITOR_BANNED"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 22, true))).contains("OWNER_BANNED"));
    }

    @Test
    void perVisitorPairAndPropertyCaps() {
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 14, 10))).contains("VISITOR_DAILY_CAP"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 14, 9))).isEmpty());
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 15, 1))).contains("VISITOR_PROPERTY_DAILY_CAP"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 16, 1))).contains("PAIR_PROPERTY_CAP"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 17, 3))).contains("PAIR_OWNER_CAP"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 17, 2))).isEmpty());
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 18, 20))).contains("PROPERTY_DAILY_CAP"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(a, 18, 19))).isEmpty());
    }

    @Test
    void reciprocityZeroesMutualFarming() {
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(set(a, 19, 3), 20, 3))).contains("RECIPROCITY"));
        assertTrue(CityMonopolyRules.yieldIneligibility(C, with(a -> set(set(a, 19, 3), 20, 2))).isEmpty());
    }

    @Test
    void concentrationRuleTopThreeShareOrHhi() {
        Map<Long, Long> farmed = new HashMap<>(Map.of(1L, 200L, 2L, 80L, 3L, 30L));
        for (long v = 10; v < 20; v++) farmed.put(v, 5L);
        assertTrue(CityMonopolyRules.concentrated(C, farmed));
        Map<Long, Long> organic = new HashMap<>();
        for (long v = 1; v <= 60; v++) organic.put(v, 6L);
        assertFalse(CityMonopolyRules.concentrated(C, organic));
        assertFalse(CityMonopolyRules.concentrated(C, Map.of(1L, 300L)), "only above 300 in 7 d");
    }

    @Test
    void ringDetectionNeedsFourReciprocalMembersWithMostlyInternalRent() {
        Map<Long, Map<Long, Long>> g = new HashMap<>();
        long[] ring = {1, 2, 3, 4};
        for (long a : ring) for (long b : ring) if (a != b) g.computeIfAbsent(b, k -> new HashMap<>()).put(a, 20L);
        g.get(1L).put(99L, 5L);
        List<Set<Long>> found = CityMonopolyService.rings(C, g);
        assertEquals(1, found.size());
        assertEquals(Set.of(1L, 2L, 3L, 4L), found.get(0));
        Map<Long, Map<Long, Long>> small = new HashMap<>();
        small.computeIfAbsent(1L, k -> new HashMap<>()).put(2L, 10L);
        small.computeIfAbsent(2L, k -> new HashMap<>()).put(1L, 10L);
        assertTrue(CityMonopolyService.rings(C, small).isEmpty(), "pairs are handled by reciprocity, not ring");
        for (long a : ring) for (long v = 100; v < 110; v++) g.get(a).put(v, 50L);
        assertTrue(CityMonopolyService.rings(C, g).isEmpty(), "mostly external rent is not a ring");
    }

    // ---------------------------------------------------------------- claim checks (§2.2)

    private static List<CityMonopolyRules.Sample> dwell(int n, int spanS) {
        List<CityMonopolyRules.Sample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new CityMonopolyRules.Sample(NOW - spanS * 1000L + (long) i * spanS * 1000L / Math.max(1, n - 1), A_LAT, A_LNG + i * 0.00001, 8.0));
        return out;
    }

    private static CityMonopolyRules.Evidence ev(double lat, Double hAcc, List<CityMonopolyRules.Sample> samples, boolean sim, boolean attest,
                                                 boolean camera, Long captured, Long phash, double recapture) {
        return new CityMonopolyRules.Evidence(lat, A_LNG, hAcc, samples, sim, attest, false, camera, captured, lat, A_LNG, phash, recapture);
    }

    private static CityMonopolyRules.Evidence good() {
        return ev(A_LAT, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, 0x0F0F0F0F0F0F0F0FL, 0.1);
    }

    private static CityMonopolyRules.ClaimContext ctx() {
        return new CityMonopolyRules.ClaimContext(A_LAT, A_LNG, NOW, LocalTime.of(12, 0), true, 10, 5, true, 0, 0, false, 0, 0,
                false, false, true, List.of(), List.of(), false, true);
    }

    private static CityMonopolyRules.ClaimContext ctx(java.util.function.UnaryOperator<Object[]> edit) {
        CityMonopolyRules.ClaimContext x = ctx();
        Object[] a = {x.anchorLat(), x.anchorLng(), x.nowMillis(), x.localTime(), x.requirePhoto(), x.accountAgeDays(), x.accountLevel(),
                x.phoneBound(), x.claimsToday(), x.failedOnPropertyToday(), x.lostWithinCooldown(), x.holdings(), x.exploreLevel(),
                x.staffOfStore(), x.safetyBlocked(), x.tileLit(), x.ownPriorHashes(), x.otherHashes(), x.banned(), x.checkAccountGates()};
        a = edit.apply(a);
        @SuppressWarnings("unchecked") List<Long> own = (List<Long>) a[16];
        @SuppressWarnings("unchecked") List<Long> other = (List<Long>) a[17];
        return new CityMonopolyRules.ClaimContext((double) a[0], (double) a[1], (long) a[2], (LocalTime) a[3], (boolean) a[4], (long) a[5],
                (int) a[6], (boolean) a[7], (int) a[8], (int) a[9], (boolean) a[10], (int) a[11], (int) a[12], (boolean) a[13], (boolean) a[14],
                (boolean) a[15], own, other, (boolean) a[18], (boolean) a[19]);
    }

    private static List<String> claim(CityMonopolyRules.Evidence e, CityMonopolyRules.ClaimContext x) {
        return CityMonopolyRules.evaluateClaim(C, e, x).reasons();
    }

    @Test
    void validClaimPasses() {
        CityMonopolyRules.ClaimVerdict v = CityMonopolyRules.evaluateClaim(C, good(), ctx());
        assertTrue(v.ok(), v.reasons().toString());
        assertTrue(v.dwellSeconds() >= 60);
    }

    @Test
    void claimRadiusIs40Metres() {
        assertTrue(claim(ev(A_LAT + 0.00030, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, 1L, 0), ctx()).isEmpty(), "~33 m ok");
        assertTrue(claim(ev(A_LAT + 0.00040, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, 1L, 0), ctx()).contains("OUT_OF_RADIUS"), "~44 m");
    }

    @Test
    void claimAccuracyMustBe25mOrBetterUnlessMerchantQr() {
        assertTrue(claim(ev(A_LAT, 25.0, dwell(4, 90), false, true, true, NOW - 30_000L, 1L, 0), ctx()).isEmpty());
        assertTrue(claim(ev(A_LAT, 25.5, dwell(4, 90), false, true, true, NOW - 30_000L, 1L, 0), ctx()).contains("POOR_ACCURACY"));
        assertTrue(claim(ev(A_LAT, null, dwell(4, 90), false, true, true, NOW - 30_000L, 1L, 0), ctx()).contains("POOR_ACCURACY"));
        CityMonopolyRules.Evidence qr = new CityMonopolyRules.Evidence(A_LAT, A_LNG, 60.0, dwell(4, 90), false, true, true, true, NOW - 30_000L, A_LAT, A_LNG, 1L, 0.0);
        assertTrue(claim(qr, ctx()).isEmpty(), "merchant static QR relaxes to 65 m");
    }

    @Test
    void claimDwellNeedsThreeSamplesOverSixtySeconds() {
        assertTrue(claim(ev(A_LAT, 10.0, dwell(2, 90), false, true, true, NOW - 30_000L, 1L, 0), ctx()).contains("DWELL_TOO_FEW_SAMPLES"));
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 59), false, true, true, NOW - 30_000L, 1L, 0), ctx()).contains("DWELL_TOO_SHORT"));
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 60), false, true, true, NOW - 30_000L, 1L, 0), ctx()).isEmpty());
        List<CityMonopolyRules.Sample> stale = dwell(4, 90);
        stale.replaceAll(s -> new CityMonopolyRules.Sample(s.tMillis() - 400_000L, s.lat(), s.lng(), s.hAcc()));
        assertTrue(claim(ev(A_LAT, 10.0, stale, false, true, true, NOW - 30_000L, 1L, 0), ctx()).contains("DWELL_TOO_FEW_SAMPLES"), "samples older than 300 s ignored");
        List<CityMonopolyRules.Sample> far = new ArrayList<>(dwell(4, 90));
        far.add(new CityMonopolyRules.Sample(NOW - 1_000L, A_LAT + 0.001, A_LNG, 8.0));
        List<String> r = claim(ev(A_LAT, 10.0, far, false, true, true, NOW - 30_000L, 1L, 0), ctx());
        assertTrue(r.contains("DWELL_OUTSIDE_SPREAD"));
    }

    @Test
    void claimRejectsTeleportSpeedAndSimulatedLocation() {
        List<CityMonopolyRules.Sample> jump = new ArrayList<>(dwell(3, 90));
        jump.add(new CityMonopolyRules.Sample(NOW - 500L, A_LAT + 0.0004, A_LNG, 8.0));
        CityMonopolyRules.ClaimVerdict v = CityMonopolyRules.evaluateClaim(C, ev(A_LAT, 10.0, jump, false, true, true, NOW - 30_000L, 1L, 0), ctx());
        assertTrue(v.reasons().contains("SPEED_IMPLAUSIBLE"));
        assertTrue(v.auditFlags().contains("TELEPORT_AUDIT"));
        CityMonopolyRules.ClaimVerdict sim = CityMonopolyRules.evaluateClaim(C, ev(A_LAT, 10.0, dwell(4, 90), true, true, true, NOW - 30_000L, 1L, 0), ctx());
        assertTrue(sim.reasons().contains("SIMULATED_LOCATION"));
        assertTrue(sim.auditFlags().contains("SPOOF"));
        assertEquals(1.0, sim.riskScore());
    }

    @Test
    void claimRequiresAppAttestVerification() {
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 90), false, false, true, NOW - 30_000L, 1L, 0), ctx()).contains("APP_ATTEST_REQUIRED"));
        CityMonopolyConfig off = CityMonopolyConfig.withOverrides(Map.of("city.monopoly.claim.require_app_attest", "false"));
        assertTrue(CityMonopolyRules.evaluateClaim(off, ev(A_LAT, 10.0, dwell(4, 90), false, false, true, NOW - 30_000L, 1L, 0), ctx()).ok());
    }

    @Test
    void claimPhotoMustBeInAppFreshCoLocatedOriginalAndNotRecaptured() {
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, false, NOW - 30_000L, 1L, 0), ctx()).contains("PHOTO_NOT_IN_APP_CAMERA"));
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, true, NOW - 121_000L, 1L, 0), ctx()).contains("PHOTO_TOO_OLD"));
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, true, null, 1L, 0), ctx()).contains("PHOTO_TOO_OLD"));
        CityMonopolyRules.Evidence moved = new CityMonopolyRules.Evidence(A_LAT, A_LNG, 10.0, dwell(4, 90), false, true, false, true, NOW - 30_000L,
                A_LAT + 0.0004, A_LNG, 1L, 0.0);
        assertTrue(claim(moved, ctx()).contains("PHOTO_LOCATION_MISMATCH"));
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, 1L, 0.7), ctx()).contains("PHOTO_SCREEN_RECAPTURE"));
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, null, 0), ctx()).contains("PHOTO_HASH_MISSING"));
        long h = 0x00FF00FF00FF00FFL;
        long eightBits = h ^ 0xFFL;
        long fiveBits = h ^ 0x1FL;
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, eightBits, 0), ctx(a -> set(a, 16, List.of(h)))).contains("PHOTO_REUSED"));
        assertFalse(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, h ^ 0x1FFL, 0), ctx(a -> set(a, 16, List.of(h)))).contains("PHOTO_REUSED"));
        assertTrue(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, h ^ 0xFL, 0), ctx(a -> set(a, 17, List.of(h)))).contains("PHOTO_COPIED"));
        assertFalse(claim(ev(A_LAT, 10.0, dwell(4, 90), false, true, true, NOW - 30_000L, fiveBits, 0), ctx(a -> set(a, 17, List.of(h)))).contains("PHOTO_COPIED"));
    }

    @Test
    void claimAccountGatesHoursAndDailyLimits() {
        assertTrue(claim(good(), ctx(a -> set(a, 5, 2L))).contains("ACCOUNT_TOO_NEW"));
        assertTrue(claim(good(), ctx(a -> set(a, 6, 2))).contains("ACCOUNT_LEVEL_TOO_LOW"));
        assertTrue(claim(good(), ctx(a -> set(a, 7, false))).contains("PHONE_UNBOUND"));
        assertTrue(claim(good(), ctx(a -> set(a, 3, LocalTime.of(5, 59)))).contains("OUTSIDE_HOURS"));
        assertTrue(claim(good(), ctx(a -> set(a, 3, LocalTime.of(23, 0)))).contains("OUTSIDE_HOURS"));
        assertTrue(claim(good(), ctx(a -> set(a, 3, LocalTime.of(22, 59)))).isEmpty());
        assertTrue(claim(good(), ctx(a -> set(a, 8, 3))).contains("DAILY_CLAIM_LIMIT"));
        assertTrue(claim(good(), ctx(a -> set(a, 9, 3))).contains("PROPERTY_FAILED_LIMIT"));
        assertTrue(claim(good(), ctx(a -> set(a, 10, true))).contains("RECLAIM_COOLDOWN"));
        assertTrue(claim(good(), ctx(a -> set(a, 13, true))).contains("STAFF_OWN_STORE"));
        assertTrue(claim(good(), ctx(a -> set(a, 14, true))).contains("SAFETY_BLOCKED"));
        assertTrue(claim(good(), ctx(a -> set(a, 15, false))).contains("FOG_NOT_LIT"));
        assertTrue(claim(good(), ctx(a -> set(a, 18, true))).contains("CITY_BANNED"));
    }

    @Test
    void holdingLimitNinePlusExploreBonusesMaxTwelve() {
        assertEquals(9, CityMonopolyRules.holdingLimit(C, 1));
        assertEquals(10, CityMonopolyRules.holdingLimit(C, 15));
        assertEquals(11, CityMonopolyRules.holdingLimit(C, 30));
        assertEquals(12, CityMonopolyRules.holdingLimit(C, 45));
        assertEquals(12, CityMonopolyRules.holdingLimit(C, 50));
        assertTrue(claim(good(), ctx(a -> set(a, 11, 9))).contains("HOLDING_LIMIT"));
        assertTrue(claim(good(), ctx(a -> set(set(a, 11, 9), 12, 15))).isEmpty());
    }

    @Test
    void humanReviewOnRiskOrFivePercentSample() {
        assertTrue(CityMonopolyRules.needsHumanReview(C, 0.6, "x"));
        int sampled = 0;
        for (int i = 0; i < 20_000; i++) if (CityMonopolyRules.needsHumanReview(C, 0.0, "req-" + i)) sampled++;
        assertEquals(0.05, sampled / 20_000.0, 0.01);
    }

    // ---------------------------------------------------------------- protection (§2.6)

    @Test
    void protectionWindowsAndShield() {
        assertEquals(24, CityMonopolyRules.protectionHours(C, CityMonopolyRules.ProtectionEvent.CLAIM, false));
        assertEquals(12, CityMonopolyRules.protectionHours(C, CityMonopolyRules.ProtectionEvent.CLAIM, true), "finale week");
        assertEquals(12, CityMonopolyRules.protectionHours(C, CityMonopolyRules.ProtectionEvent.DEFENSE, false));
        assertEquals(24, CityMonopolyRules.protectionHours(C, CityMonopolyRules.ProtectionEvent.TAKEOVER, false));
        long h = 3_600_000L;
        assertEquals(NOW + 36 * h, CityMonopolyRules.shieldUntil(C, NOW, NOW + 24 * h, null));
        assertEquals(NOW + 48 * h, CityMonopolyRules.shieldUntil(C, NOW, NOW + 40 * h, null), "never beyond 48 h");
        assertEquals(NOW + 12 * h, CityMonopolyRules.shieldUntil(C, NOW, null, null));
        assertNull(CityMonopolyRules.shieldUntil(C, NOW, null, NOW - 6 * 24 * h), "once per property per 7 days");
        assertNotNull(CityMonopolyRules.shieldUntil(C, NOW, null, NOW - 7 * 24 * h));
    }

    // ---------------------------------------------------------------- challenge (§2.7)

    @Test
    void challengeFeeMinimumHalfInvestedFinaleAndRevengeDiscounts() {
        assertEquals(30, CityMonopolyRules.challengeFee(C, CityMonopolyRules.investedAt(C, 1, 0), false, false));
        assertEquals(90, CityMonopolyRules.challengeFee(C, CityMonopolyRules.investedAt(C, 2, 1), false, false));
        assertEquals(700, CityMonopolyRules.challengeFee(C, CityMonopolyRules.investedAt(C, 4, 3), false, false));
        assertEquals(525, CityMonopolyRules.challengeFee(C, 1400, true, false));
        assertEquals(350, CityMonopolyRules.challengeFee(C, 1400, false, true));
    }

    @Test
    void challengeDcTable() {
        assertEquals(10, CityMonopolyRules.challengeDc(C, 0, false, CityMonopolyRules.SetState.NONE, false));
        assertEquals(8, CityMonopolyRules.challengeDc(C, 0, false, CityMonopolyRules.SetState.NONE, true), "weathered Lv0 corner");
        assertEquals(17, CityMonopolyRules.challengeDc(C, 2, true, CityMonopolyRules.SetState.MAJORITY, false));
        assertEquals(20, CityMonopolyRules.challengeDc(C, 3, true, CityMonopolyRules.SetState.MONOPOLY, false));
        assertEquals(20, CityMonopolyRules.challengeDc(C, 3, true, CityMonopolyRules.SetState.MONOPOLY, false), "capped at 20");
    }

    @Test
    void challengeModsCappedAtSix() {
        CityMonopolyRules.Mods none = CityMonopolyRules.challengeMods(C, new CityMonopolyRules.ModInput(CityMonopolyRules.TaskResult.NONE, 1, 1, 0, 0, 1));
        assertEquals(0, none.total());
        CityMonopolyRules.Mods pass = CityMonopolyRules.challengeMods(C, new CityMonopolyRules.ModInput(CityMonopolyRules.TaskResult.PASS, 15, 1, 0, 1, 1));
        assertEquals(1 + 1 + 1, pass.total());
        CityMonopolyRules.Mods max = CityMonopolyRules.challengeMods(C, new CityMonopolyRules.ModInput(CityMonopolyRules.TaskResult.PERFECT, 35, 20, 1.2, 5, 30));
        assertEquals(Map.of("task", 2, "insight", 2, "warmup", 1, "assist", 3), max.parts());
        assertEquals(6, max.total(), "mod cap 6 = modCap");
        assertEquals(0, CityMonopolyRules.challengeMods(C, new CityMonopolyRules.ModInput(CityMonopolyRules.TaskResult.NONE, 1, 19, 5, 0, 1)).parts().get("warmup"));
        assertEquals(2, CityMonopolyRules.challengeMods(C, new CityMonopolyRules.ModInput(CityMonopolyRules.TaskResult.NONE, 1, 1, 0, 5, 29)).parts().get("assist"));
    }

    @Test
    void d20PlusModAgainstDcWithNaturalRules() {
        assertTrue(CityMonopolyRules.challengerWins(C, 20, 0, 20));
        assertTrue(CityMonopolyRules.challengerWins(C, 20, -10, 99), "nat 20 always wins");
        assertFalse(CityMonopolyRules.challengerWins(C, 1, 6, 2), "nat 1 always loses");
        assertTrue(CityMonopolyRules.challengerWins(C, 10, 0, 10), "≥ wins (DC encodes the defender advantage)");
        assertFalse(CityMonopolyRules.challengerWins(C, 9, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> CityMonopolyRules.challengerWins(C, 21, 0, 10));
    }

    @Test
    void winProbabilityTableMatchesSpec() {
        int[] dcs = {10, 12, 14, 16, 18, 20};
        int[][] pct = {{55, 60, 65, 70, 75, 80, 85}, {45, 50, 55, 60, 65, 70, 75}, {35, 40, 45, 50, 55, 60, 65},
                {25, 30, 35, 40, 45, 50, 55}, {15, 20, 25, 30, 35, 40, 45}, {5, 10, 15, 20, 25, 30, 35}};
        for (int i = 0; i < dcs.length; i++) {
            for (int mod = 0; mod <= 6; mod++) {
                assertEquals(pct[i][mod], Math.round(CityMonopolyRules.winProbability(C, dcs[i], mod) * 100), "DC " + dcs[i] + " mod " + mod);
            }
        }
        assertEquals(0.80, CityMonopolyRules.winProbability(C, 8, 3), 1e-9, "weathered Lv0 corner vs +3");
    }

    @Test
    void feeSplitCompensationBurnAndTaskRefund() {
        assertEquals(new CityMonopolyRules.FeeSplit(100, 40, 60, 0), CityMonopolyRules.feeSplit(C, 100, true, false));
        assertEquals(new CityMonopolyRules.FeeSplit(100, 30, 70, 0), CityMonopolyRules.feeSplit(C, 100, false, false));
        assertEquals(new CityMonopolyRules.FeeSplit(90, 0, 45, 45), CityMonopolyRules.feeSplit(C, 90, false, true));
        assertEquals(0, CityMonopolyRules.levelAfterTakeover(C, 0));
        assertEquals(2, CityMonopolyRules.levelAfterTakeover(C, 3));
    }

    @Test
    void luckRerollsPerDay() {
        assertEquals(0, CityMonopolyRules.rerollsPerDay(C, 9));
        assertEquals(1, CityMonopolyRules.rerollsPerDay(C, 10));
        assertEquals(2, CityMonopolyRules.rerollsPerDay(C, 25));
        assertEquals(2, CityMonopolyRules.rerollsPerDay(C, 50));
    }

    // ---------------------------------------------------------------- decay & upkeep (§2.8, §4)

    @Test
    void decayAtSevenAndTwentyOneDays() {
        assertEquals(CityMonopolyRules.DecayState.OK, CityMonopolyRules.decay(C, 6, 1).state());
        assertEquals(CityMonopolyRules.DecayState.WEATHERED, CityMonopolyRules.decay(C, 7, 1).state());
        assertEquals(CityMonopolyRules.DecayState.WEATHERED, CityMonopolyRules.decay(C, 13, 1).state());
        CityMonopolyRules.Decay d14 = CityMonopolyRules.decay(C, 14, 1);
        assertEquals(CityMonopolyRules.DecayState.DECAYING, d14.state());
        assertEquals(1, d14.levelDrops());
        assertEquals(1, CityMonopolyRules.decay(C, 16, 1).levelDrops());
        assertEquals(2, CityMonopolyRules.decay(C, 17, 1).levelDrops(), "then −1 every 3 days");
        assertEquals(3, CityMonopolyRules.decay(C, 20, 1).levelDrops());
        assertEquals(CityMonopolyRules.DecayState.RELEASED, CityMonopolyRules.decay(C, 21, 1).state());
        assertTrue(CityMonopolyRules.decay(C, 5, 1).reminderDay());
        assertTrue(CityMonopolyRules.decay(C, 12, 1).reminderDay());
        assertTrue(CityMonopolyRules.decay(C, 19, 1).reminderDay());
        assertFalse(CityMonopolyRules.decay(C, 6, 1).reminderDay());
    }

    @Test
    void loreGraceDaysShiftDecayThresholds() {
        assertEquals(CityMonopolyRules.DecayState.OK, CityMonopolyRules.decay(C, 7, 20).state());
        assertEquals(CityMonopolyRules.DecayState.WEATHERED, CityMonopolyRules.decay(C, 8, 20).state());
        assertEquals(CityMonopolyRules.DecayState.DECAYING, CityMonopolyRules.decay(C, 22, 40).state());
        assertEquals(CityMonopolyRules.DecayState.RELEASED, CityMonopolyRules.decay(C, 23, 40).state());
    }

    @Test
    void upkeepTwoPercentWithLoreDiscountAndMinimum() {
        assertEquals(1, CityMonopolyRules.upkeep(C, 60, 1), "1.2 → 1");
        assertEquals(28, CityMonopolyRules.upkeep(C, 1400, 1));
        assertEquals(27, CityMonopolyRules.upkeep(C, 1400, 25), "−5%");
        assertEquals(25, CityMonopolyRules.upkeep(C, 1400, 45), "−10%");
        assertEquals(1, CityMonopolyRules.upkeep(C, 10, 1), "min 1 per property");
        assertEquals(0, CityMonopolyRules.upkeep(C, 0, 1));
    }

    @Test
    void chanceTableIsD6Mapping() {
        assertEquals("PHOTO", CityMonopolyRules.chanceTask(C, 1));
        assertEquals("CITY_QUIZ", CityMonopolyRules.chanceTask(C, 4));
        assertEquals("FIND", CityMonopolyRules.chanceTask(C, 5));
        assertEquals("CHOOSE", CityMonopolyRules.chanceTask(C, 6));
        assertEquals(15, C.integer("city.monopoly.chance.reward"));
        assertEquals(2, C.integer("city.monopoly.chance.per_day"));
    }

    // ---------------------------------------------------------------- seasons & leaderboards (§2.10)

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final ZonedDateTime EPOCH = ZonedDateTime.of(2026, 11, 9, 10, 0, 0, 0, SH);

    @Test
    void seasonsAre28DaysMondayToFourthSundayFreeze() {
        CityMonopolyRules.Season s = CityMonopolyRules.seasonAt(C, EPOCH, ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, SH));
        assertEquals("s2026w46", s.seasonId());
        assertEquals(EPOCH, s.startsAt());
        assertEquals(ZonedDateTime.of(2026, 12, 6, 22, 0, 0, 0, SH), s.freezesAt());
        assertEquals(ZonedDateTime.of(2026, 12, 7, 10, 0, 0, 0, SH), s.endsAt());
        CityMonopolyRules.Season next = CityMonopolyRules.seasonAt(C, EPOCH, ZonedDateTime.of(2026, 12, 7, 10, 0, 0, 0, SH));
        assertEquals("s2026w50", next.seasonId());
        CityMonopolyRules.Season before = CityMonopolyRules.seasonAt(C, EPOCH, ZonedDateTime.of(2026, 12, 7, 9, 59, 0, 0, SH));
        assertEquals("s2026w46", before.seasonId());
        CityMonopolyRules.Season y = CityMonopolyRules.seasonAt(C, EPOCH, ZonedDateTime.of(2027, 1, 4, 10, 0, 0, 0, SH));
        assertEquals("s2027w01", y.seasonId());
    }

    @Test
    void seasonPhasesOpeningRegularFinaleBreak() {
        CityMonopolyRules.Season s = CityMonopolyRules.seasonAt(C, EPOCH, EPOCH);
        assertEquals(CityMonopolyRules.Phase.OPENING, CityMonopolyRules.phase(C, s, EPOCH.plusHours(1)));
        assertEquals(CityMonopolyRules.Phase.OPENING, CityMonopolyRules.phase(C, s, EPOCH.plusDays(2).plusHours(23)));
        assertEquals(CityMonopolyRules.Phase.REGULAR, CityMonopolyRules.phase(C, s, EPOCH.plusDays(3)));
        assertEquals(CityMonopolyRules.Phase.FINALE, CityMonopolyRules.phase(C, s, EPOCH.plusDays(21)));
        assertEquals(CityMonopolyRules.Phase.BREAK, CityMonopolyRules.phase(C, s, ZonedDateTime.of(2026, 12, 6, 23, 0, 0, 0, SH)));
    }

    @Test
    void seasonScoreTeamAndClubAggregation() {
        assertEquals(500 + 2 * 20 + 3 * 30 + 4 * 10 + 10 * 1 + 10 * 3.0,
                CityMonopolyRules.soloScore(C, 500, 2, 3, 4, Map.of(1, 10, 4, 10)), 1e-9);
        List<Double> members = List.of(100.0, 90.0, 80.0, 70.0, 60.0, 50.0);
        assertEquals(340 + 5 * 12, CityMonopolyRules.teamScore(C, members, 12), 1e-9, "top 4 + 5 × dice event");
        List<Double> club = new ArrayList<>();
        for (int i = 0; i < 25; i++) club.add(10.0);
        assertEquals(200 + 50 * 3, CityMonopolyRules.clubScore(C, club, 3), 1e-9, "top 20 + 50 per district");
    }

    @Test
    void leaderboardWindowTopTenPlusTwoAroundMe() {
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 38, 39, 40, 41, 42), CityMonopolyRules.leaderboardWindow(C, 100, 40));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), CityMonopolyRules.leaderboardWindow(C, 100, 9));
        assertEquals(List.of(0, 1, 2), CityMonopolyRules.leaderboardWindow(C, 3, -1));
    }

    @Test
    void soloSeasonRewardsTiers() {
        assertEquals(3000, CityMonopolyRules.soloReward(C, 1, 1000, 5).get("xp"));
        assertEquals("SEASON_LORD", CityMonopolyRules.soloReward(C, 1, 1000, 5).get("badge"));
        assertEquals(2000, CityMonopolyRules.soloReward(C, 5, 1000, 5).get("xp"), "top 1% beats top-10 XP");
        assertEquals(1500, CityMonopolyRules.soloReward(C, 5, 100, 5).get("xp"));
        assertEquals(800, CityMonopolyRules.soloReward(C, 80, 1000, 5).get("xp"));
        assertEquals(300, CityMonopolyRules.soloReward(C, 400, 1000, 5).get("xp"));
        assertEquals(200, CityMonopolyRules.soloReward(C, 900, 1000, 1).get("xp"));
        assertNull(CityMonopolyRules.soloReward(C, 900, 1000, 0));
    }
}
