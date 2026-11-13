package com.chengyinhub.business.service.support;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * D-93(2026-11-11 owner:营销首页和店铺参谋指标合并成一套):两页共有指标唯一的时间窗口径。
 *
 * <p>「近 N 天」= Asia/Shanghai 的 N 个自然日,含今天,到明天 0 点为止(半开区间
 * {@code [currentStart, endExclusive)});上期是紧挨着的前 N 个自然日 {@code [previousStart, currentStart)}。
 * 营销首页 analytics 与店铺参谋 trends/attribution 都只从这里取窗口,
 * 不再各自用 JVM 默认时区或滚动 N×24h 现算。
 */
public final class MerchantMetricPeriod {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 近 N 个自然日,含今天。 */
    public static final String BASIS = "DAYS_INCL_TODAY";

    private final int days;
    private final LocalDate today;
    private final LocalDate currentStartDay;
    private final Date previousStart;
    private final Date currentStart;
    private final Date endExclusive;

    private MerchantMetricPeriod(int days, LocalDate today) {
        this.days = days;
        this.today = today;
        ZonedDateTime end = today.plusDays(1).atStartOfDay(ZONE);
        ZonedDateTime current = end.minusDays(days);
        ZonedDateTime previous = current.minusDays(days);
        this.currentStartDay = current.toLocalDate();
        this.previousStart = Date.from(previous.toInstant());
        this.currentStart = Date.from(current.toInstant());
        this.endExclusive = Date.from(end.toInstant());
    }

    public static MerchantMetricPeriod resolve(int days, Clock clock) {
        if (days < 1) throw new IllegalArgumentException("windowDays必须为正");
        return new MerchantMetricPeriod(days, clock.instant().atZone(ZONE).toLocalDate());
    }

    /** 事件时刻在 Asia/Shanghai 的自然日;null 入 null 出。 */
    public static LocalDate localDay(Date at) {
        return at == null ? null : at.toInstant().atZone(ZONE).toLocalDate();
    }

    public boolean inCurrent(Date at) {
        return at != null && !at.before(currentStart) && at.before(endExclusive);
    }

    public boolean inPrevious(Date at) {
        return at != null && !at.before(previousStart) && at.before(currentStart);
    }

    /** 本期内的逐日下标(0 = 窗口首日);不在本期返回 -1。 */
    public int currentDayIndex(Date at) {
        if (!inCurrent(at)) return -1;
        return (int) ChronoUnit.DAYS.between(currentStartDay, localDay(at));
    }

    public int getDays() { return days; }
    public LocalDate getToday() { return today; }
    public LocalDate getCurrentStartDay() { return currentStartDay; }
    public Date getPreviousStart() { return previousStart; }
    public Date getCurrentStart() { return currentStart; }
    public Date getEndExclusive() { return endExclusive; }
}
