package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TimeWindowScheduleTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode config(String from, String to, String zone) throws Exception {
        return json.readTree("{\"enabled\":true,\"openFrom\":\"" + from + "\",\"openTo\":\"" + to
                + "\",\"timeZone\":\"" + zone + "\"}");
    }
    private void boundary(String from, String to, String zone, String now, boolean open, String next, String close) throws Exception {
        JsonNode cfg = config(from, to, zone);
        Map<String, Object> result = TimeWindowSchedule.view(cfg, Instant.parse(now));
        assertEquals(open, result.get("openNow"));
        assertEquals(open, TimeWindowSchedule.isOpen(cfg, Instant.parse(now)));
        assertEquals(Instant.parse(next).toEpochMilli(), result.get("nextOpenAt"));
        assertEquals(Instant.parse(close).toEpochMilli(), result.get("nextCloseAt"));
    }
    @Test void daytimeHalfOpenBoundaries() throws Exception {
        boundary("09:00", "17:00", "Asia/Shanghai", "2026-10-02T00:59:59Z", false, "2026-10-02T01:00:00Z", "2026-10-02T09:00:00Z");
        boundary("09:00", "17:00", "Asia/Shanghai", "2026-10-02T01:00:00Z", true, "2026-10-03T01:00:00Z", "2026-10-02T09:00:00Z");
        boundary("09:00", "17:00", "Asia/Shanghai", "2026-10-02T09:00:00Z", false, "2026-10-03T01:00:00Z", "2026-10-03T09:00:00Z");
    }
    @Test void overnightUsesPreviousOpeningUntilExclusiveClose() throws Exception {
        boundary("23:00", "01:00", "Asia/Shanghai", "2026-10-01T16:30:00Z", true, "2026-10-02T15:00:00Z", "2026-10-01T17:00:00Z");
        boundary("23:00", "01:00", "Asia/Shanghai", "2026-10-01T17:00:00Z", false, "2026-10-02T15:00:00Z", "2026-10-02T17:00:00Z");
    }
    @Test void springGapDoesNotInventNonexistentOpening() throws Exception {
        boundary("02:10", "02:50", "America/New_York", "2026-03-08T06:59:00Z", false, "2026-03-09T06:10:00Z", "2026-03-09T06:50:00Z");
        boundary("02:30", "04:00", "America/New_York", "2026-03-08T06:59:00Z", false, "2026-03-08T07:00:00Z", "2026-03-08T08:00:00Z");
    }
    @Test void autumnOverlapHasTwoSeparateOpenings() throws Exception {
        boundary("01:15", "01:45", "America/New_York", "2026-11-01T05:20:00Z", true, "2026-11-01T06:15:00Z", "2026-11-01T05:45:00Z");
        boundary("01:15", "01:45", "America/New_York", "2026-11-01T05:50:00Z", false, "2026-11-01T06:15:00Z", "2026-11-01T06:45:00Z");
        boundary("00:30", "01:30", "America/New_York", "2026-11-01T05:50:00Z", false, "2026-11-01T06:00:00Z", "2026-11-01T06:30:00Z");
    }
    @Test void skippedCalendarDayAndYearBoundary() throws Exception {
        boundary("09:00", "10:00", "Pacific/Apia", "2011-12-30T09:00:00Z", false, "2011-12-30T19:00:00Z", "2011-12-30T20:00:00Z");
        boundary("23:00", "01:00", "Etc/UTC", "2026-12-31T23:30:00Z", true, "2027-01-01T23:00:00Z", "2027-01-01T01:00:00Z");
    }
    @Test void zoneAndConfigChangesInvalidateStableVersion() throws Exception {
        Instant now = Instant.parse("2026-10-02T12:00:00Z");
        String a = (String) TimeWindowSchedule.view(config("09:00", "17:00", "Asia/Shanghai"), now).get("windowVersion");
        assertEquals(a, TimeWindowSchedule.view(config("09:00", "17:00", "Asia/Shanghai"), now.plusSeconds(1)).get("windowVersion"));
        assertNotEquals(a, TimeWindowSchedule.view(config("09:00", "17:00", "Etc/UTC"), now).get("windowVersion"));
        assertNotEquals(a, TimeWindowSchedule.view(config("10:00", "17:00", "Asia/Shanghai"), now).get("windowVersion"));
    }
    @Test void legacyUsesActualServerZoneNotClientTimezone() throws Exception {
        TimeZone old = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            JsonNode cfg = json.readTree("{\"enabled\":true,\"openFrom\":\"09:00\",\"openTo\":\"17:00\"}");
            assertEquals("Asia/Shanghai", TimeWindowSchedule.view(cfg, Instant.parse("2026-10-02T02:00:00Z")).get("timeZone"));
        } finally { TimeZone.setDefault(old); }
    }
    @Test void invalidDirectDatabaseValuesFailClosed() throws Exception {
        for (String[] values : new String[][] {{"25:00","01:00","Etc/UTC"},{"23:00","23:00","Etc/UTC"},
                {"23:00","01:00","Bogus/Zone"},{"23:00","01:00","+08:00"},{"9:00","01:00","Etc/UTC"}}) {
            JsonNode cfg = config(values[0],values[1],values[2]);
            assertThrows(RuntimeException.class, () -> TimeWindowSchedule.view(cfg, Instant.now()));
            assertThrows(RuntimeException.class, () -> TimeWindowSchedule.isOpen(cfg, Instant.now()));
        }
    }
    @Test void disabledHasNoSchedulableTimestamp() throws Exception {
        Map<String, Object> view = TimeWindowSchedule.view(json.readTree("{}"), Instant.now());
        assertEquals(false, view.get("enabled")); assertNull(view.get("nextOpenAt")); assertNull(view.get("nextCloseAt"));
    }
}
