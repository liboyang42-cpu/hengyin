package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedTimeWindowTest {
    private static long at(String local) {
        return ZonedDateTime.parse(local + "+08:00[Asia/Shanghai]").toInstant().toEpochMilli();
    }

    @Test
    void sameDayWindowBeforeDuringAndAfter() {
        Map<String, Object> before = AdvancedTimeWindow.compute("10:00", "12:00", at("2026-11-13T09:00:00"));
        assertEquals(false, before.get("openNow"));
        assertEquals(at("2026-11-13T10:00:00"), before.get("nextOpenAt"));
        assertEquals(at("2026-11-13T12:00:00"), before.get("nextCloseAt"));

        Map<String, Object> during = AdvancedTimeWindow.compute("10:00", "12:00", at("2026-11-13T11:00:00"));
        assertEquals(true, during.get("openNow"));
        assertEquals(at("2026-11-14T10:00:00"), during.get("nextOpenAt"));
        assertEquals(at("2026-11-13T12:00:00"), during.get("nextCloseAt"));

        Map<String, Object> after = AdvancedTimeWindow.compute("10:00", "12:00", at("2026-11-13T12:00:00"));
        assertEquals(false, after.get("openNow"), "closing minute is closed, as start() rules");
        assertEquals(at("2026-11-14T10:00:00"), after.get("nextOpenAt"));
        assertEquals(at("2026-11-14T12:00:00"), after.get("nextCloseAt"));
    }

    @Test
    void overnightWindow() {
        Map<String, Object> late = AdvancedTimeWindow.compute("23:00", "01:00", at("2026-11-13T23:30:00"));
        assertEquals(true, late.get("openNow"));
        assertEquals(at("2026-11-14T01:00:00"), late.get("nextCloseAt"));
        Map<String, Object> noon = AdvancedTimeWindow.compute("23:00", "01:00", at("2026-11-13T12:00:00"));
        assertEquals(false, noon.get("openNow"));
        assertEquals(at("2026-11-13T23:00:00"), noon.get("nextOpenAt"));
        assertEquals(at("2026-11-14T01:00:00"), noon.get("nextCloseAt"));
        long server = (Long) noon.get("serverNow");
        assertTrue((Long) noon.get("nextOpenAt") > server && (Long) noon.get("nextCloseAt") > (Long) noon.get("nextOpenAt"));
        assertEquals("LOCAL_ONLY", noon.get("notificationProvider"));
        assertEquals(noon.get("windowVersion"), late.get("windowVersion"));
        assertNotEquals(noon.get("windowVersion"), AdvancedTimeWindow.compute("22:00", "01:00", at("2026-11-13T12:00:00")).get("windowVersion"));
    }

    @Test
    void malformedWindowFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> AdvancedTimeWindow.compute("10:00", "10:00", 0));
        assertThrows(IllegalArgumentException.class, () -> AdvancedTimeWindow.compute("1000", "12:00", 0));
        assertThrows(IllegalArgumentException.class, () -> AdvancedTimeWindow.compute("24:00", "12:00", 0));
        assertEquals(-1, AdvancedTimeWindow.minuteOfDay(null));
    }
}
