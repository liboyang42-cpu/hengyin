package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.DistanceCalculatorUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Doc2 F4 核销锚点(地图点 + 半径 + WiFi)的纯校验与判定,无 IO。
 *
 * <p>判定只服务于工作台自检({@code /api/merchant/desk/anchor/check}):本 PR 不把锚点接进任何核销端点。
 * WiFi 只比对 BSSID 规范化后的 SHA-256,不落明文。
 */
public final class RedemptionAnchorPolicy {

    public static final int MIN_RADIUS_M = 30;
    public static final int MAX_RADIUS_M = 500;
    public static final int MAX_WIFI = 8;
    /** 自检时上报精度超过此值(米)只给「定位太粗」,不给在/不在范围的结论 */
    public static final double MAX_ACCURACY_M = 100d;

    public static final String IN_RANGE = "IN_RANGE";
    public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
    public static final String LOCATION_UNKNOWN = "LOCATION_UNKNOWN";
    public static final String WIFI_MATCH = "WIFI_MATCH";
    public static final String WIFI_MISMATCH = "WIFI_MISMATCH";
    public static final String WIFI_NOT_CONFIGURED = "WIFI_NOT_CONFIGURED";
    public static final String WIFI_NOT_REPORTED = "WIFI_NOT_REPORTED";

    private static final Pattern BSSID = Pattern.compile("[0-9a-f]{2}(:[0-9a-f]{2}){5}");

    private RedemptionAnchorPolicy() {
    }

    /** 坐标合法(中国境内粗框不做,只做地理合法范围 + 非 0,0)。 */
    public static boolean validCoordinate(BigDecimal lat, BigDecimal lng) {
        if (lat == null || lng == null) return false;
        double la = lat.doubleValue();
        double lo = lng.doubleValue();
        return la >= -90 && la <= 90 && lo >= -180 && lo <= 180 && !(la == 0d && lo == 0d);
    }

    public static boolean validRadius(Integer radius) {
        return radius != null && radius >= MIN_RADIUS_M && radius <= MAX_RADIUS_M;
    }

    /** 规范化 BSSID:去空白、小写、'-' 换 ':';不合法返回 null。 */
    public static String normalizeBssid(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', ':');
        return BSSID.matcher(s).matches() ? s : null;
    }

    public static String hashBssid(String normalized) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 把商家上报的 BSSID 列表转成去重后的哈希列表;任一条非法或超过 {@link #MAX_WIFI} 条返回 null(调用方按参数错误拒绝)。
     */
    public static List<String> hashAll(Collection<String> rawBssids) {
        List<String> hashes = new ArrayList<>();
        if (rawBssids == null) return hashes;
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : rawBssids) {
            String normalized = normalizeBssid(raw);
            if (normalized == null) return null;
            seen.add(hashBssid(normalized));
        }
        if (seen.size() > MAX_WIFI) return null;
        hashes.addAll(seen);
        return hashes;
    }

    /** 位置判定。精度缺失按半径本身计(宁宽),精度过粗返回 LOCATION_UNKNOWN。 */
    public static String locationVerdict(BigDecimal anchorLat, BigDecimal anchorLng, int radiusM,
                                         BigDecimal lat, BigDecimal lng, Double accuracyM) {
        if (!validCoordinate(lat, lng) || !validCoordinate(anchorLat, anchorLng)) return LOCATION_UNKNOWN;
        if (accuracyM != null && (accuracyM.isNaN() || accuracyM < 0 || accuracyM > MAX_ACCURACY_M)) {
            return LOCATION_UNKNOWN;
        }
        double km = new DistanceCalculatorUtils().calculateDistance(
                anchorLng.toPlainString(), anchorLat.toPlainString(), lng.toPlainString(), lat.toPlainString());
        double slack = accuracyM == null ? 0d : accuracyM;
        return km * 1000d <= radiusM + slack ? IN_RANGE : OUT_OF_RANGE;
    }

    public static String wifiVerdict(List<String> configuredHashes, String reportedBssid) {
        if (configuredHashes == null || configuredHashes.isEmpty()) return WIFI_NOT_CONFIGURED;
        String normalized = normalizeBssid(reportedBssid);
        if (normalized == null) return WIFI_NOT_REPORTED;
        return configuredHashes.contains(hashBssid(normalized)) ? WIFI_MATCH : WIFI_MISMATCH;
    }
}
