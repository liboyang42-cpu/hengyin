package com.chengyinhub.web.metrics;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OPS-01 指标合同：路由 × 结果的序列数固定（低基数），P95 来自真实 timer 样本，
 * HTTP 200 + 业务失败不得计成功。
 */
class GameSloMetricsTest
{
    @Test
    void registersFixedLowCardinalitySeriesForEveryRoute()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new GameSloMetrics(registry);

        int httpRoutes = 0;
        for (GameSloRoute route : GameSloRoute.values())
        {
            if (route.family() == GameSloRoute.Family.REWARD)
            {
                continue;
            }
            httpRoutes++;
            assertEquals(GameSloResult.values().length,
                    registry.find(counterName(route)).tag(GameSloMetrics.TAG_ROUTE, route.tag()).counters().size(),
                    route.tag() + " 的结果序列必须固定");
            assertEquals(GameSloResult.values().length,
                    registry.find(timerName(route)).tag(GameSloMetrics.TAG_ROUTE, route.tag()).timers().size(),
                    route.tag() + " 的时延序列必须固定");
        }
        // 每个 HTTP 路由 3 条计数 + 3 条时延；奖励只有一条函数计数，没有 actor/order/location 序列。
        assertEquals(httpRoutes * GameSloResult.values().length,
                registry.find(GameSloMetrics.START_TOTAL).counters().size()
                        + registry.find(GameSloMetrics.ARRIVE_TOTAL).counters().size()
                        + registry.find(GameSloMetrics.PAY_CALLBACK_TOTAL).counters().size());
        FunctionCounter conflicts = registry.get(GameSloMetrics.REWARD_IDEMPOTENT_CONFLICT)
                .tag(GameSloMetrics.TAG_ROUTE, GameSloRoute.REWARD_GRANT.tag())
                .functionCounter();
        assertNotNull(conflicts);
        for (GameSloRoute route : GameSloRoute.values())
        {
            if (route.family() != GameSloRoute.Family.REWARD)
            {
                for (GameSloResult result : GameSloResult.values())
                {
                    Timer timer = registry.find(timerName(route))
                            .tag(GameSloMetrics.TAG_ROUTE, route.tag())
                            .tag(GameSloMetrics.TAG_RESULT, result.tag())
                            .timer();
                    assertNotNull(timer, "时延序列必须预先注册：" + route.tag() + "/" + result.tag());
                }
            }
        }
    }

    @Test
    void businessFailureWithHttp200IsNeverSuccess()
    {
        // 传输层 200 + AjaxResult code=500：本仓库的「业务失败」形状，必须不是 success。
        AjaxResult businessFailure = new AjaxResult(500, "作品名须为 1 至 12 字");
        assertEquals(GameSloResult.FAULT, GameSloMetrics.classify(businessFailure));
        assertEquals(GameSloResult.REJECTED,
                GameSloMetrics.classify(new AjaxResult(500, "还没到这一站附近").put("errorCode", "ARRIVE_TOO_FAR")));
        assertEquals(GameSloResult.REJECTED,
                GameSloMetrics.classify(new AjaxResult(409, "请先确认本局规则").put("err", "ORIENT_ACK_REQUIRED")));
        assertEquals(GameSloResult.REJECTED, GameSloMetrics.classify(new AjaxResult(401, "请先登录")));
        assertEquals(GameSloResult.SUCCESS, GameSloMetrics.classify(AjaxResult.success()));
        assertEquals(GameSloResult.FAULT, GameSloMetrics.classify((AjaxResult) null));
    }

    @Test
    void exceptionsReuseCriticalPathClassification()
    {
        assertEquals(GameSloResult.REJECTED, GameSloMetrics.classify(new IllegalArgumentException("参数有误")));
        assertEquals(GameSloResult.REJECTED, GameSloMetrics.classify(new IllegalStateException("请先完成开局流程")));
        assertEquals(GameSloResult.REJECTED, GameSloMetrics.classify(new ServiceException("队伍与本场归属不匹配", 403)));
        assertEquals(GameSloResult.FAULT,
                GameSloMetrics.classify(new org.springframework.dao.CannotAcquireLockException("lock")));
        assertEquals(GameSloResult.FAULT, GameSloMetrics.classify(new NullPointerException()));
        assertEquals(GameSloResult.FAULT, GameSloMetrics.classify((Throwable) null));
    }

    @Test
    void recordsRealTimerSamplesAndRejectsMisroutedFamilies()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GameSloMetrics metrics = new GameSloMetrics(registry);

        metrics.recordStart(GameSloRoute.ADVANCED_START, GameSloResult.SUCCESS, TimeUnit.MILLISECONDS.toNanos(80));
        metrics.recordStart(GameSloRoute.ADVANCED_START, GameSloResult.SUCCESS, TimeUnit.MILLISECONDS.toNanos(120));
        metrics.recordArrive(GameSloResult.SUCCESS, TimeUnit.MILLISECONDS.toNanos(30));

        // 走错 family 的调用不得写出不属于该路径的序列。
        metrics.recordStart(GameSloRoute.ARRIVE, GameSloResult.SUCCESS, TimeUnit.MILLISECONDS.toNanos(1));

        Timer start = registry.find(GameSloMetrics.START_LATENCY)
                .tag(GameSloMetrics.TAG_ROUTE, GameSloRoute.ADVANCED_START.tag())
                .tag(GameSloMetrics.TAG_RESULT, GameSloResult.SUCCESS.tag())
                .timer();
        assertNotNull(start);
        assertEquals(2, start.count());
        assertEquals(2.0, registry.find(GameSloMetrics.START_TOTAL)
                .tag(GameSloMetrics.TAG_ROUTE, GameSloRoute.ADVANCED_START.tag())
                .tag(GameSloMetrics.TAG_RESULT, GameSloResult.SUCCESS.tag())
                .counter().count());

        Timer arrive = registry.find(GameSloMetrics.ARRIVE_LATENCY)
                .tag(GameSloMetrics.TAG_ROUTE, GameSloRoute.ARRIVE.tag())
                .tag(GameSloMetrics.TAG_RESULT, GameSloResult.SUCCESS.tag())
                .timer();
        assertEquals(1, arrive.count(), "arrive 只记自己那条路径");
        assertEquals(0.0, registry.find(GameSloMetrics.START_TOTAL)
                .tag(GameSloMetrics.TAG_ROUTE, GameSloRoute.ARRIVE.tag())
                .counters().stream().mapToDouble(counter -> counter.count()).sum(),
                "不得为 arrive 写出 start 入口的计数");
        assertNull(registry.find(GameSloMetrics.ARRIVE_LATENCY)
                .tag(GameSloMetrics.TAG_ROUTE, GameSloRoute.REWARD_GRANT.tag()).timer());
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
