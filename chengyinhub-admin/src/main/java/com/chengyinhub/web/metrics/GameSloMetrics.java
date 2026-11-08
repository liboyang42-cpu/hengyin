package com.chengyinhub.web.metrics;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.observability.CriticalPathEvents;
import com.chengyinhub.common.observability.GameSloEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OPS-01 四路径 SLO 指标。只注册固定路由 × 固定结果的低基数序列：
 * {@code route} 取 {@link GameSloRoute} 的固定值，{@code result} 取
 * {@link GameSloResult}（success/rejected/fault）。不带 actor、order、location、secret。
 *
 * <p>P95 用 Micrometer {@link Timer} 的真实样本与 {@code publishPercentiles(0.95)}；
 * 分布窗口固定 5 分钟，样本数由 {@link Timer#count()} 读出。没有样本时投影返回 UNKNOWN，
 * 不把进程启动以来的计数冒充 24h/7d 历史。
 *
 * <p>指标名对齐原卡 OPS-01 的提案名；如果以后由 owner 决定暴露 Prometheus，
 * 这些名字会按 Micrometer 命名约定转成下划线形式，本类不改 management 暴露范围。
 */
@Component
public class GameSloMetrics
{
    public static final String START_TOTAL = "cy_game_start_total";
    public static final String START_LATENCY = "cy_game_start_latency_seconds";
    public static final String ARRIVE_TOTAL = "cy_arrive_total";
    public static final String ARRIVE_LATENCY = "cy_arrive_latency_seconds";
    public static final String PAY_CALLBACK_TOTAL = "cy_pay_callback_total";
    public static final String PAY_CALLBACK_LATENCY = "cy_pay_callback_latency_seconds";
    public static final String REWARD_IDEMPOTENT_CONFLICT = "cy_reward_idempotent_conflict_total";

    public static final String TAG_ROUTE = "route";
    public static final String TAG_RESULT = "result";

    /** P95 的分布窗口：固定 5 分钟，避免把进程启动以来的历史混进延迟分位。 */
    public static final Duration PERCENTILE_WINDOW = Duration.ofMinutes(5);

    private final Map<GameSloRoute, Map<GameSloResult, Counter>> counters =
            new EnumMap<>(GameSloRoute.class);
    private final Map<GameSloRoute, Map<GameSloResult, Timer>> timers =
            new EnumMap<>(GameSloRoute.class);

    public GameSloMetrics(MeterRegistry registry)
    {
        for (GameSloRoute route : GameSloRoute.values())
        {
            if (route.family() == GameSloRoute.Family.REWARD)
            {
                continue;
            }
            Map<GameSloResult, Counter> routeCounters = new EnumMap<>(GameSloResult.class);
            Map<GameSloResult, Timer> routeTimers = new EnumMap<>(GameSloResult.class);
            for (GameSloResult result : GameSloResult.values())
            {
                routeCounters.put(result, registry.counter(counterName(route),
                        TAG_ROUTE, route.tag(), TAG_RESULT, result.tag()));
                routeTimers.put(result, Timer.builder(timerName(route))
                        .tags(TAG_ROUTE, route.tag(), TAG_RESULT, result.tag())
                        .description("OPS-01 关键路径时延（P95 分布窗口 5 分钟）")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .distributionStatisticExpiry(PERCENTILE_WINDOW)
                        .register(registry));
            }
            counters.put(route, routeCounters);
            timers.put(route, routeTimers);
        }
        FunctionCounter.builder(REWARD_IDEMPOTENT_CONFLICT, GameSloEvents.class,
                        ignored -> GameSloEvents.rewardIdempotentConflicts())
                .tag(TAG_ROUTE, GameSloRoute.REWARD_GRANT.tag())
                .description("完局发奖幂等冲突失败累计次数（OPS-01，正常重放不计）")
                .register(registry);
    }

    /** 开局（高级玩法 / 城市定向）：成功、业务拒绝、故障都记；P95 看 success 序列。 */
    public void recordStart(GameSloRoute route, GameSloResult result, long durationNanos)
    {
        if (route == null || route.family() != GameSloRoute.Family.START)
        {
            return;
        }
        record(route, result, durationNanos);
    }

    /** 到达判定：HTTP 200 + 业务失败不得算成功，由调用方先 {@link #classify(AjaxResult)}。 */
    public void recordArrive(GameSloResult result, long durationNanos)
    {
        record(GameSloRoute.ARRIVE, result, durationNanos);
    }

    /** 支付回调：只有 2xx/204 才算成功。 */
    public void recordPayCallback(GameSloResult result, long durationNanos)
    {
        record(GameSloRoute.PAY_CALLBACK, result, durationNanos);
    }

    private void record(GameSloRoute route, GameSloResult result, long durationNanos)
    {
        try
        {
            GameSloResult safeResult = result == null ? GameSloResult.FAULT : result;
            Map<GameSloResult, Counter> routeCounters = counters.get(route);
            Map<GameSloResult, Timer> routeTimers = timers.get(route);
            if (routeCounters == null || routeTimers == null)
            {
                return;
            }
            routeCounters.get(safeResult).increment();
            routeTimers.get(safeResult).record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
            if (safeResult == GameSloResult.FAULT)
            {
                GameSloEvents.fault(route.tag());
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // 观测失败不能反过来改变业务返回。
        }
    }

    private static String counterName(GameSloRoute route)
    {
        return switch (route.family())
        {
            case START -> START_TOTAL;
            case ARRIVE -> ARRIVE_TOTAL;
            case PAY -> PAY_CALLBACK_TOTAL;
            case REWARD -> throw new IllegalArgumentException("reward route has no HTTP result counter");
        };
    }

    private static String timerName(GameSloRoute route)
    {
        return switch (route.family())
        {
            case START -> START_LATENCY;
            case ARRIVE -> ARRIVE_LATENCY;
            case PAY -> PAY_CALLBACK_LATENCY;
            case REWARD -> throw new IllegalArgumentException("reward route has no latency timer");
        };
    }

    /**
     * 把 AjaxResult 映射成 SLO 结果：只有 code=200 是 success；带稳定机器码
     * （{@code errorCode} 或城市定向的 {@code err}）的业务拒绝是 rejected；
     * 4xx 无机器码也是 rejected；其余 5xx/未知码是 fault。
     *
     * <p>HTTP 200 的 body 里 code 非 200 时同样走这里——业务失败绝不因为传输层 200 就算成功。
     */
    public static GameSloResult classify(AjaxResult result)
    {
        if (result == null)
        {
            return GameSloResult.FAULT;
        }
        Object code = result.get(AjaxResult.CODE_TAG);
        int numeric = code instanceof Number ? ((Number) code).intValue() : 500;
        if (numeric == 200)
        {
            return GameSloResult.SUCCESS;
        }
        if (result.get("errorCode") != null || result.get("err") != null)
        {
            return GameSloResult.REJECTED;
        }
        if (numeric >= 400 && numeric < 500)
        {
            return GameSloResult.REJECTED;
        }
        return GameSloResult.FAULT;
    }

    /**
     * 把抛出的异常映射成 SLO 结果，复用 O-11 的业务拒绝/故障分类：
     * IllegalArgumentException / IllegalStateException / ServiceException / 裸 RuntimeException
     * 视为 rejected，数据访问、连接池、NPE 等视为 fault。
     */
    public static GameSloResult classify(Throwable failure)
    {
        if (failure == null)
        {
            return GameSloResult.FAULT;
        }
        return CriticalPathEvents.classify(failure) == CriticalPathEvents.Kind.REJECTED
                ? GameSloResult.REJECTED : GameSloResult.FAULT;
    }
}
