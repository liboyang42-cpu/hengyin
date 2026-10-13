package com.chengyinhub.common.utils;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 经纬度按数值比较。
 *
 * <p>库里的坐标串与客户端回传的坐标经常只是格式化差异(121.47 vs 121.470000),
 * 按字符串 equals 会把「只改了文字」的编辑误判成「改了地点」而拒掉。
 * 容差取小数点后 6 位(1e-6,约 0.1 米),远小于任何真实改点,不会因此放行改地点。
 */
public final class CoordinateUtils {

    /** 允许的坐标差:小数点后 6 位。 */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.000001");

    private CoordinateUtils() {
    }

    /** 字符串形态坐标比较;任一侧不可解析时回退为精确比较(脏数据不因此放行)。 */
    public static boolean sameCoordinate(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        BigDecimal parsedLeft = parseOrNull(left);
        BigDecimal parsedRight = parseOrNull(right);
        if (parsedLeft == null || parsedRight == null) {
            return Objects.equals(left, right);
        }
        return sameCoordinate(parsedLeft, parsedRight);
    }

    /** BigDecimal 形态坐标比较;任一为 null 只有同为 null 才算相同。 */
    public static boolean sameCoordinate(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.subtract(right).abs().compareTo(TOLERANCE) <= 0;
    }

    private static BigDecimal parseOrNull(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
