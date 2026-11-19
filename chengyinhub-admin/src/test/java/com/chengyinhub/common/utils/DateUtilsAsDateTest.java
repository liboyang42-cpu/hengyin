package com.chengyinhub.common.utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DateUtils.asDate:MyBatis Map 结果里的 DATETIME 在 MySQL Connector/J 8 下是 LocalDateTime,统一还原为 Date。 */
class DateUtilsAsDateTest {

    @Test
    void nullStaysNull() {
        assertNull(DateUtils.asDate(null));
    }

    @Test
    void dateAndTimestampAreReturnedAsIs() {
        Date date = new Date(1_700_000_000_000L);
        assertSame(date, DateUtils.asDate(date));
        java.sql.Timestamp ts = new java.sql.Timestamp(1_700_000_000_000L);
        assertSame(ts, DateUtils.asDate(ts));
    }

    @Test
    void localDateTimeUsesSystemDefaultZone() {
        LocalDateTime local = LocalDateTime.of(2026, 11, 19, 10, 30, 15);
        assertEquals(Date.from(local.atZone(ZoneId.systemDefault()).toInstant()), DateUtils.asDate(local));
    }

    @Test
    void localDateIsStartOfDayInSystemDefaultZone() {
        LocalDate day = LocalDate.of(2026, 11, 19);
        assertEquals(Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()), DateUtils.asDate(day));
    }

    @Test
    void offsetDateTimeAndInstantKeepTheirInstant() {
        Instant instant = Instant.ofEpochMilli(1_700_000_000_000L);
        assertEquals(new Date(1_700_000_000_000L), DateUtils.asDate(instant));
        assertEquals(new Date(1_700_000_000_000L), DateUtils.asDate(OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(8))));
    }

    @Test
    void unsupportedTypeFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> DateUtils.asDate("2026-11-19 10:30:00"));
        assertThrows(IllegalStateException.class, () -> DateUtils.asDate(1_700_000_000_000L));
    }
}
