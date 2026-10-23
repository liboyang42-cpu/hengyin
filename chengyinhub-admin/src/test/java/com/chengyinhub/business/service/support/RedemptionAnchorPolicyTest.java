package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedemptionAnchorPolicyTest {

    private static final BigDecimal LAT = new BigDecimal("31.2304000");
    private static final BigDecimal LNG = new BigDecimal("121.4737000");

    @Test
    void radiusAndCoordinateBounds() {
        assertFalse(RedemptionAnchorPolicy.validRadius(29));
        assertTrue(RedemptionAnchorPolicy.validRadius(30));
        assertTrue(RedemptionAnchorPolicy.validRadius(500));
        assertFalse(RedemptionAnchorPolicy.validRadius(501));
        assertFalse(RedemptionAnchorPolicy.validRadius(null));
        assertFalse(RedemptionAnchorPolicy.validCoordinate(BigDecimal.ZERO, BigDecimal.ZERO));
        assertFalse(RedemptionAnchorPolicy.validCoordinate(new BigDecimal("91"), LNG));
        assertTrue(RedemptionAnchorPolicy.validCoordinate(LAT, LNG));
    }

    @Test
    void bssidIsNormalizedAndOnlyHashStored() {
        assertEquals("aa:bb:cc:dd:ee:ff", RedemptionAnchorPolicy.normalizeBssid(" AA-BB-CC-DD-EE-FF "));
        assertNull(RedemptionAnchorPolicy.normalizeBssid("aa:bb:cc"));
        List<String> hashes = RedemptionAnchorPolicy.hashAll(Arrays.asList("AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff"));
        assertEquals(1, hashes.size());
        assertEquals(64, hashes.get(0).length());
        assertFalse(hashes.get(0).contains(":"));
        assertNull(RedemptionAnchorPolicy.hashAll(Collections.singletonList("not-a-bssid")));
    }

    @Test
    void tooManyWifiRejected() {
        List<String> nine = Arrays.asList("00:00:00:00:00:01", "00:00:00:00:00:02", "00:00:00:00:00:03",
                "00:00:00:00:00:04", "00:00:00:00:00:05", "00:00:00:00:00:06", "00:00:00:00:00:07",
                "00:00:00:00:00:08", "00:00:00:00:00:09");
        assertNull(RedemptionAnchorPolicy.hashAll(nine));
    }

    @Test
    void locationVerdict() {
        // ~111 m 北
        BigDecimal near = LAT.add(new BigDecimal("0.0010000"));
        assertEquals(RedemptionAnchorPolicy.OUT_OF_RANGE,
                RedemptionAnchorPolicy.locationVerdict(LAT, LNG, 100, near, LNG, null));
        assertEquals(RedemptionAnchorPolicy.IN_RANGE,
                RedemptionAnchorPolicy.locationVerdict(LAT, LNG, 100, near, LNG, 20d));
        assertEquals(RedemptionAnchorPolicy.IN_RANGE,
                RedemptionAnchorPolicy.locationVerdict(LAT, LNG, 120, near, LNG, null));
        assertEquals(RedemptionAnchorPolicy.LOCATION_UNKNOWN,
                RedemptionAnchorPolicy.locationVerdict(LAT, LNG, 100, near, LNG, 150d));
        assertEquals(RedemptionAnchorPolicy.LOCATION_UNKNOWN,
                RedemptionAnchorPolicy.locationVerdict(LAT, LNG, 100, null, LNG, null));
    }

    @Test
    void wifiVerdict() {
        List<String> configured = RedemptionAnchorPolicy.hashAll(Collections.singletonList("aa:bb:cc:dd:ee:ff"));
        assertEquals(RedemptionAnchorPolicy.WIFI_MATCH, RedemptionAnchorPolicy.wifiVerdict(configured, "AA-BB-CC-DD-EE-FF"));
        assertEquals(RedemptionAnchorPolicy.WIFI_MISMATCH, RedemptionAnchorPolicy.wifiVerdict(configured, "aa:bb:cc:dd:ee:00"));
        assertEquals(RedemptionAnchorPolicy.WIFI_NOT_REPORTED, RedemptionAnchorPolicy.wifiVerdict(configured, null));
        assertEquals(RedemptionAnchorPolicy.WIFI_NOT_CONFIGURED,
                RedemptionAnchorPolicy.wifiVerdict(Collections.emptyList(), "aa:bb:cc:dd:ee:ff"));
    }
}
