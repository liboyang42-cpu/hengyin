package com.chengyinhub.common.utils.geo;

/**
 * 坐标系边界转换 + 字符串坐标严格解析(Doc2 F1d)。
 *
 * <p>规则:
 * <ul>
 *   <li>入库一律 WGS84;客户端带 coord_sys 上报,GCJ02/BD09 在边界<b>只转一次</b>,产出 {@link Wgs84Point}。</li>
 *   <li>解析失败<b>不回落为 0</b>(对比 DistanceCalculatorUtils 失败返回 0.0 = 「就在原地」),
 *       而是返回带脏值原因的 {@link Parsed},由调用方隔离、上报。</li>
 * </ul>
 * GCJ02→WGS84 没有闭式逆变换,用不动点迭代,收敛到 1e-9 度(毫米级)。境外点 GCJ02 与 WGS84 相同。
 */
public final class GeoCoordinates {

    /** 脏值原因(落 orient_node_geo.dirty_reason,也直接回给发布者)。 */
    public enum DirtyReason { MISSING, NOT_NUMBER, NON_FINITE, OUT_OF_RANGE, ZERO, SWAPPED_SUSPECT }

    /** 解析结果:要么是一个干净的点,要么是一个脏值原因,二者恰有其一。 */
    public static final class Parsed {
        private final Wgs84Point point;
        private final DirtyReason dirtyReason;

        private Parsed(Wgs84Point point, DirtyReason dirtyReason) {
            this.point = point;
            this.dirtyReason = dirtyReason;
        }

        public boolean clean() { return point != null; }

        public Wgs84Point point() { return point; }

        public DirtyReason dirtyReason() { return dirtyReason; }
    }

    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;
    private static final double X_PI = Math.PI * 3000.0 / 180.0;
    private static final double CONVERGE_DEG = 1e-9;
    private static final int MAX_ITER = 30;

    private GeoCoordinates() {
    }

    /** 字符串坐标(cms_topic_node.longitude/latitude 这类 varchar)按 coordSys 解析并转 WGS84。 */
    public static Parsed parse(String lng, String lat, CoordSys coordSys) {
        if (coordSys == null) throw new IllegalArgumentException("coordSys 不能为空");
        if (isBlank(lng) || isBlank(lat)) return dirty(DirtyReason.MISSING);
        double x;
        double y;
        try {
            x = Double.parseDouble(lng.trim());
            y = Double.parseDouble(lat.trim());
        } catch (NumberFormatException e) {
            return dirty(DirtyReason.NOT_NUMBER);
        }
        return check(x, y, coordSys);
    }

    /** 数值坐标(客户端上报)按 coordSys 校验并转 WGS84。 */
    public static Parsed parse(Double lng, Double lat, CoordSys coordSys) {
        if (coordSys == null) throw new IllegalArgumentException("coordSys 不能为空");
        if (lng == null || lat == null) return dirty(DirtyReason.MISSING);
        return check(lng, lat, coordSys);
    }

    /** 已经是 WGS84 的库内数值(orient_node_geo.wgs84_*),只做合法性校验、不再转换。 */
    public static Parsed parseStoredWgs84(Double lng, Double lat) {
        return parse(lng, lat, CoordSys.WGS84);
    }

    /** 转换本体。包内可见:对外唯一入口是 {@link #parse},拿到 Wgs84Point 的代码无从再转一次。 */
    static Wgs84Point toWgs84(CoordSys coordSys, double lng, double lat) {
        switch (coordSys) {
            case WGS84:
                return new Wgs84Point(lng, lat);
            case GCJ02:
                return gcj02ToWgs84(lng, lat);
            case BD09: {
                double[] gcj = bd09ToGcj02(lng, lat);
                return gcj02ToWgs84(gcj[0], gcj[1]);
            }
            default:
                throw new IllegalArgumentException("未知坐标系: " + coordSys);
        }
    }

    /** WGS84 → GCJ02 正变换(仅供逆变换迭代与测试使用;对外下发仍由端上地图组件负责)。 */
    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) return new double[] {lng, lat};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[] {lng + dLng, lat + dLat};
    }

    /** GCJ02 → BD09 正变换(测试用)。 */
    public static double[] gcj02ToBd09(double lng, double lat) {
        double z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI);
        return new double[] {z * Math.cos(theta) + 0.0065, z * Math.sin(theta) + 0.006};
    }

    static double[] bd09ToGcj02(double lng, double lat) {
        double x = lng - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[] {z * Math.cos(theta), z * Math.sin(theta)};
    }

    static Wgs84Point gcj02ToWgs84(double lng, double lat) {
        if (outOfChina(lng, lat)) return new Wgs84Point(lng, lat);
        double wLng = lng;
        double wLat = lat;
        for (int i = 0; i < MAX_ITER; i++) {
            double[] g = wgs84ToGcj02(wLng, wLat);
            double dLng = g[0] - lng;
            double dLat = g[1] - lat;
            wLng -= dLng;
            wLat -= dLat;
            if (Math.abs(dLng) < CONVERGE_DEG && Math.abs(dLat) < CONVERGE_DEG) break;
        }
        return new Wgs84Point(wLng, wLat);
    }

    static boolean outOfChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static Parsed check(double x, double y, CoordSys coordSys) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return dirty(DirtyReason.NON_FINITE);
        // 0 不是坐标,是「没填」被 coerce 出来的默认值;单边为 0 同样不可信。
        if (x == 0.0 || y == 0.0) return dirty(DirtyReason.ZERO);
        if (Math.abs(y) > 90.0 && Math.abs(x) <= 90.0 && Math.abs(y) <= 180.0) {
            return dirty(DirtyReason.SWAPPED_SUSPECT);
        }
        if (Math.abs(x) > 180.0 || Math.abs(y) > 90.0) return dirty(DirtyReason.OUT_OF_RANGE);
        return new Parsed(toWgs84(coordSys, x, y), null);
    }

    private static Parsed dirty(DirtyReason reason) {
        return new Parsed(null, reason);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
