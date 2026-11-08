package com.chengyinhub.web.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OPS-01 阈值/恢复判定的纯函数。默认值只是**代码内建议**，不代表生产已批准；
 * 真实阈值、通知通道和值班归属由运维 owner 在手册/配置中决定。
 *
 * <p>没有后台定时进程：投影读取时按当前进程内样本求值，或由人按运行手册判断。
 * 这样阈值可被测试钉住，又不会引入新的监控进程或付费平台。
 */
public final class GameSloSloPolicy
{
    public static final String VERSION = "ops01-suggested-v1";

    /** 输出里必须原样带出，避免把建议值误读成已生效的生产阈值。 */
    public static final String SOURCE = "code_default_suggested_not_production_approved";

    /** 实际生效值可由该前缀覆盖；缺省即下面的建议常量。本卡不写任何生产配置文件。 */
    public static final String CONFIGURED_BY = "cy.slo.* (env CY_SLO_*)";

    /** 下面的常量只是**缺省建议**：少于 MIN_SAMPLES 不判定，避免零样本/单样本噪声触发告警。 */
    public static final int MIN_SAMPLES = 20;

    /** 建议的恢复线：已告警后，失败率降到 2% 以内才视为恢复。 */
    public static final double RECOVERY_FAILURE_RATE_MAX = 0.02;

    public static final double START_MAX_FAILURE_RATE = 0.05;
    public static final double ARRIVE_MAX_FAILURE_RATE = 0.05;
    public static final double PAY_MAX_FAILURE_RATE = 0.01;

    public static final double START_MAX_P95_MS = 5000d;
    public static final double ARRIVE_MAX_P95_MS = 3000d;
    public static final double PAY_MAX_P95_MS = 3000d;

    public enum Verdict
    {
        NO_DATA, HEALTHY, BREACHED
    }

    private GameSloSloPolicy()
    {
    }

    /**
     * 失效判定：样本不足返回 NO_DATA；失败率严格大于阈值才算 BREACHED。
     */
    public static Verdict evaluate(long attempts, long bad, int minSamples, double maxBadRate)
    {
        if (attempts <= 0 || attempts < Math.max(1, minSamples))
        {
            return Verdict.NO_DATA;
        }
        return failureRate(bad, attempts) > maxBadRate ? Verdict.BREACHED : Verdict.HEALTHY;
    }

    /**
     * 恢复判定：样本足够且失败率回落到恢复线以内。用于人工/下一次读取确认「已恢复」，
     * 不维护隐式后台状态。
     */
    public static boolean recovered(long attempts, long bad, int minSamples, double recoveryRate)
    {
        return attempts > 0 && attempts >= Math.max(1, minSamples)
                && failureRate(bad, attempts) <= recoveryRate;
    }

    public static double failureRate(long bad, long attempts)
    {
        if (attempts <= 0)
        {
            return 0d;
        }
        return Math.max(0L, bad) / (double) attempts;
    }

    public static double maxFailureRate(GameSloRoute route)
    {
        return switch (route.family())
        {
            case START -> START_MAX_FAILURE_RATE;
            case ARRIVE -> ARRIVE_MAX_FAILURE_RATE;
            case PAY -> PAY_MAX_FAILURE_RATE;
            case REWARD -> 0d;
        };
    }

    public static double maxP95Ms(GameSloRoute route)
    {
        return switch (route.family())
        {
            case START -> START_MAX_P95_MS;
            case ARRIVE -> ARRIVE_MAX_P95_MS;
            case PAY -> PAY_MAX_P95_MS;
            case REWARD -> 0d;
        };
    }

    /**
     * 只读描述**实际生效**的阈值（可能来自 {@code cy.slo.*} 覆盖），并原样标注来源：
     * 默认值只是建议，不宣称生产已批准或已生效。
     */
    public static Map<String, Object> describe(GameSloThresholds thresholds)
    {
        GameSloThresholds effective = thresholds == null ? GameSloThresholds.defaults() : thresholds;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("source", SOURCE);
        root.put("configuredBy", CONFIGURED_BY);
        root.put("minSamples", effective.minSamples());
        root.put("recoveryFailureRateMax", effective.recoveryFailureRate());
        Map<String, Object> routes = new LinkedHashMap<>();
        for (GameSloRoute route : GameSloRoute.values())
        {
            Map<String, Object> item = new LinkedHashMap<>();
            if (route.family() == GameSloRoute.Family.REWARD)
            {
                item.put("maxIdempotentConflicts", 0);
            }
            else
            {
                item.put("maxFailureRate", effective.maxFailureRate(route));
                item.put("maxSuccessP95Ms", effective.maxP95Ms(route));
            }
            routes.put(route.tag(), item);
        }
        root.put("routes", routes);
        return root;
    }
}
