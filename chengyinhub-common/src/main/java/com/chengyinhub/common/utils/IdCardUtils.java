package com.chengyinhub.common.utils;

/**
 * 中国大陆身份证号(GB 11643-1999)格式与校验位判定。
 *
 * <p>这里只做到「号码自洽」,做到不了「确有其人」。平台没有接三要素核验通道,
 * 所以本方法的 true 只说明这串数字符合国标算法,不证明它属于提交者本人。
 * 因此对外文案一律用「已登记 / 发布者实名」,不允许出现「已实名认证」。
 */
public final class IdCardUtils {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
    /** 出生日期只校验到「像不像个日子」,不校验是否在未来 —— 那属于业务口径,交给调用方的提示文案。 */
    private static final int MIN_BIRTH_YEAR = 1900;

    private IdCardUtils() {
    }

    /**
     * 归一化:去首尾空白、去内部空格、末位 x 转大写。
     * 身份证里有 X,而用户手写几乎必然写成小写,不归一化会把合法号码判成非法。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", "").toUpperCase();
    }

    /**
     * 是否为格式自洽的 18 位身份证号(含 GB 11643 校验位)。
     *
     * <p>15 位旧证不放行:2004 年起停止签发,且我们只有 15 位→18 位的换算口径,
     * 收下它等于存下一个我们无法与任何外部系统对账的形态。
     */
    public static boolean isValid(String raw) {
        String id = normalize(raw);
        if (id.length() != 18) {
            return false;
        }
        for (int i = 0; i < 17; i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') {
                return false;
            }
        }
        char last = id.charAt(17);
        if (!(last >= '0' && last <= '9') && last != 'X') {
            return false;
        }
        if (!isPlausibleBirthday(id.substring(6, 14))) {
            return false;
        }
        return checkCode(id) == last;
    }

    private static char checkCode(String id) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * WEIGHTS[i];
        }
        return CHECK_CODES[sum % 11];
    }

    private static boolean isPlausibleBirthday(String ymd) {
        int year = digits(ymd, 0, 4);
        int month = digits(ymd, 4, 6);
        int day = digits(ymd, 6, 8);
        if (year < MIN_BIRTH_YEAR || year > java.time.Year.now().getValue()) {
            return false;
        }
        if (month < 1 || month > 12) {
            return false;
        }
        int[] monthDays = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        int maxDay = monthDays[month - 1] + (month == 2 && isLeap(year) ? 1 : 0);
        return day >= 1 && day <= maxDay;
    }

    private static boolean isLeap(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    /** 段内全为数字才返回数值,否则返回 -1 让上层判否。 */
    private static int digits(String s, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
