package com.chengyinhub.business.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** D-93:自然日边界按 Asia/Shanghai 计,与 JVM 默认时区无关。 */
class MerchantMetricPeriodTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restore() { TimeZone.setDefault(original); }

    private static Date sh(String isoLocal) {
        return Date.from(ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), MerchantMetricPeriod.ZONE).toInstant());
    }

    private static MerchantMetricPeriod periodAt(String isoLocal, int days) {
        return MerchantMetricPeriod.resolve(days,
                Clock.fixed(Instant.from(ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), MerchantMetricPeriod.ZONE)),
                        MerchantMetricPeriod.ZONE));
    }

    @Test
    void justAfterMidnightIsToday_andLastSecondBelongsToPreviousDay() {
        for (String zone : new String[]{"UTC", "Asia/Shanghai", "America/Los_Angeles"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            MerchantMetricPeriod p = periodAt("2026-11-12T10:00:00", 7);
            assertEquals(6, p.currentDayIndex(sh("2026-11-12T00:00:05")), zone + " 今天 00:00:05 落今天(下标6)");
            assertEquals(5, p.currentDayIndex(sh("2026-11-11T23:59:59")), zone + " 昨天 23:59:59 落昨天(下标5)");
            assertEquals(0, p.currentDayIndex(sh("2026-11-06T00:00:01")), zone + " 窗首日 00:00:01");
            assertEquals(-1, p.currentDayIndex(sh("2026-11-05T23:59:59")), zone + " 窗前一秒不在本期");
            assertTrue(p.inPrevious(sh("2026-11-05T23:59:59")), zone + " 窗前一秒属上期");
            assertEquals(-1, p.currentDayIndex(sh("2026-11-13T00:00:00")), zone + " 明天 0 点不在本期");
            assertFalse(p.inCurrent(null));
        }
    }

    @Test
    void windowStartsAtShanghaiMidnightWhenNowIsLateUtcEvening() {
        // 17:46 UTC = 上海次日 01:46:窗口今天是上海 11-13
        MerchantMetricPeriod p = MerchantMetricPeriod.resolve(7,
                Clock.fixed(Instant.parse("2026-11-12T17:46:00Z"), MerchantMetricPeriod.ZONE));
        assertEquals(java.time.LocalDate.parse("2026-11-13"), p.getToday());
        assertEquals(6, p.currentDayIndex(sh("2026-11-13T00:00:05")));
    }
}
