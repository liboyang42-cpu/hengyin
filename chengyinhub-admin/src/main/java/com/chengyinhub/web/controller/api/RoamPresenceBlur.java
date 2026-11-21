package com.chengyinhub.web.controller.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * P1b SEC-15：发给陌生人的「附近还在走的人」坐标，一律吸附到不小于 {@link #MIN_CELL_M} 米的网格中心。
 *
 * <p>纬向格宽固定 0.01°（≈1.1 km）；经向格宽按所在纬度放大，保证东西向也不小于 500 m
 * （0.01° 经度在 60° 以北不足 560 m，届时改用 0.02° 等）。格宽总是 0.01° 的整数倍，回包保持两位小数。
 * 服务端落库仍是 3 位小数（≈100 m，只用于距离过滤和瞬移校验），从不原样发给别人。
 */
final class RoamPresenceBlur {
    /** 对外坐标的最小网格（米）。 */
    static final int MIN_CELL_M = 500;
    private static final double METERS_PER_DEGREE = 111_320d;
    private static final BigDecimal BASE_STEP = new BigDecimal("0.01");

    private RoamPresenceBlur() {
    }

    static BigDecimal latitude(BigDecimal lat) {
        if (lat == null) return null;
        return snap(lat, stepFor(1d));
    }

    static BigDecimal longitude(BigDecimal lat, BigDecimal lng) {
        if (lng == null) return null;
        double cos = lat == null ? 0d : Math.cos(Math.toRadians(Math.abs(lat.doubleValue())));
        return snap(lng, stepFor(cos));
    }

    /** 让 step × 每度米数 × cos ≥ MIN_CELL_M 的最小 0.01° 整数倍。 */
    static BigDecimal stepFor(double cosLat) {
        double cos = Math.max(cosLat, 0.01d);
        int multiples = (int) Math.ceil(MIN_CELL_M / (METERS_PER_DEGREE * cos * BASE_STEP.doubleValue()));
        return BASE_STEP.multiply(BigDecimal.valueOf(Math.max(1, multiples)));
    }

    /** 格宽 ≥ 东西向/南北向 500 m 的米数（测试用）。 */
    static double cellWidthMeters(BigDecimal step, double cosLat) {
        return step.doubleValue() * METERS_PER_DEGREE * cosLat;
    }

    private static BigDecimal snap(BigDecimal value, BigDecimal step) {
        BigDecimal cells = value.divide(step, 0, RoundingMode.HALF_UP);
        return cells.multiply(step).setScale(2, RoundingMode.HALF_UP);
    }
}
