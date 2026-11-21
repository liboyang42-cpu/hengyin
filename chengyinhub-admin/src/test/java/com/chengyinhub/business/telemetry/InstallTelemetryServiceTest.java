package com.chengyinhub.business.telemetry;

import com.chengyinhub.business.telemetry.mapper.InstallTelemetryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstallTelemetryServiceTest {

    private static final long NOW = 1_763_200_000_000L;
    private static final String INSTALL = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String EVENT = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private InstallTelemetryMapper mapper;
    private InstallTelemetryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(InstallTelemetryMapper.class);
        service = new InstallTelemetryService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        when(mapper.insertEvent(anyString(), anyString(), anyString(), any(), anyString(), anyString(), any(Date.class)))
                .thenReturn(1);
    }

    private static String batch(String events) {
        return "{\"installId\":\"" + INSTALL + "\",\"platform\":\"ios\",\"appVersion\":\"1.4.0 (230)\","
                + "\"events\":[" + events + "]}";
    }

    private static String event(String name, String props) {
        return "{\"eventId\":\"" + EVENT + "\",\"name\":\"" + name + "\",\"occurredAt\":" + NOW
                + (props == null ? "" : ",\"props\":" + props) + "}";
    }

    @Test
    void acceptsEveryWhitelistedFirstSessionEvent() {
        String body = batch(String.join(",",
                event("app_open", "{\"launch\":\"cold\"}"),
                event("onboarding_step", "{\"step\":\"welcome\",\"action\":\"done\"}"),
                event("permission_prompt", "{\"permission\":\"location\"}"),
                event("permission_result", "{\"permission\":\"camera\",\"result\":\"denied\"}"),
                event("sign_in_start", "{\"method\":\"apple\"}"),
                event("sign_in_failure", "{\"method\":\"phone\",\"reason\":\"code_invalid\"}"),
                event("sign_in_success", "{\"method\":\"wechat\"}"),
                event("first_walk", null),
                event("first_fog_reveal", "{}"),
                event("first_reward", "{\"kind\":\"stamp\"}"),
                event("first_share", "{\"channel\":\"system_sheet\"}")));
        assertEquals(11, service.recordAnonymousBatch(body, NOW));
        verify(mapper).insertEvent(eq(INSTALL), eq(EVENT), eq("permission_result"),
                eq("{\"permission\":\"camera\",\"result\":\"denied\"}"), eq("ios"), eq("1.4.0 (230)"), any(Date.class));
        verify(mapper).insertEvent(eq(INSTALL), eq(EVENT), eq("first_walk"), isNull(), eq("ios"), anyString(),
                any(Date.class));
    }

    @Test
    void rejectsUnknownEventsKeysValuesAndPersonalData() {
        String[] bad = {
                batch(event("purchase", null)),
                batch(event("app_open", "{\"launch\":\"hot\"}")),
                batch(event("app_open", "{\"launch\":\"cold\",\"phone\":\"13800000000\"}")),
                batch(event("first_reward", "{\"kind\":\"Free Text With Spaces\"}")),
                batch(event("first_reward", "{\"kind\":{\"nested\":1}}")),
                batch(event("first_walk", "{\"lat\":\"30.1\"}")),
                batch(event("first_walk", null).replace("}", ",\"latitude\":30.1}")),
                batch(event("first_walk", null)).replace("\"platform\"", "\"memberId\":1,\"platform\""),
                batch(event("first_walk", null)).replace(INSTALL, "not-a-uuid"),
                batch(event("first_walk", null)).replace("\"ios\"", "\"android\""),
                batch(event("first_walk", null).replace(EVENT, "abc")),
                batch(event("first_walk", null).replace(String.valueOf(NOW), String.valueOf(NOW + 600_000))),
                batch(event("first_walk", null).replace(String.valueOf(NOW), String.valueOf(NOW - 15L * 86_400_000))),
                batch(""),
                "[]",
                "not json",
                null
        };
        for (String body : bad) {
            assertThrows(InstallEventSchema.Rejected.class, () -> service.recordAnonymousBatch(body, NOW),
                    String.valueOf(body));
        }
        verify(mapper, never()).insertEvent(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
                any(Date.class));
    }

    @Test
    void rejectsOversizedBatches() {
        StringBuilder events = new StringBuilder();
        for (int i = 0; i <= InstallEventSchema.MAX_BATCH_SIZE; i++) {
            if (i > 0) {
                events.append(',');
            }
            events.append(event("first_walk", null));
        }
        assertThrows(InstallEventSchema.Rejected.class, () -> service.recordAnonymousBatch(batch(events.toString()), NOW));
    }

    @Test
    void disabledSwitchValidatesButStoresNothing() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals(0, service.recordAnonymousBatch(batch(event("first_walk", null)), NOW));
        assertFalse(service.link(INSTALL, 9L));
        verify(mapper, never()).insertLink(anyString(), anyLong());
    }

    @Test
    void linkValidatesInstallIdAndMember() {
        when(mapper.insertLink(INSTALL, 9L)).thenReturn(1);
        assertTrue(service.link(INSTALL, 9L));
        assertThrows(InstallEventSchema.Rejected.class, () -> service.link("x", 9L));
        assertThrows(InstallEventSchema.Rejected.class, () -> service.link(INSTALL, 0L));
        assertThrows(InstallEventSchema.Rejected.class, () -> service.link(null, 9L));
    }

    private static String metric(String kind, String payload) {
        return "{\"installId\":\"" + INSTALL + "\",\"kind\":\"" + kind + "\",\"appVersion\":\"1.4.0 (230)\","
                + "\"osVersion\":\"iOS 18.2\",\"payload\":" + payload + "}";
    }

    @Test
    void metricPayloadIsStoredWithinCaps() {
        when(mapper.insertMetricPayload(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(1);
        assertEquals(1, service.recordMetricPayload(metric("diagnostic", "{\"crashDiagnostics\":[]}"), NOW));
        verify(mapper).insertMetricPayload(eq(INSTALL), eq("diagnostic"), eq("1.4.0 (230)"), eq("iOS 18.2"),
                eq("{\"crashDiagnostics\":[]}"), eq(23));
    }

    @Test
    void metricPayloadRejectsBadShapeAndSize() {
        String huge = "{\"x\":\"" + "a".repeat(InstallTelemetryService.MAX_METRIC_PAYLOAD_BYTES) + "\"}";
        String[] bad = {
                metric("crash", "{}"),
                metric("metric", "[]"),
                metric("metric", "\"text\""),
                metric("metric", "{}").replace("\"kind\"", "\"memberId\":3,\"kind\""),
                metric("metric", "{}").replace("iOS 18.2", "iOS <script>"),
                metric("metric", huge),
                null
        };
        for (String body : bad) {
            assertThrows(InstallEventSchema.Rejected.class, () -> service.recordMetricPayload(body, NOW));
        }
        verify(mapper, never()).insertMetricPayload(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt());
    }

    @Test
    void metricPayloadDailyCapDropsQuietly() {
        when(mapper.countMetricPayloadsSince(eq(INSTALL), any(Date.class)))
                .thenReturn(InstallTelemetryService.MAX_METRIC_PAYLOADS_PER_DAY);
        assertEquals(0, service.recordMetricPayload(metric("metric", "{}"), NOW));
        verify(mapper, never()).insertMetricPayload(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt());
    }

    @SuppressWarnings("unchecked")
    @Test
    void preSignInFunnelListsEveryStepInOrderAndFillsZeros() {
        when(mapper.selectEventCounts(any(Date.class), any(Date.class))).thenReturn(List.of(
                Map.of("eventName", "app_open", "installCount", 40L, "eventCount", 95L),
                Map.of("eventName", "sign_in_success", "installCount", 12L, "eventCount", 12L)));
        when(mapper.countLinkedInstalls(any(Date.class), any(Date.class))).thenReturn(11L);

        Map<String, Object> data = service.preSignInFunnel(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-14"));

        List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertEquals(InstallEventSchema.FUNNEL_ORDER.size(), steps.size());
        assertEquals("app_open", steps.get(0).get("stepName"));
        assertEquals(1, steps.get(0).get("stepOrder"));
        assertEquals(40L, steps.get(0).get("installCount"));
        Map<String, Object> walk = steps.stream().filter(s -> "first_walk".equals(s.get("stepName"))).findFirst()
                .orElseThrow();
        assertEquals(0L, walk.get("installCount"));
        assertEquals(11L, data.get("linkedInstallCount"));
        assertEquals("2026-11-01", data.get("startDate"));
        assertEquals("2026-11-14", data.get("endDate"));
        assertNull(data.get("rows"));
    }
}
