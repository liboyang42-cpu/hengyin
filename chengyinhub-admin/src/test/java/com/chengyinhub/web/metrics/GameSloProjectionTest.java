package com.chengyinhub.web.metrics;

import com.chengyinhub.common.observability.GameSloEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-01 只读投影合同：没有样本时 P95 是 UNKNOWN（不是 0），计数边界是进程启动以来，
 * 24h/7d 历史明确 UNKNOWN，奖励幂等冲突单独成一条。
 */
class GameSloProjectionTest
{
    private static final long START_AT = Instant.parse("2026-11-08T00:00:00Z").toEpochMilli();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T02:00:00Z"), ZoneOffset.UTC);

    @Test
    @SuppressWarnings("unchecked")
    void zeroSamplesReportUnknownInsteadOfZero()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new GameSloMetrics(registry);
        GameSloProjection projection = GameSloProjection.at(registry, CLOCK, START_AT);

        Map<String, Object> snapshot = projection.snapshot();
        Map<String, Object> coverage = (Map<String, Object>) snapshot.get("coverage");
        assertEquals("PROCESS_UPTIME", coverage.get("scope"));
        assertEquals("UNKNOWN", coverage.get("history24h"));
        assertEquals("UNKNOWN", coverage.get("history7d"));
        assertEquals(7200L, coverage.get("processUptimeSeconds"));
        assertEquals("2026-11-08T00:00:00Z", coverage.get("processStartedAt"));

        Map<String, Object> routes = (Map<String, Object>) snapshot.get("routes");
        Map<String, Object> arrive = (Map<String, Object>) routes.get(GameSloRoute.ARRIVE.tag());
        assertEquals(0L, arrive.get("attempts"));
        assertNull(arrive.get("successRate"), "零样本的比率不是 0");
        Map<String, Object> latency = (Map<String, Object>) arrive.get("latency");
        assertEquals(0L, latency.get("samplesSinceStart"));
        assertEquals(0L, latency.get("percentileSampleCount"));
        assertNull(latency.get("p95Ms"));
        assertEquals("UNKNOWN", latency.get("status"));

        Map<String, Object> evaluation = (Map<String, Object>) arrive.get("evaluation");
        assertEquals("NO_DATA", evaluation.get("sloStatus"));

