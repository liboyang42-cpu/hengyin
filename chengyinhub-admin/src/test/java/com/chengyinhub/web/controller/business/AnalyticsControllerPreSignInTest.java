package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.telemetry.InstallTelemetryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P6 measurement:登录前漏斗与旧 /funnel 共用 D3 窗口合同。 */
class AnalyticsControllerPreSignInTest {

    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-11-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private AnalyticsController controller(InstallTelemetryService service) {
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "installTelemetryService", service);
        ReflectionTestUtils.setField(controller, "clock", TODAY);
        return controller;
    }

    @Test
    void defaultsToLast30DaysAndReturnsServiceData() {
        InstallTelemetryService service = mock(InstallTelemetryService.class);
        Map<String, Object> data = Map.of("steps", java.util.List.of());
        when(service.preSignInFunnel(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-11-15"))).thenReturn(data);

        AjaxResult result = controller(service).preSignInFunnel(null, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertSame(data, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void rejectsBadOrOversizedWindowsWithoutQuerying() {
        InstallTelemetryService service = mock(InstallTelemetryService.class);
        AnalyticsController controller = controller(service);
        assertEquals(500, controller.preSignInFunnel("2026-13-01", null).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.preSignInFunnel("2026-11-10", "2026-11-01").get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.preSignInFunnel("2026-01-01", "2026-11-01").get(AjaxResult.CODE_TAG));
        verify(service, never()).preSignInFunnel(any(), any());
    }
}
