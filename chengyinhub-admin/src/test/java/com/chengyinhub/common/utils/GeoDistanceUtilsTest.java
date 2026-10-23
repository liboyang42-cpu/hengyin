package com.chengyinhub.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O-02:后端唯一的球面距离实现。钉住单位(米)、半径(6371000)、参数顺序(纬度在前)、
 * NaN 传播(不得被吞成 0 让围栏放行)、对跖点不出 NaN,以及与被替换掉的旧实现逐位等价。
 */
class GeoDistanceUtilsTest
{
    @Test
    void samePointIsZero()
    {
        assertEquals(0d, GeoDistanceUtils.haversineMeters(31.2304, 121.4737, 31.2304, 121.4737), 0d);
    }

    @Test
    void oneThousandthDegreeLatitudeIsAbout111Meters()
    {
        double d = GeoDistanceUtils.haversineMeters(31.230, 121.470, 31.231, 121.470);
        assertEquals(111.19d, d, 0.01d);
    }

    @Test
    void knownCityDistanceAndSymmetry()
    {
        // 上海市中心两点:约 1.88 km(两种参数顺序结果一致)
        double ab = GeoDistanceUtils.haversineMeters(31.2304, 121.4737, 31.2400, 121.4900);
        double ba = GeoDistanceUtils.haversineMeters(31.2400, 121.4900, 31.2304, 121.4737);
        assertEquals(ab, ba, 1e-9);
        assertTrue(ab > 1800d && ab < 1950d, "实际 " + ab);
    }

    @Test
    void latitudeComesFirst()
    {
        // 60°N 上经度差 1° 约 55.6 km(赤道上的一半);参数若把经纬对调就不是这个数
        double lngDegreeAt60 = GeoDistanceUtils.haversineMeters(60d, 10d, 60d, 11d);
        assertEquals(55_597d, lngDegreeAt60, 50d);
    }

    @Test
    void nonFiniteInputPropagatesAsNaN()
    {
        assertTrue(Double.isNaN(GeoDistanceUtils.haversineMeters(Double.NaN, 121d, 31d, 121d)));
        assertTrue(Double.isNaN(GeoDistanceUtils.haversineMeters(31d, Double.POSITIVE_INFINITY, 31d, 121d)));
    }

    @Test
    void antipodalPointsAreFiniteHalfCircumference()
    {
        double d = GeoDistanceUtils.haversineMeters(0d, 0d, 0d, 180d);
        assertEquals(Math.PI * GeoDistanceUtils.EARTH_RADIUS_METERS, d, 1e-6);
    }

    @Test
    void matchesReplacedAtan2ImplementationWithinMicrometers()
    {
        double[][] cases = {
                { 31.2304, 121.4737, 31.2310, 121.4740 },
                { 39.9042, 116.4074, 31.2304, 121.4737 },
                { -33.8688, 151.2093, 51.5074, -0.1278 },
                { 22.5431, 114.0579, 22.5432, 114.0579 },
        };
        for (double[] c : cases) {
            assertEquals(legacyAtan2(c[0], c[1], c[2], c[3]), GeoDistanceUtils.haversineMeters(c[0], c[1], c[2], c[3]),
                    1e-6);
        }
    }

    @Test
    void distanceCalculatorKeepsKmRoundingAndZeroOnBadInputContract()
    {
        DistanceCalculatorUtils utils = new DistanceCalculatorUtils();
        // 参数经度在前、单位公里、两位小数
        assertEquals(0.11d, utils.calculateDistance("121.470", "31.230", "121.470", "31.231"), 0d);
        assertEquals(0d, utils.calculateDistance(null, "31.23", "121.47", "31.23"), 0d);
        assertEquals(0d, utils.calculateDistance("abc", "31.23", "121.47", "31.23"), 0d);
    }

    /** 被收敛掉的 ApiPlayProgressController#haversine 原文(atan2 形式)。 */
    private static double legacyAtan2(double lat1, double lng1, double lat2, double lng2)
    {
        double r = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
