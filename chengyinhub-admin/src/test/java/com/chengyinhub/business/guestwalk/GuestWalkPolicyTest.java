package com.chengyinhub.business.guestwalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.locationtrust.LocationTrustConfig;
import com.chengyinhub.business.locationtrust.LocationTrustPolicy;
import com.chengyinhub.business.support.RoamGeohashCover;

class GuestWalkPolicyTest {
    static final String INSTALL = "0f8fad5b-d9cb-469f-a165-70867728950e";
    static final String WALK = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    static final double LAT = 31.2304, LNG = 121.4737;
    static final long NOW = 1_763_200_000_000L;
    static final LocationTrustConfig CFG = LocationTrustConfig.defaults();

    /** A walk north at {@code mps}, one fix every 10 s, ending {@code endsAgoMs} before NOW. */
    static String walk(int fixes, double mps, long endsAgoMs, IntFunction<String> extra) {
        long end = NOW - endsAgoMs;
        long start = end - (fixes - 1) * 10_000L;
        StringBuilder f = new StringBuilder();
        LinkedHashSet<String> cells = new LinkedHashSet<>();
        for (int i = 0; i < fixes; i++) {
            double lat = LAT + (i * 10 * mps) / 111_320d;
            cells.add(RoamGeohashCover.encode(lat, LNG, 7));
            if (i > 0) f.append(',');
            f.append(String.format(Locale.ROOT, "{\"lat\":%.7f,\"lng\":%.7f,\"accuracy\":8,\"timestamp\":%d%s}",
                    lat, LNG, start + i * 10_000L, extra == null ? "" : extra.apply(i)));
        }
        List<String> quoted = new ArrayList<>();
        for (String c : cells) quoted.add('"' + c + '"');
        return "{\"installId\":\"" + INSTALL + "\",\"walkId\":\"" + WALK + "\",\"startedAt\":" + start
                + ",\"finishedAt\":" + end + ",\"distanceM\":" + (int) ((fixes - 1) * 10 * mps)
                + ",\"cells\":[" + String.join(",", quoted) + "],\"fixes\":[" + f + "]}";
    }

    static String walk(int fixes, double mps, long endsAgoMs) {
        return walk(fixes, mps, endsAgoMs, null);
    }

    @Test
    void honestWalkCreditsItsCellsAndTheFirstWalkReward() {
        GuestWalkPolicy.Upload upload = GuestWalkPolicy.parse(walk(121, 1.4, 60_000L));
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(upload, NOW, CFG);
        assertFalse(out.withheld());
        assertEquals(upload.cells(), out.credited());
        assertEquals(0, out.droppedFixes());
        assertEquals(Math.min(200, upload.cells().size()), out.xp());
        assertTrue(out.distanceM() > 1500 && out.distanceM() <= upload.distanceM());
        assertEquals(121, out.acceptedTrack().size());
    }

    @Test
    void walkOlderThan24HoursIsWithheld() {
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(
                GuestWalkPolicy.parse(walk(30, 1.4, 25L * 3_600_000L)), NOW, CFG);
        assertTrue(out.withheld());
        assertEquals(GuestWalkPolicy.OUT_OF_WINDOW, out.code());
        assertEquals(0, out.xp());
    }

    @Test
    void futureFixIsOutOfWindow() {
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(
                GuestWalkPolicy.parse(walk(30, 1.4, -10L * 60_000L)), NOW, CFG);
        assertTrue(out.withheld());
        assertEquals(GuestWalkPolicy.OUT_OF_WINDOW, out.code());
    }

    @Test
    void simulatedFixWithholdsTheWholeWalk() {
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(GuestWalkPolicy.parse(
                walk(60, 1.4, 60_000L, i -> i == 30 ? ",\"simulated\":1" : ",\"simulated\":0")), NOW, CFG);
        assertTrue(out.withheld());
        assertEquals(LocationTrustPolicy.SIMULATED, out.code());
        assertTrue(out.credited().isEmpty());
    }

    @Test
    void teleportingTrackKeepsOnlyCellsNearAcceptedFixes() {
        // 300 m/s: every fix after the first is an impossible jump, so only the start fix anchors cells.
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(GuestWalkPolicy.parse(walk(40, 300, 60_000L)), NOW, CFG);
        assertFalse(out.withheld());
        assertTrue(out.reasons().contains(LocationTrustPolicy.SPEED_IMPOSSIBLE));
        assertEquals(1, out.acceptedTrack().size());
        assertTrue(out.droppedFarFromTrack() > 0);
        assertTrue(out.credited().size() < 10);
    }

