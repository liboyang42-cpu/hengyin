package com.chengyinhub.web.service.play;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;

/**
 * BE-14 榜单作用域与周窗口的唯一口径。
 *
 * <p>周界按服务器时区切周：{@code Asia/Shanghai} 周一 00:00 起，到下周一 00:00 止（不含），
 * 与 {@code ClubMemberServiceImpl#currentWeekStart} 同一口径；只读区间，
 * 不改写任何历史周的事实，也不存在补算历史周的写路径。
 */
public final class ScopedLeaderboardPeriods {

    /** 服务端业务时区（与俱乐部贡献榜一致）。 */
    public static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");

    public static final String PERIOD_WEEK = "week";
    public static final String PERIOD_ALL = "all";

    private ScopedLeaderboardPeriods() {
    }

    /** 该时刻所属周的起点（周一 00:00，含）。 */
    public static Date weekStart(Instant now) {
        LocalDate monday = LocalDate.ofInstant(now, SERVER_ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return Date.from(monday.atStartOfDay(SERVER_ZONE).toInstant());
    }

    /** 该周窗口的终点（下周一 00:00，不含）。 */
    public static Date weekEnd(Date weekStart) {
        return Date.from(weekStart.toInstant().atZone(SERVER_ZONE).plusWeeks(1).toInstant());
    }
}
