package com.chengyinhub.web.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OPS-01 阈值与恢复判定：边界必须可验，默认值明确标成建议而非生产已批准。 */
class GameSloSloPolicyTest
{
    @Test
    void insufficientSamplesNeverBreach()
    {
        assertEquals(GameSloSloPolicy.Verdict.NO_DATA,
                GameSloSloPolicy.evaluate(0, 0, GameSloSloPolicy.MIN_SAMPLES, 0.05));
        assertEquals(GameSloSloPolicy.Verdict.NO_DATA,
                GameSloSloPolicy.evaluate(GameSloSloPolicy.MIN_SAMPLES - 1, GameSloSloPolicy.MIN_SAMPLES - 1,
                        GameSloSloPolicy.MIN_SAMPLES, 0.05));
        // 样本刚够、全部失败 ⇒ 才允许 BREACHED。
        assertEquals(GameSloSloPolicy.Verdict.BREACHED,
                GameSloSloPolicy.evaluate(GameSloSloPolicy.MIN_SAMPLES, GameSloSloPolicy.MIN_SAMPLES,
                        GameSloSloPolicy.MIN_SAMPLES, 0.05));
    }

    @Test
    void thresholdIsStrictAndBoundaryStaysHealthy()
    {
        // 恰好等于阈值不算 breach：避免抖动就把正常拒绝报成故障。
        assertEquals(GameSloSloPolicy.Verdict.HEALTHY, GameSloSloPolicy.evaluate(100, 5, 20, 0.05));
        assertEquals(GameSloSloPolicy.Verdict.BREACHED, GameSloSloPolicy.evaluate(100, 6, 20, 0.05));
        assertEquals(GameSloSloPolicy.Verdict.HEALTHY, GameSloSloPolicy.evaluate(100, 0, 20, 0.05));
    }

    @Test
    void recoveryNeedsEnoughSamplesAndInclusiveBoundary()
    {
        assertFalse(GameSloSloPolicy.recovered(0, 0, 20, 0.02));
        assertFalse(GameSloSloPolicy.recovered(19, 0, 20, 0.02));
        assertTrue(GameSloSloPolicy.recovered(100, 2, 20, 0.02));
        assertFalse(GameSloSloPolicy.recovered(100, 3, 20, 0.02));
    }

    @Test
    void defaultsAreLabelledSuggestedAndNotProductionApproved()
    {
        Map<String, Object> describe = GameSloSloPolicy.describe(null);
        assertEquals(GameSloSloPolicy.VERSION, describe.get("version"));
        assertTrue(((String) describe.get("source")).contains("not_production_approved"));
        assertTrue(((String) describe.get("configuredBy")).contains("cy.slo."));
        assertEquals(GameSloSloPolicy.MIN_SAMPLES, describe.get("minSamples"));

        @SuppressWarnings("unchecked")
        Map<String, Object> routes = (Map<String, Object>) describe.get("routes");
        @SuppressWarnings("unchecked")
        Map<String, Object> pay = (Map<String, Object>) routes.get(GameSloRoute.PAY_CALLBACK.tag());
        assertEquals(GameSloSloPolicy.PAY_MAX_FAILURE_RATE, pay.get("maxFailureRate"));
        assertEquals(GameSloSloPolicy.PAY_MAX_P95_MS, pay.get("maxSuccessP95Ms"));
        @SuppressWarnings("unchecked")
        Map<String, Object> reward = (Map<String, Object>) routes.get(GameSloRoute.REWARD_GRANT.tag());
        assertEquals(0, reward.get("maxIdempotentConflicts"));
        assertEquals(GameSloRoute.values().length, routes.size());
    }

    @Test
    void configuredOverridesReplaceTheSuggestedDefaultsInTheReadback()
    {
        GameSloThresholds overridden = new GameSloThresholds(50, 0.001, 0.09, 0.08, 0.005, 4000, 2500, 1500);
        Map<String, Object> describe = GameSloSloPolicy.describe(overridden);
        assertEquals(50, describe.get("minSamples"));
        assertEquals(0.001, describe.get("recoveryFailureRateMax"));
        @SuppressWarnings("unchecked")
        Map<String, Object> routes = (Map<String, Object>) describe.get("routes");
        @SuppressWarnings("unchecked")
        Map<String, Object> arrive = (Map<String, Object>) routes.get(GameSloRoute.ARRIVE.tag());
        assertEquals(0.08, arrive.get("maxFailureRate"));
        assertEquals(2500d, arrive.get("maxSuccessP95Ms"));
        // 覆盖值非法时按 0/1 夹紧，不允许出现负阈值把一切判成健康。
        GameSloThresholds clamped = new GameSloThresholds(0, -1, -1, 2, Double.NaN, -5, 0, 1);
        assertEquals(1, clamped.minSamples());
        assertEquals(0d, clamped.recoveryFailureRate());
        assertEquals(1d, clamped.maxFailureRate(GameSloRoute.ARRIVE));
        assertTrue(clamped.maxP95Ms(GameSloRoute.ARRIVE) >= 1d);
    }
}
