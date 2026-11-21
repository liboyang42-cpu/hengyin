package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1b SEC-15:发给陌生人的坐标网格在任何纬度都不小于 500 m。 */
class RoamPresenceBlurTest {

    @Test
    void cell_is_at_least_500_m_in_both_directions_at_every_latitude() {
        for (int deg = 0; deg <= 75; deg++) {
            double cos = Math.cos(Math.toRadians(deg));
            BigDecimal lngStep = RoamPresenceBlur.stepFor(cos);
            BigDecimal latStep = RoamPresenceBlur.stepFor(1d);
            assertTrue(RoamPresenceBlur.cellWidthMeters(lngStep, cos) >= RoamPresenceBlur.MIN_CELL_M,
                    "经向格宽在 " + deg + "° 不足 500 m");
            assertTrue(RoamPresenceBlur.cellWidthMeters(latStep, 1d) >= RoamPresenceBlur.MIN_CELL_M);
        }
    }

    @Test
    void stored_100_m_points_inside_one_cell_share_one_public_point() {
        BigDecimal lat = new BigDecimal("31.230");
        assertEquals(new BigDecimal("31.23"), RoamPresenceBlur.latitude(lat));
        assertEquals(new BigDecimal("121.47"), RoamPresenceBlur.longitude(lat, new BigDecimal("121.474")));
        assertEquals(new BigDecimal("121.47"), RoamPresenceBlur.longitude(lat, new BigDecimal("121.466")));
        assertEquals(new BigDecimal("31.23"), RoamPresenceBlur.latitude(new BigDecimal("31.226")));
    }

    @Test
    void far_north_uses_a_wider_longitude_cell() {
        BigDecimal lat = new BigDecimal("65.000");
        // 0.01° 经度在 65° ≈ 470 m,不够 500 m ⇒ 改用 0.02°
        assertEquals(new BigDecimal("0.02"), RoamPresenceBlur.stepFor(Math.cos(Math.toRadians(65))));
        assertEquals(new BigDecimal("25.02"), RoamPresenceBlur.longitude(lat, new BigDecimal("25.013")));
    }

    @Test
    void missing_coordinates_stay_missing() {
        assertEquals(null, RoamPresenceBlur.latitude(null));
        assertEquals(null, RoamPresenceBlur.longitude(new BigDecimal("31.0"), null));
    }
}
