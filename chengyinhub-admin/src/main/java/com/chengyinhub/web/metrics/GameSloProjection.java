package com.chengyinhub.web.metrics;

import com.chengyinhub.common.observability.GameSloEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OPS-01 只读投影：把进程内 MeterRegistry 的固定序列读成一份可直接排查的快照。
 *
 * <p>边界写在响应里：计数是**本进程启动以来**（scope=PROCESS_UPTIME），不是 24h/7d；
 * 没有持久化历史时 history24h/history7d 一律 UNKNOWN。P95 来自 Micrometer 5 分钟分布
 * 缓冲，样本数同时给出；没有样本时 p95 为 null、状态 UNKNOWN，不伪造 0。
 */
@Component
public class GameSloProjection
{
    private final MeterRegistry registry;
    private final GameSloThresholds thresholds;
    private final Clock clock;
    private final long processStartedAtMillis;

    /**
     * 生产构造器：多构造器时 Spring 需要显式指定，否则按无参构造装配会失败。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public GameSloProjection(MeterRegistry registry, GameSloThresholds thresholds)
    {
        this(registry, thresholds, Clock.systemUTC(), ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /** 测试用：建议阈值 + 受控时钟。 */
    static GameSloProjection at(MeterRegistry registry, Clock clock, long processStartedAtMillis)
    {
        return new GameSloProjection(registry, GameSloThresholds.defaults(), clock, processStartedAtMillis);
    }

    /** 测试用：显式阈值（验证 cy.slo.* 覆盖确实生效）。 */
    static GameSloProjection at(MeterRegistry registry, GameSloThresholds thresholds, Clock clock,
            long processStartedAtMillis)
    {
        return new GameSloProjection(registry, thresholds, clock, processStartedAtMillis);
    }

    private GameSloProjection(MeterRegistry registry, GameSloThresholds thresholds, Clock clock,
            long processStartedAtMillis)
    {
        this.registry = registry;
        this.thresholds = thresholds;
        this.clock = clock;
        this.processStartedAtMillis = processStartedAtMillis;
    }

    public Map<String, Object> snapshot()
    {
        long now = clock.millis();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.ofEpochMilli(now).toString());
        root.put("coverage", coverage(now));

