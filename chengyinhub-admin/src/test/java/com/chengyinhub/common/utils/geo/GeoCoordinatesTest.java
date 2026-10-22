package com.chengyinhub.common.utils.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoCoordinatesTest {

    private static final double EPS = 1e-7; // ~1 cm

    @Test
    void gcj02IsConvertedBackToTheOriginalWgs84Point() {
        double wLng = 121.4737;
        double wLat = 31.2304;
        double[] gcj = GeoCoordinates.wgs84ToGcj02(wLng, wLat);
        // 上海 GCJ02 偏移是几百米量级:确认真的发生了偏移,而不是恒等变换骗过了断言
        assertTrue(Math.abs(gcj[0] - wLng) > 0.001 || Math.abs(gcj[1] - wLat) > 0.001);

        GeoCoordinates.Parsed parsed = GeoCoordinates.parse(gcj[0], gcj[1], CoordSys.GCJ02);
        assertTrue(parsed.clean());
        assertEquals(wLng, parsed.point().lng(), EPS);
        assertEquals(wLat, parsed.point().lat(), EPS);
    }

    @Test
    void bd09IsConvertedInOneBoundaryStepToWgs84() {
        double wLng = 116.3912;
        double wLat = 39.9075;
        double[] gcj = GeoCoordinates.wgs84ToGcj02(wLng, wLat);
        double[] bd = GeoCoordinates.gcj02ToBd09(gcj[0], gcj[1]);
        GeoCoordinates.Parsed parsed = GeoCoordinates.parse(Double.toString(bd[0]), Double.toString(bd[1]), CoordSys.BD09);
        assertTrue(parsed.clean());
        assertEquals(wLng, parsed.point().lng(), 1e-6);
        assertEquals(wLat, parsed.point().lat(), 1e-6);
    }

    @Test
    void wgs84InputIsNotShiftedAndOutsideChinaGcjEqualsWgs() {
        GeoCoordinates.Parsed wgs = GeoCoordinates.parse(121.47, 31.23, CoordSys.WGS84);
        assertEquals(121.47, wgs.point().lng(), 0.0);
        assertEquals(31.23, wgs.point().lat(), 0.0);

        GeoCoordinates.Parsed tokyo = GeoCoordinates.parse(139.6917, 35.6895, CoordSys.GCJ02);
        assertEquals(139.6917, tokyo.point().lng(), 0.0);
        assertEquals(35.6895, tokyo.point().lat(), 0.0);
    }

    @Test
    void dirtyStringsAreIsolatedWithAReasonAndNeverCoercedToZero() {
        assertDirty(GeoCoordinates.parse("", "31.2", CoordSys.GCJ02), GeoCoordinates.DirtyReason.MISSING);
        assertDirty(GeoCoordinates.parse(null, "31.2", CoordSys.GCJ02), GeoCoordinates.DirtyReason.MISSING);
        assertDirty(GeoCoordinates.parse("121.4,", "31.2", CoordSys.GCJ02), GeoCoordinates.DirtyReason.NOT_NUMBER);
        assertDirty(GeoCoordinates.parse("东经121", "31.2", CoordSys.GCJ02), GeoCoordinates.DirtyReason.NOT_NUMBER);
        assertDirty(GeoCoordinates.parse("NaN", "31.2", CoordSys.GCJ02), GeoCoordinates.DirtyReason.NON_FINITE);
        assertDirty(GeoCoordinates.parse("0", "0", CoordSys.GCJ02), GeoCoordinates.DirtyReason.ZERO);
        assertDirty(GeoCoordinates.parse("121.47", "0.0", CoordSys.GCJ02), GeoCoordinates.DirtyReason.ZERO);
        assertDirty(GeoCoordinates.parse("31.23", "121.47", CoordSys.GCJ02), GeoCoordinates.DirtyReason.SWAPPED_SUSPECT);
        assertDirty(GeoCoordinates.parse("200", "31.2", CoordSys.GCJ02), GeoCoordinates.DirtyReason.OUT_OF_RANGE);
        assertDirty(GeoCoordinates.parse("121.4", "-95", CoordSys.GCJ02), GeoCoordinates.DirtyReason.OUT_OF_RANGE);
        assertDirty(GeoCoordinates.parse("-33.86", "151.21", CoordSys.WGS84), GeoCoordinates.DirtyReason.SWAPPED_SUSPECT);
        assertDirty(GeoCoordinates.parse("121.4", "-190", CoordSys.GCJ02), GeoCoordinates.DirtyReason.OUT_OF_RANGE);
    }

    @Test
    void coordSysIsParsedStrictly() {
        assertEquals(CoordSys.GCJ02, CoordSys.parse("gcj02"));
        assertEquals(CoordSys.BD09, CoordSys.parse("BD09LL"));
        assertEquals(CoordSys.WGS84, CoordSys.parse(" wgs-84 "));
        assertNull(CoordSys.parse(""));
        assertNull(CoordSys.parse("mercator"));
    }

    private static void assertDirty(GeoCoordinates.Parsed parsed, GeoCoordinates.DirtyReason reason) {
        assertFalse(parsed.clean());
        assertNull(parsed.point());
        assertEquals(reason, parsed.dirtyReason());
    }
}
