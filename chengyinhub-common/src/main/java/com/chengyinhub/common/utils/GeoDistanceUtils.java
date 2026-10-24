package com.chengyinhub.common.utils;

/**
 * 后端唯一的球面距离实现(O-02 收敛):所有 Java 侧「两点相距多少米」都走这里。
 *
 * <p>口径:地球平均半径 6371000 米、haversine 公式、输入为同一坐标系下的十进制度(本工具不做
 * gcj02/wgs84 换算,坐标系由调用方保证一致)。参数顺序固定为「纬度在前」。
 *
 * <p>非有限输入(NaN/Infinity)原样传播为 NaN,不会被吞成 0 —— 调用方用
 * {@link Double#isFinite(double)} 失败关闭。浮点误差导致的 a 越界被夹到 [0,1],
 * 对跖点不会因 sqrt(负数) 得 NaN。
 */
public final class GeoDistanceUtils
{
    /** 地球平均半径(米)。全仓统一口径,不再各自写 6371000 / 6371008.8。 */
    public static final double EARTH_RADIUS_METERS = 6371000d;

    private GeoDistanceUtils()
    {
    }

    /** 两点球面距离(米,haversine)。参数:纬度1、经度1、纬度2、经度2。 */
    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2)
    {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        // Math.min/max 遇 NaN 返回 NaN,非有限输入不会被夹成合法值
        a = Math.min(1d, Math.max(0d, a));
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }
}