        Map<String, Object> routes = new LinkedHashMap<>();
        for (GameSloRoute route : GameSloRoute.values())
        {
            routes.put(route.tag(), route.family() == GameSloRoute.Family.REWARD
                    ? rewardRoute() : httpRoute(route));
        }
        root.put("routes", routes);
        root.put("thresholds", GameSloSloPolicy.describe(thresholds));
        Map<String, Object> notifications = new LinkedHashMap<>();
        notifications.put("channel", "NOT_CONFIGURED_BY_OPS01");
        notifications.put("onCall", "UNASSIGNED");
        notifications.put("note", "本卡不配置真实通知通道或值班人，不创建定时监控进程；只保留现有日志/指标读取口。");
        root.put("notifications", notifications);
        return root;
    }

    private Map<String, Object> coverage(long now)
    {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("scope", "PROCESS_UPTIME");
        coverage.put("processStartedAt", Instant.ofEpochMilli(processStartedAtMillis).toString());
        coverage.put("processUptimeSeconds", Math.max(0L, (now - processStartedAtMillis) / 1000L));
        coverage.put("history24h", "UNKNOWN");
        coverage.put("history7d", "UNKNOWN");
        coverage.put("p95Window", "MICROMETER_5_MINUTE_DISTRIBUTION_BUFFER");
        coverage.put("note", "计数自本进程启动；无跨重启持久化历史，24h/7d 不可由本投影宣称。");
        return coverage;
    }

    private Map<String, Object> httpRoute(GameSloRoute route)
    {
        long success = counter(route, GameSloResult.SUCCESS);
        long rejected = counter(route, GameSloResult.REJECTED);
        long fault = counter(route, GameSloResult.FAULT);
        long attempts = success + rejected + fault;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attempts", attempts);
        out.put("success", success);
        out.put("rejected", rejected);
        out.put("fault", fault);
        out.put("successRate", rate(success, attempts));
        out.put("failureRate", rate(rejected + fault, attempts));
        out.put("faultRate", rate(fault, attempts));
        out.put("latency", latency(route));
        out.put("evaluation", evaluation(route, attempts, rejected + fault, fault));
        return out;
    }

    private Map<String, Object> evaluation(GameSloRoute route, long attempts, long bad, long fault)
    {
        int minSamples = thresholds.minSamples();
        double maxFailureRate = thresholds.maxFailureRate(route);
        double recoveryMax = thresholds.recoveryFailureRate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("minSamples", minSamples);
        out.put("maxFailureRate", maxFailureRate);
        out.put("sloStatus", GameSloSloPolicy.evaluate(attempts, bad, minSamples, maxFailureRate).name());
        out.put("faultStatus", GameSloSloPolicy.evaluate(attempts, fault, minSamples, maxFailureRate).name());
        out.put("recoveryFailureRateMax", recoveryMax);
        out.put("sloRecovered", GameSloSloPolicy.recovered(attempts, bad, minSamples, recoveryMax));
        out.put("faultRecovered", GameSloSloPolicy.recovered(attempts, fault, minSamples, recoveryMax));
        return out;
    }

    private Map<String, Object> latency(GameSloRoute route)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", GameSloResult.SUCCESS.tag());
        out.put("percentileWindowSeconds", GameSloMetrics.PERCENTILE_WINDOW.toSeconds());
        out.put("percentileSampleNote", "p95/p50 来自 Micrometer 分布缓冲（5 分钟窗口，默认 3 个缓冲区），"
                + "percentileSampleCount 是该窗口内的样本数；samplesSinceStart 是自进程启动的成功样本总数，两者不互相冒充。");
        out.put("samplesSinceStart", 0L);
        out.put("percentileSampleCount", 0L);
        out.put("p50Ms", null);
        out.put("p95Ms", null);
        out.put("maxSinceStartMs", null);
        out.put("status", "UNKNOWN");

        Timer timer = registry.find(timerName(route))
                .tag(GameSloMetrics.TAG_ROUTE, route.tag())
                .tag(GameSloMetrics.TAG_RESULT, GameSloResult.SUCCESS.tag())
                .timer();
        if (timer == null || timer.count() <= 0)
        {
            return out;
        }
        HistogramSnapshot snapshot = timer.takeSnapshot();
        long sinceStart = timer.count();
        long windowSamples = snapshot.count();
        Double p50 = percentile(snapshot, 0.5);
        Double p95 = percentile(snapshot, 0.95);
        out.put("samplesSinceStart", sinceStart);
        out.put("percentileSampleCount", windowSamples);
        out.put("p50Ms", p50);
        out.put("p95Ms", p95);
        out.put("maxSinceStartMs", millis(snapshot.max(TimeUnit.NANOSECONDS)));
        if (p95 == null)
        {
            // 缓冲里没有分位样本（零样本或超过窗口未再发生）⇒ 不给 0，也不冒充健康。
            out.put("status", "UNKNOWN");
        }
        else if (windowSamples < thresholds.minSamples())
        {
            out.put("status", "INSUFFICIENT_SAMPLES");
        }
        else
        {
            out.put("status", p95 > thresholds.maxP95Ms(route) ? "BREACHED" : "HEALTHY");
        }
        return out;
    }

    private Map<String, Object> rewardRoute()
    {
        long conflicts = GameSloEvents.rewardIdempotentConflicts();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotentConflicts", conflicts);
        out.put("maxIdempotentConflicts", 0);
        out.put("status", conflicts > 0 ? "BREACHED" : "HEALTHY");
        out.put("note", "正常重放不计；只计唯一键冲突且无法证明已发放的失败。");
        return out;
    }

    private long counter(GameSloRoute route, GameSloResult result)
    {
        Counter counter = registry.find(counterName(route))
                .tag(GameSloMetrics.TAG_ROUTE, route.tag())
                .tag(GameSloMetrics.TAG_RESULT, result.tag())
                .counter();
        return counter == null ? 0L : Math.round(counter.count());
    }

    private static Double percentile(HistogramSnapshot snapshot, double percentile)
    {
        for (ValueAtPercentile value : snapshot.percentileValues())
        {
            if (Math.abs(value.percentile() - percentile) < 1e-9)
            {
                return millis(value.value(TimeUnit.NANOSECONDS));
            }
        }
        return null;
    }

    private static Double millis(double nanos)
    {
        if (!Double.isFinite(nanos) || nanos < 0)
        {
            return null;
        }
        return nanos / 1_000_000d;
    }

    private static Double rate(long part, long total)
    {
        if (total <= 0)
        {
            return null;
        }
        return Math.max(0L, part) / (double) total;
    }

    private static String counterName(GameSloRoute route)
    {
        return switch (route.family())
        {
            case START -> GameSloMetrics.START_TOTAL;
            case ARRIVE -> GameSloMetrics.ARRIVE_TOTAL;
            case PAY -> GameSloMetrics.PAY_CALLBACK_TOTAL;
            case REWARD -> throw new IllegalArgumentException("reward route has no HTTP counter");
        };
    }

    private static String timerName(GameSloRoute route)
    {
        return switch (route.family())
        {
            case START -> GameSloMetrics.START_LATENCY;
            case ARRIVE -> GameSloMetrics.ARRIVE_LATENCY;
            case PAY -> GameSloMetrics.PAY_CALLBACK_LATENCY;
            case REWARD -> throw new IllegalArgumentException("reward route has no latency timer");
        };
    }
}
