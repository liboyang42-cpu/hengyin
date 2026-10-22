package com.chengyinhub.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GB 11643 校验位判定。这是平台能做到的全部 —— 没有三要素通道,
 * 所以校验通过 ≠ 确有其人,文案因此只能说「已登记」。
 */
class IdCardUtilsTest {

    @Test
    void 合法号码按校验位放行() {
        assertTrue(IdCardUtils.isValid("110105199003070017"));
        assertTrue(IdCardUtils.isValid("510105199912310019"));
    }

    /** 末位 X 是校验位取到 2 时的合法形态,手写几乎必然写成小写,必须两种都收。 */
    @Test
    void 末位X大小写都算合法() {
        assertTrue(IdCardUtils.isValid("31010119750101001X"));
        assertTrue(IdCardUtils.isValid("31010119750101001x"));
    }

    @Test
    void 用户粘贴带来的空格与换行先归一化再判() {
        assertEquals("110105199003070017", IdCardUtils.normalize("  110105 19900307 0017 \n"));
        assertTrue(IdCardUtils.isValid("1101 05199003070017"));
    }

    @Test
    void 校验位错一位就拒() {
        assertFalse(IdCardUtils.isValid("110105199003070018"));
        assertFalse(IdCardUtils.isValid("110105199003070011"));
    }

    @Test
    void 长度与字符集不合一律拒() {
        assertFalse(IdCardUtils.isValid(null));
        assertFalse(IdCardUtils.isValid(""));
        assertFalse(IdCardUtils.isValid("11010519900307001"));
        // 15 位旧证不放行:存进去无法与任何外部口径对账
        assertFalse(IdCardUtils.isValid("110105900307001"));
        assertFalse(IdCardUtils.isValid("1101051990030700IA"));
    }

    /** 出生日期全零是最常见的随手填,校验位照样能凑对,所以日期必须单独判。 */
    @Test
    void 出生月份日期不合法就拒() {
        assertFalse(IdCardUtils.isValid("110105199013070014"));
        assertFalse(IdCardUtils.isValid("110105199002300011"));
        // 1900 之前的号码不可能是活着的发布者
        assertFalse(IdCardUtils.isValid("110105189912310012"));
    }

    /** 闰年 2/29 合法,平年 2/29 不合法 —— 不判这条,20010229 这种号会一路通过。 */
    @Test
    void 闰年二月廿九放行_平年拒() {
        assertTrue(IdCardUtils.isValid(IdCardUtils.normalize("11010520000229001" + check("11010520000229001"))));
        assertFalse(IdCardUtils.isValid("110105199902290011"));
    }

    /**
     * 加权和 %11 == 10 时国标校验位是 '2'。校验位表曾少这一项,这类号码直接数组越界(接口 500),
     * 约 1/11 的真实号码永远登记不上。期望值按国标公式 (12 - sum%11) % 11 独立推出,不复用被测表。
     */
    @Test
    void 校验位为2的号码合法且不越界() {
        assertEquals(10, weightedSum("11010519900307009") % 11);
        assertTrue(IdCardUtils.isValid("110105199003070092"));
        // 同一前 17 位换任何别的末位都必须拒,而不是抛异常
        assertFalse(IdCardUtils.isValid("110105199003070093"));
        assertFalse(IdCardUtils.isValid("11010519900307009X"));
    }

    @Test
    void 校验位表覆盖全部11种余数() {
        for (int seq = 0; seq < 1000; seq++) {
            String first17 = "11010519900307" + String.format("%03d", seq);
            int code = (12 - weightedSum(first17) % 11) % 11;
            String expected = first17 + (code == 10 ? "X" : String.valueOf(code));
            assertTrue(IdCardUtils.isValid(expected), expected);
        }
    }

    private static int weightedSum(String first17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (first17.charAt(i) - '0') * weights[i];
        }
        return sum;
    }

    private static String check(String first17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] codes = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (first17.charAt(i) - '0') * weights[i];
        }
        return String.valueOf(codes[sum % 11]);
    }
}
