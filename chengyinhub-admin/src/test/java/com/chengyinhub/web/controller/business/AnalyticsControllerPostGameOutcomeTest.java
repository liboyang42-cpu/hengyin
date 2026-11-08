package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.vo.PostGameOutcomeReportVO;
import com.chengyinhub.business.service.IPostGameOutcomeReportService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只读复报端点的参数与失败语义。
 *
 * <p>锁三件事：①读权限不带来写端点；②参数非法直接拒；③数据源不可读返回业务错误，绝不返回 0 报表。
 */
class AnalyticsControllerPostGameOutcomeTest {

    private IPostGameOutcomeReportService reportService;
    private AnalyticsController controller;

    @BeforeEach
    void setUp() {
        reportService = mock(IPostGameOutcomeReportService.class);
        controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "postGameOutcomeReportService", reportService);
    }

    @Test
    void defaultsToThirtyDayWindow() {
        PostGameOutcomeReportVO report = report();
        when(reportService.generate(LocalDate.parse("2026-10-01"), 30)).thenReturn(report);

        AjaxResult result = controller.postGameOutcome("2026-10-01", null);

        assertEquals(Integer.valueOf(200), result.get(AjaxResult.CODE_TAG));
        verify(reportService).generate(LocalDate.parse("2026-10-01"), 30);
        assertSame(report, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void passesExplicitWindowThrough() {
        when(reportService.generate(any(LocalDate.class), eq(7))).thenReturn(report());

        AjaxResult result = controller.postGameOutcome("2026-10-01", 7);

        assertEquals(Integer.valueOf(200), result.get(AjaxResult.CODE_TAG));
        verify(reportService).generate(LocalDate.parse("2026-10-01"), 7);
    }

    @Test
    void blankCohortDateIsRejectedWithoutCallingService() {
        AjaxResult result = controller.postGameOutcome("  ", 30);

        assertEquals(Integer.valueOf(500), result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("cohortDate"));
        verify(reportService, never()).generate(any(LocalDate.class), anyInt());
    }

    @Test
    void malformedCohortDateIsRejected() {
        assertEquals(Integer.valueOf(500), controller.postGameOutcome("2026/10/01", 30).get(AjaxResult.CODE_TAG));
        assertEquals(Integer.valueOf(500), controller.postGameOutcome("2026-1-1", 30).get(AjaxResult.CODE_TAG));
    }

    @Test
    void unsupportedWindowIsReportedAsBusinessError() {
        when(reportService.generate(any(LocalDate.class), eq(14)))
                .thenThrow(new IllegalArgumentException("windowDays 仅支持 7/30/90"));

        AjaxResult result = controller.postGameOutcome("2026-10-01", 14);

        assertEquals(Integer.valueOf(500), result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("7/30/90"));
    }

    @Test
    void unreadableSourceYieldsErrorInsteadOfZeroReport() {
        when(reportService.generate(any(LocalDate.class), anyInt()))
                .thenThrow(new IllegalStateException("原账本毛额查询返回 null，拒绝把未知收入报告为 0"));

        AjaxResult result = controller.postGameOutcome("2026-10-01", 30);

        assertEquals(Integer.valueOf(500), result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("拒绝把未知"));
        assertEquals(null, result.get(AjaxResult.DATA_TAG), "失败不得带报表载荷");
    }

    @Test
    void successPayloadCarriesComputedAtAndIsNotReplayable() {
        when(reportService.generate(any(LocalDate.class), anyInt())).thenReturn(report());

        AjaxResult result = controller.postGameOutcome("2026-10-01", 30);
        PostGameOutcomeReportVO data = (PostGameOutcomeReportVO) result.get(AjaxResult.DATA_TAG);

        assertEquals(Integer.valueOf(200), result.get(AjaxResult.CODE_TAG));
        assertEquals(false, data.isHistoricalReplayable());
        assertEquals(PostGameOutcomeReportVO.BASIS_VERSION, data.getBasisVersion());
        assertNotNull(data.getComputedAt());
    }

    @Test
    void reportEndpointsRemainReadOnly() {
        // 契约锁定：本域只增 GET 只读端点。快照写端未获批准，不得出现在本控制器里。
        long postMappings = Arrays.stream(AnalyticsController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PostMapping.class))
                .count();
        long putMappings = Arrays.stream(AnalyticsController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PutMapping.class))
                .count();
        long deleteMappings = Arrays.stream(AnalyticsController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.DeleteMapping.class))
                .count();
        assertEquals(0L, postMappings, "复报报表不得新增写端点");
        assertEquals(0L, putMappings);
        assertEquals(0L, deleteMappings);
    }

    private static PostGameOutcomeReportVO report() {
        Date now = new Date();
        PostGameOutcomeReportVO.Cohort cohort = new PostGameOutcomeReportVO.Cohort(
                1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L, 0L, 0L, 0L, 0L,
                new java.math.BigDecimal("1.0000"), null);
        return new PostGameOutcomeReportVO("2026-10-01", 30, true, now, now, now,
                Map.of("cms_registration.payment_time", now), cohort, Map.of(), null);
    }
}
