package com.chengyinhub.business.service.support;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * P7 daily-loop(GAM-04 / GRO-07):连续活跃天数 + 补签卡(streak freeze)的纯规则,无 IO,便于单测。
 *
 * <ul>
 *   <li>同一天再活跃:天数不变。</li>
 *   <li>隔天活跃:天数 +1。</li>
 *   <li>中间漏了 N 天:手里补签卡 &gt;= N 就自动用掉 N 张,天数照常 +1;不够就从 1 重新算,卡不动。</li>
 *   <li>每连续满 {@code freezeEveryDays} 天(天数是它的整数倍)得 1 张,最多攒 {@code freezeMax} 张。</li>
 * </ul>
 * 「自动用」是在下一次活跃或读的时候按日期差结算的,不需要夜间任务;读接口用 {@link #view} 展示同一口径。
 */
public final class StreakLedger
{
    private StreakLedger() {}

    /** 补签规则(来自配置,见 DailyLoopSettings)。freezeEveryDays &lt;= 0 或 freezeMax &lt;= 0 = 不发卡。 */
    public record Policy(int freezeEveryDays, int freezeMax) {}

    /** 持久化状态:最近活跃日、已记的连续天数、手里的补签卡。 */
    public record State(LocalDate lastActive, int streak, int freezes) {}

    /** 一次活跃之后的新状态。freezesUsed = 本次自动用掉的卡;freezeEarned = 本次是否新得一张。 */
    public record Outcome(int streak, int freezes, int freezesUsed, boolean freezeEarned, boolean advanced) {}

    /**
     * 读口径(不落库)。currentStreak 是「今天看起来」的连续天数:漏的天能被卡补上就保留,否则为 0。
     * freezesAvailable 已扣掉将要自动用掉的卡;atRisk = 今天还没活跃且连续天数 &gt; 0。
     */
    public record View(int currentStreak, boolean activeToday, boolean atRisk, int freezesAvailable,
                       int freezesPending, boolean broken) {}

    public static Outcome onActive(State state, LocalDate today, Policy policy)
    {
        int streak = Math.max(0, state.streak());
        int freezes = clampFreezes(state.freezes(), policy);
        LocalDate last = state.lastActive();
        if (last == null) {
            return earn(1, freezes, 0, true, policy);
        }
        long gap = ChronoUnit.DAYS.between(last, today);
        if (gap <= 0) {
            // 同一天(或时钟回拨):不推进、不发卡、不用卡。
            return new Outcome(Math.max(1, streak), freezes, 0, false, false);
        }
        if (gap == 1) {
            return earn(streak + 1, freezes, 0, true, policy);
        }
        long missed = gap - 1;
        if (streak > 0 && missed <= freezes) {
            return earn(streak + 1, freezes - (int) missed, (int) missed, true, policy);
        }
        // 断了:从 1 开始,手里的卡留着(下一段连续还能用)。
        return earn(1, freezes, 0, true, policy);
    }

    public static View view(State state, LocalDate today, Policy policy)
    {
        int streak = Math.max(0, state.streak());
        int freezes = clampFreezes(state.freezes(), policy);
        LocalDate last = state.lastActive();
        if (last == null || streak == 0) {
            return new View(0, false, false, freezes, 0, false);
        }
        long gap = ChronoUnit.DAYS.between(last, today);
        if (gap <= 0) {
            return new View(streak, true, false, freezes, 0, false);
        }
        if (gap == 1) {
            return new View(streak, false, true, freezes, 0, false);
        }
        long missed = gap - 1;
        if (missed <= freezes) {
            return new View(streak, false, true, freezes - (int) missed, (int) missed, false);
        }
        return new View(0, false, false, freezes, 0, true);
    }

    /** 下一枚连续徽章还差的天数阈值(ladder 升序);已全部达成返回 null。 */
    public static Integer nextLadderStep(int currentStreak, int[] ladder)
    {
        if (ladder == null) return null;
        for (int step : ladder) {
            if (step > currentStreak) return step;
        }
        return null;
    }

    private static Outcome earn(int streak, int freezes, int used, boolean advanced, Policy policy)
    {
        boolean earned = false;
        if (advanced && policy.freezeEveryDays() > 0 && policy.freezeMax() > 0
                && streak > 0 && streak % policy.freezeEveryDays() == 0 && freezes < policy.freezeMax()) {
            freezes += 1;
            earned = true;
        }
        return new Outcome(streak, freezes, used, earned, advanced);
    }

    private static int clampFreezes(int freezes, Policy policy)
    {
        return Math.max(0, Math.min(freezes, Math.max(0, policy.freezeMax())));
    }
}
