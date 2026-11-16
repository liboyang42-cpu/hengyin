package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** E-D4:时段门按业务时区(Asia/Shanghai)判断,不随 JVM 默认时区漂移。 */
class TimeWindowBusinessZoneTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 2026-03-31T23:33:20Z = 上海 2026-04-01 07:33。 */
    private static final Date NOW = new Date(1_775_000_000_000L);

    private static void within(String from, String to) throws Exception {
        AdvancedGameRuntimeServiceImpl service = new AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> NOW);
        Object window = JSON.readTree("{\"enabled\":true,\"openFrom\":\"" + from + "\",\"openTo\":\"" + to + "\"}");
        ReflectionTestUtils.invokeMethod(service, "requireWithinTimeWindow", window);
    }

    private static void inZone(String zone, org.junit.jupiter.api.function.Executable body) throws Throwable {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            body.execute();
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void 默认时区是洛杉矶时仍按上海时间判断开放() throws Throwable {
        inZone("America/Los_Angeles", () -> assertDoesNotThrow(() -> within("07:00", "08:00")));
        inZone("America/Los_Angeles", () ->
                assertThrows(IllegalStateException.class, () -> within("15:00", "18:00")));
    }

    @Test
    void 默认时区是UTC时同样按上海时间() throws Throwable {
        inZone("UTC", () -> assertDoesNotThrow(() -> within("07:00", "08:00")));
        inZone("UTC", () -> assertThrows(IllegalStateException.class, () -> within("23:00", "23:59")));
    }

    @Test
    void 跨夜窗口按上海时间() throws Throwable {
        inZone("Pacific/Auckland", () -> assertDoesNotThrow(() -> within("23:00", "08:00")));
    }
}
