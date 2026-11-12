package com.chengyinhub.web.metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OPS-01 阈值的**唯一生效来源**：Spring 配置项 {@code cy.slo.*}，缺省值即代码内建议值。
 *
 * <p>默认值与 {@link GameSloSloPolicy} 中的常量一致，标注为 {@code ops01-suggested-v1}，
 * <b>不代表生产已批准或已生效</b>。owner 若要调整，只需在自己的环境注入
 * {@code CY_SLO_*} / {@code cy.slo.*}，不需要改代码；本卡不改任何生产配置文件。
 */
@Component
public class GameSloThresholds
{
    private final int minSamples;
    private final double recoveryFailureRate;
    private final double startMaxFailureRate;
    private final double arriveMaxFailureRate;
    private final double payMaxFailureRate;
    private final double startMaxP95Ms;
    private final double arriveMaxP95Ms;
    private final double payMaxP95Ms;

    public GameSloThresholds(
            @Value("${cy.slo.min-samples:20}") int minSamples,
            @Value("${cy.slo.recovery-failure-rate:0.02}") double recoveryFailureRate,
            @Value("${cy.slo.start.max-failure-rate:0.05}") double startMaxFailureRate,
            @Value("${cy.slo.arrive.max-failure-rate:0.05}") double arriveMaxFailureRate,
            @Value("${cy.slo.pay.max-failure-rate:0.01}") double payMaxFailureRate,
            @Value("${cy.slo.start.max-p95-ms:5000}") double startMaxP95Ms,
            @Value("${cy.slo.arrive.max-p95-ms:3000}") double arriveMaxP95Ms,
            @Value("${cy.slo.pay.max-p95-ms:3000}") double payMaxP95Ms)
    {
        this.minSamples = Math.max(1, minSamples);
        this.recoveryFailureRate = clampRate(recoveryFailureRate);
        this.startMaxFailureRate = clampRate(startMaxFailureRate);
        this.arriveMaxFailureRate = clampRate(arriveMaxFailureRate);
        this.payMaxFailureRate = clampRate(payMaxFailureRate);
        this.startMaxP95Ms = Math.max(1d, startMaxP95Ms);
        this.arriveMaxP95Ms = Math.max(1d, arriveMaxP95Ms);
        this.payMaxP95Ms = Math.max(1d, payMaxP95Ms);
    }

    /** 测试用：全部取代码内建议值。 */
    static GameSloThresholds defaults()
    {
        return new GameSloThresholds(GameSloSloPolicy.MIN_SAMPLES,
                GameSloSloPolicy.RECOVERY_FAILURE_RATE_MAX,
                GameSloSloPolicy.START_MAX_FAILURE_RATE,
                GameSloSloPolicy.ARRIVE_MAX_FAILURE_RATE,
                GameSloSloPolicy.PAY_MAX_FAILURE_RATE,
                GameSloSloPolicy.START_MAX_P95_MS,
                GameSloSloPolicy.ARRIVE_MAX_P95_MS,
                GameSloSloPolicy.PAY_MAX_P95_MS);
    }

    public int minSamples()
    {
        return minSamples;
    }

    public double recoveryFailureRate()
    {
        return recoveryFailureRate;
    }

    public double maxFailureRate(GameSloRoute route)
    {
        return switch (route.family())
        {
            case START -> startMaxFailureRate;
            case ARRIVE -> arriveMaxFailureRate;
            case PAY -> payMaxFailureRate;
            case REWARD -> 0d;
        };
    }

    public double maxP95Ms(GameSloRoute route)
    {
        return switch (route.family())
        {
            case START -> startMaxP95Ms;
            case ARRIVE -> arriveMaxP95Ms;
            case PAY -> payMaxP95Ms;
            case REWARD -> 0d;
        };
    }

    private static double clampRate(double rate)
    {
        if (!Double.isFinite(rate) || rate < 0d)
        {
            return 0d;
        }
        return Math.min(1d, rate);
    }
}