        Map<String, Object> thresholds = (Map<String, Object>) snapshot.get("thresholds");
        assertEquals(GameSloSloPolicy.SOURCE, thresholds.get("source"));
        Map<String, Object> notifications = (Map<String, Object>) snapshot.get("notifications");
        assertEquals("NOT_CONFIGURED_BY_OPS01", notifications.get("channel"));
        assertEquals("UNASSIGNED", notifications.get("onCall"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void realSamplesProduceP95AndBreachThenRecover()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GameSloMetrics metrics = new GameSloMetrics(registry);
        GameSloProjection projection = GameSloProjection.at(registry, CLOCK, START_AT);

        // 25 个真实时延样本：p95 必须落在这批样本里，样本数如实回报。
        for (int i = 0; i < 25; i++)
        {
            metrics.recordStart(GameSloRoute.ORIENT_START, GameSloResult.SUCCESS,
                    TimeUnit.MILLISECONDS.toNanos(100 + i * 10L));
        }
        Map<String, Object> healthy = (Map<String, Object>) ((Map<String, Object>)
                projection.snapshot().get("routes")).get(GameSloRoute.ORIENT_START.tag());
        Map<String, Object> latency = (Map<String, Object>) healthy.get("latency");
        assertEquals(25L, latency.get("samplesSinceStart"));
        assertNotNull(latency.get("p95Ms"));
        double p95 = (Double) latency.get("p95Ms");
        assertTrue(p95 >= 100d && p95 <= 340d, "p95 必须是真实样本值: " + p95);
        assertEquals("HEALTHY", latency.get("status"));
        assertEquals("HEALTHY", ((Map<String, Object>) healthy.get("evaluation")).get("sloStatus"));

        // 加故障样本压到失败率阈值以上：状态与恢复标记都要如实。
        for (int i = 0; i < 5; i++)
        {
            metrics.recordStart(GameSloRoute.ORIENT_START, GameSloResult.FAULT, TimeUnit.MILLISECONDS.toNanos(5));
        }
        Map<String, Object> breached = (Map<String, Object>) ((Map<String, Object>)
                projection.snapshot().get("routes")).get(GameSloRoute.ORIENT_START.tag());
        assertEquals(30L, breached.get("attempts"));
        assertEquals(5L, breached.get("fault"));
        Map<String, Object> evaluation = (Map<String, Object>) breached.get("evaluation");
        assertEquals("BREACHED", evaluation.get("sloStatus"));
        assertEquals(Boolean.FALSE, evaluation.get("sloRecovered"));

        // 之后再来 230 个成功：失败率 5/255 ≈ 1.96%，低于 2% 恢复线，恢复标记必须自行成立
        // （不依赖任何后台定时进程或人工状态机）。
        for (int i = 0; i < 230; i++)
        {
            metrics.recordStart(GameSloRoute.ORIENT_START, GameSloResult.SUCCESS, TimeUnit.MILLISECONDS.toNanos(50));
        }
        Map<String, Object> recovered = (Map<String, Object>) ((Map<String, Object>)
                projection.snapshot().get("routes")).get(GameSloRoute.ORIENT_START.tag());
        Map<String, Object> recoveredEvaluation = (Map<String, Object>) recovered.get("evaluation");
        assertEquals("HEALTHY", recoveredEvaluation.get("sloStatus"));
        assertEquals(Boolean.TRUE, recoveredEvaluation.get("sloRecovered"));
        assertTrue(((Number) recovered.get("failureRate")).doubleValue() < 0.05);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rewardIdempotentConflictIsCountedSeparately()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new GameSloMetrics(registry);
        GameSloProjection projection = GameSloProjection.at(registry, CLOCK, START_AT);

        long before = GameSloEvents.rewardIdempotentConflicts();
        Map<String, Object> routes = (Map<String, Object>) projection.snapshot().get("routes");
        Map<String, Object> reward = (Map<String, Object>) routes.get(GameSloRoute.REWARD_GRANT.tag());
        assertEquals(before, reward.get("idempotentConflicts"));

        GameSloEvents.rewardIdempotentConflict();

        Map<String, Object> after = (Map<String, Object>) ((Map<String, Object>)
                projection.snapshot().get("routes")).get(GameSloRoute.REWARD_GRANT.tag());
        assertEquals(before + 1, after.get("idempotentConflicts"));
        assertEquals("BREACHED", after.get("status"));
        // 奖励计数器不参与任何 HTTP 成功率分母。
        for (GameSloRoute route : GameSloRoute.values())
        {
            if (route.family() != GameSloRoute.Family.REWARD)
            {
                Map<String, Object> http = (Map<String, Object>) routes.get(route.tag());
                assertFalse(after.containsKey("attempts"));
                assertNotNull(http.get("attempts"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredThresholdsDecideTheVerdict()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GameSloMetrics metrics = new GameSloMetrics(registry);
        GameSloThresholds strict = new GameSloThresholds(5, 0.001, 0.05, 0.05, 0.001, 5000, 3000, 3000);
        GameSloProjection projection = GameSloProjection.at(registry, strict, CLOCK, START_AT);

        for (int i = 0; i < 5; i++)
        {
            metrics.recordPayCallback(GameSloResult.FAULT, TimeUnit.MILLISECONDS.toNanos(10));
        }
        Map<String, Object> pay = (Map<String, Object>) ((Map<String, Object>)
                projection.snapshot().get("routes")).get(GameSloRoute.PAY_CALLBACK.tag());
        assertEquals(5L, pay.get("attempts"));
        assertEquals("BREACHED", ((Map<String, Object>) pay.get("evaluation")).get("sloStatus"),
                "覆盖后的 minSamples=5 必须生效；用默认 20 这里只能是 NO_DATA");

        // 同样的 5 个样本在默认阈值下是 NO_DATA，证明比较的确实是生效配置而不是写死的值。
        GameSloProjection defaults = GameSloProjection.at(registry, CLOCK, START_AT);
        Map<String, Object> defaultPay = (Map<String, Object>) ((Map<String, Object>)
                defaults.snapshot().get("routes")).get(GameSloRoute.PAY_CALLBACK.tag());
        assertEquals("NO_DATA", ((Map<String, Object>) defaultPay.get("evaluation")).get("sloStatus"));
    }
}
