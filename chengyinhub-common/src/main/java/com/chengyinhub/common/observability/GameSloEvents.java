package com.chengyinhub.common.observability;

import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPS-01 关键路径 SLO 的进程内事件：只记固定路由、固定结果与计数，不含会员、订单、位置、
 * openid、金额或异常 message。
 *
 * <p>和 O-11 的 {@link CriticalPathEvents} 分开：O-11 是「关键写路径失败事件」，本类只承载
 * 原卡 OPS-01 的四条路径 SLO。admin 的 GameSloMetrics 把这里的计数以 Micrometer 指标暴露；
 * 失败行只在 sys-error.log 留固定标记，供现有日志采集做阈值告警，不新建告警通道。
 *
 * <p>本类任何情况下都不向调用方抛异常——观测失败不能改变业务结果。
 */
public final class GameSloEvents
{
    public static final String MARKER = "[CY-SLO]";

    /** 独立 logger 名；仍在 com.chengyinhub 层级下，沿用现有 logback 级别与文件路由。 */
    private static final Logger log = LoggerFactory.getLogger("com.chengyinhub.slo");

    /** 完局奖励幂等冲突：只在唯一键冲突且无法证明「奖励已存在」时累加，正常重放不计。 */
    private static final LongAdder REWARD_IDEMPOTENT_CONFLICTS = new LongAdder();

    private GameSloEvents()
    {
    }

    /**
     * 记录一次完局奖励幂等冲突失败。
     *
     * <p>调用方必须已经区分「已有发放事实的正常重放」和「唯一键冲突导致本次发放失败」；
     * 正常重放不得调用本方法。
     */
    public static void rewardIdempotentConflict()
    {
        try
        {
            REWARD_IDEMPOTENT_CONFLICTS.increment();
            log.error("{} route=reward_grant result=idempotent_conflict", MARKER);
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // 观测不能反过来改变奖励事务结果。
        }
    }

    /** 进程内累计幂等冲突次数（自启动起，单调递增）。 */
    public static long rewardIdempotentConflicts()
    {
        return REWARD_IDEMPOTENT_CONFLICTS.sum();
    }

    /**
     * 为 SLO 失败写一条固定格式日志。route 只接受已登记的固定字面量，
     * 防止调用方把 actor/order/location 等业务值带进日志。
     */
    public static void fault(String route)
    {
        try
        {
            if (route == null || !route.matches("[a-z][a-z0-9_]{0,31}"))
            {
                return;
            }
            log.error("{} route={} result=fault", MARKER, route);
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // 同上：日志/观测失败不改变业务。
        }
    }
}
