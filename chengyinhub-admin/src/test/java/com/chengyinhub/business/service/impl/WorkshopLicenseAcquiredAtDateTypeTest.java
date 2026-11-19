package com.chengyinhub.business.service.impl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工坊授权视图 acquiredAt:paid_at 来自 Map 结果,MySQL Connector/J 8 回 LocalDateTime。
 * 旧代码只认 Date,LocalDateTime 落到 toString,对外输出不带时区的本地时间串而不是 ISO 瞬时。
 */
class WorkshopLicenseAcquiredAtDateTypeTest {

    private static String instant(Object value) throws Exception {
        Method m = WorkshopService.class.getDeclaredMethod("instant", Object.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value);
    }

    @Test
    void dateAndLocalDateTimeRenderTheSameIsoInstant() throws Exception {
        LocalDateTime paidLocal = LocalDateTime.of(2026, 11, 19, 10, 30, 0);
        Date paidDate = Date.from(paidLocal.atZone(ZoneId.systemDefault()).toInstant());

        String fromDate = instant(paidDate);
        assertEquals(paidDate.toInstant().toString(), fromDate);
        assertEquals(fromDate, instant(paidLocal));
    }

    @Test
    void missingPaidAtRendersEmpty() throws Exception {
        assertEquals("", instant(null));
    }
}