    @Test
    void cellsFarFromTheTrackAreNotCredited() {
        String body = walk(20, 1.4, 60_000L).replace("\"cells\":[", "\"cells\":[\"" + RoamGeohashCover.encode(LAT + 0.2, LNG, 7) + "\",");
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(GuestWalkPolicy.parse(body), NOW, CFG);
        assertEquals(1, out.droppedFarFromTrack());
        assertFalse(out.credited().contains(RoamGeohashCover.encode(LAT + 0.2, LNG, 7)));
    }

    @Test
    void poorAccuracyFixesDoNotAnchorCells() {
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(GuestWalkPolicy.parse(
                walk(20, 1.4, 60_000L).replace("\"accuracy\":8", "\"accuracy\":500")), NOW, CFG);
        assertTrue(out.withheld());
        assertEquals(GuestWalkPolicy.NO_TRACK, out.code());
    }

    @Test
    void budgetLimitsCellsForAShortWalk() {
        // A 10 s walk may credit 30 base + ceil(10 s x 15 m/s / 100 m) = 32 cells, however many lie near it.
        LinkedHashSet<String> near = new LinkedHashSet<>();
        for (int i = -40; i <= 40; i++) {
            for (int j = -40; j <= 40; j++) {
                String code = RoamGeohashCover.encode(LAT + i * 0.0002, LNG + j * 0.0002, 7);
                double[] c = com.chengyinhub.business.locationtrust.Geohash.decodeCenter(code);
                if (com.chengyinhub.common.utils.GeoDistanceUtils.haversineMeters(c[0], c[1], LAT, LNG) <= 550) near.add(code);
            }
        }
        assertTrue(near.size() > 32);
        StringBuilder cells = new StringBuilder();
        for (String c : near) cells.append(cells.length() == 0 ? "" : ",").append('"').append(c).append('"');
        String body = walk(2, 1.0, 60_000L).replaceFirst("\"cells\":\\[[^\\]]*\\]", "\"cells\":[" + cells + "]");
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(GuestWalkPolicy.parse(body), NOW, CFG);
        assertEquals(32, out.credited().size());
        assertEquals(near.size() - 32, out.droppedOverBudget());
        assertEquals(0, out.droppedFarFromTrack());
    }

    @Test
    void strictContractRejectsUnknownKeysBadIdsAndOversizedLists() {
        assertThrows(GuestWalkPolicy.Rejected.class, () -> GuestWalkPolicy.parse(walk(3, 1, 0).replace("\"walkId\"", "\"phone\":\"1\",\"walkId\"")));
        assertThrows(GuestWalkPolicy.Rejected.class, () -> GuestWalkPolicy.parse(walk(3, 1, 0).replace(INSTALL, "not-a-uuid")));
        assertThrows(GuestWalkPolicy.Rejected.class, () -> GuestWalkPolicy.parse(walk(3, 1, 0).replace("\"accuracy\":8", "\"accuracy\":8,\"name\":\"x\"")));
        assertThrows(GuestWalkPolicy.Rejected.class, () -> GuestWalkPolicy.parse(walk(3, 1, 0).replace("\"cells\":[", "\"cells\":[\"abc\",")));
        assertThrows(GuestWalkPolicy.Rejected.class, () -> GuestWalkPolicy.parse(""));
        assertThrows(GuestWalkPolicy.Rejected.class, () -> GuestWalkPolicy.parse("[]"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= GuestWalkPolicy.MAX_CELLS; i++) many.append(i == 0 ? "" : ",").append("\"wtw3s8n\"");
        assertThrows(GuestWalkPolicy.Rejected.class, () -> GuestWalkPolicy.parse(
                walk(3, 1, 0).replaceFirst("\"cells\":\\[[^\\]]*\\]", "\"cells\":[" + many + "]")));
    }

    @Test
    void shadowModeCreditsWithoutDroppingFixes() {
        LocationTrustConfig shadow = LocationTrustConfig.read(k -> k.equals("location.trust.mode") ? "SHADOW" : null);
        GuestWalkPolicy.Outcome out = GuestWalkPolicy.evaluate(GuestWalkPolicy.parse(walk(40, 300, 60_000L)), NOW, shadow);
        assertFalse(out.withheld());
        assertEquals(40, out.acceptedTrack().size());
    }
}
