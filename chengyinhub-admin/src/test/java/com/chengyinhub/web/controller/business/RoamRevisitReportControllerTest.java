package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.RoamRevisitFact;
import com.chengyinhub.business.domain.vo.RoamRevisitReportVO;
import com.chengyinhub.business.mapper.RoamRevisitReportMapper;
import com.chengyinhub.business.service.IRoamRevisitReportService;
import com.chengyinhub.business.service.impl.RoamRevisitReportServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RoamRevisitReportControllerTest {

    @Test
    void getTraversesRealReadServiceAndOnlyCallsTheReportMapper() {
        RoamRevisitReportMapper reportMapper = mock(RoamRevisitReportMapper.class);
        LocalDate cohortDate = LocalDate.of(2026, 8, 8);
        when(reportMapper.selectShopDataCutoffAt()).thenReturn(at("2026-08-15T23:00:00Z"));
        when(reportMapper.selectShopVisitFacts(any(Date.class), any(Date.class))).thenReturn(Arrays.asList(
                new RoamRevisitFact(1L, 100L, 1, 10L, at("2026-08-08T09:00:00Z")),
                new RoamRevisitFact(1L, 101L, 1, 10L, at("2026-08-10T09:00:00Z"))
        ));

        RoamRevisitReportServiceImpl reportService = new RoamRevisitReportServiceImpl(reportMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(reportService, "clock",
                java.time.Clock.fixed(java.time.Instant.parse("2026-08-16T00:00:00Z"),
                        java.time.ZoneId.of("Asia/Shanghai")));
        RoamRevisitReportController controller = new RoamRevisitReportController(reportService);
        AjaxResult result = controller.get(cohortDate, RoamRevisitReportVO.Metric.SHOP_REVISIT, 7);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        RoamRevisitReportVO report = (RoamRevisitReportVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, report.getEligibleMembers());
        assertEquals(1, report.getRevisitedMembers());
        verify(reportMapper).selectShopDataCutoffAt();
        verify(reportMapper).selectShopVisitFacts(any(Date.class), any(Date.class));
        verifyNoMoreInteractions(reportMapper);
    }

    @Test
    void controllerAndServiceDependencyGraphIsReadOnlyAndFailClosed() throws Exception {
        assertDeclaredDependencyTypes(RoamRevisitReportController.class,
                Collections.singleton(IRoamRevisitReportService.class));
        assertDeclaredDependencyTypes(RoamRevisitReportServiceImpl.class,
                new HashSet<>(Arrays.asList(RoamRevisitReportMapper.class, java.time.Clock.class)));

        Method generate = RoamRevisitReportServiceImpl.class.getMethod(
                "generate", LocalDate.class, RoamRevisitReportVO.Metric.class, int.class);
        Transactional transactional = generate.getAnnotation(Transactional.class);
        assertNotNull(transactional, "报表服务必须声明只读事务");
        assertTrue(transactional.readOnly(), "报表服务事务不得退化为可写");
    }

    @Test
    void queryFailureRemainsExplicitAndIsNotConvertedToZero() {
        IRoamRevisitReportService reportService = mock(IRoamRevisitReportService.class);
        LocalDate cohortDate = LocalDate.of(2026, 8, 8);
        when(reportService.generate(cohortDate, RoamRevisitReportVO.Metric.ROAM_REVISIT, 30))
                .thenThrow(new IllegalStateException("read failed"));

        RoamRevisitReportController controller = new RoamRevisitReportController(reportService);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> controller.get(cohortDate, RoamRevisitReportVO.Metric.ROAM_REVISIT, 30));

        assertEquals("read failed", failure.getMessage());
    }

    private static void assertDeclaredDependencyTypes(Class<?> type, Set<Class<?>> expected) {
        Set<Class<?>> actual = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                actual.add(field.getType());
            }
        }
        assertEquals(expected, actual, type.getSimpleName() + " 只能依赖只读链；新增写依赖必须先红");
    }

    private static Date at(String instant) {
        return Date.from(Instant.parse(instant));
    }
}
