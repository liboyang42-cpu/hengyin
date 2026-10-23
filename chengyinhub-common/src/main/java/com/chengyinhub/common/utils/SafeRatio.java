package com.chengyinhub.common.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 看板/漏斗比率的唯一算法(owner 2026-10-23 D4 / P1-094):分母为 0(或负)时返回 null,
 * 端上显示「—」;不再用 0 冒充「没有样本」。舍入口径与各调用点原实现一致。
 */
public final class SafeRatio
{
    private SafeRatio()
    {
    }

    /** part / total,四舍五入到 scale 位小数;total ≤ 0 返回 null。 */
    public static Double ratio(long part, long total, int scale)
    {
        if (total <= 0) return null;
        double factor = Math.pow(10, scale);
        return Math.round((double) part / total * factor) / factor;
    }

    /** part / total 的百分数(×100),四舍五入到 scale 位小数;total ≤ 0 返回 null。 */
    public static Double percent(long part, long total, int scale)
    {
        if (total <= 0) return null;
        double factor = Math.pow(10, scale);
        return Math.round(part * 100.0 * factor / total) / factor;
    }

    /** 环比 (current - previous) / previous,HALF_UP 到 scale 位的字符串;previous ≤ 0 返回 null。 */
    public static String change(long current, long previous, int scale)
    {
        if (previous <= 0) return null;
        return BigDecimal.valueOf(current - previous)
                .divide(BigDecimal.valueOf(previous), scale, RoundingMode.HALF_UP).toPlainString();
    }
}
