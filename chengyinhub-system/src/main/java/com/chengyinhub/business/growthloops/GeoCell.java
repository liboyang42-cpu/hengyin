package com.chengyinhub.business.growthloops;

/** geohash 编码（只用于“是否在玩家走过的区域附近”的粗匹配，5 位约 4.9 km）。 */
public final class GeoCell
{
    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

    private GeoCell() {}

    public static String encode(double lat, double lng, int precision)
    {
        if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180 || precision < 1) return null;
        double[] latR = { -90, 90 }, lngR = { -180, 180 };
        StringBuilder out = new StringBuilder(precision);
        boolean even = true;
        int bit = 0, ch = 0;
        while (out.length() < precision) {
            double[] r = even ? lngR : latR;
            double v = even ? lng : lat;
            double mid = (r[0] + r[1]) / 2;
            if (v >= mid) { ch |= 1 << (4 - bit); r[0] = mid; } else { r[1] = mid; }
            even = !even;
            if (++bit == 5) { out.append(BASE32[ch]); bit = 0; ch = 0; }
        }
        return out.toString();
    }

    /** 文本坐标（cms_activity 里是字符串）解析失败返回 null。 */
    public static String encode(Object lat, Object lng, int precision)
    {
        try {
            if (lat == null || lng == null) return null;
            double a = Double.parseDouble(lat.toString().trim()), b = Double.parseDouble(lng.toString().trim());
            if (a == 0 && b == 0) return null;
            return encode(a, b, precision);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
