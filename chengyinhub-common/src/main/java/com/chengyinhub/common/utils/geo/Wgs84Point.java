package com.chengyinhub.common.utils.geo;

/**
 * 已经落在 WGS84 上的一个点。城市定向(Doc2 F1d)内部只流转这个类型。
 *
 * <p>构造器不公开:唯一入口是 {@link GeoCoordinates#parse} 系列方法。
 * 「GCJ02/BD09 只在边界转换一次」靠类型保证 —— 拿到 Wgs84Point 的代码无从再转一次。
 */
public final class Wgs84Point {

    private final double lng;
    private final double lat;

    Wgs84Point(double lng, double lat) {
        this.lng = lng;
        this.lat = lat;
    }

    public double lng() { return lng; }

    public double lat() { return lat; }

    @Override
    public String toString() {
        return "WGS84(" + lng + "," + lat + ")";
    }
}
