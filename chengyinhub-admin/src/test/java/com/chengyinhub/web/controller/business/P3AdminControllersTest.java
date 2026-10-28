package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BizFunnelDaily;
import com.chengyinhub.business.domain.RecommendationScoreDaily;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.BizFunnelDailyMapper;
import com.chengyinhub.business.mapper.RecommendationScoreDailyMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class P3AdminControllersTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void auditReviewRejectsIncompleteRequest() {
        AuditWorkbenchController controller = new AuditWorkbenchController();

        AjaxResult result = controller.review(new AuditReviewDTO());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("审核参数不完整", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void auditListUsesServiceQuery() {
        IAuditTaskService service = mock(IAuditTaskService.class);
        AuditWorkbenchController controller = new AuditWorkbenchController();
        ReflectionTestUtils.setField(controller, "auditTaskService", service);
        when(service.selectWorkbenchQueueWithGuardScope(any(AuditTask.class), eq(Collections.emptyList()))).thenReturn(Collections.emptyList());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("pageNum", "1");
        request.setParameter("pageSize", "10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        TableDataInfo result = controller.list(new AuditTask());

        assertEquals(0, result.getRows().size());
        verify(service).selectWorkbenchQueueWithGuardScope(any(AuditTask.class), eq(Collections.emptyList()));
    }

    @Test
    void analyticsControllerProvidesFunnelEndpoint() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);
        when(mapper.selectBizFunnelDailyList(any(BizFunnelDaily.class))).thenReturn(Collections.singletonList(new BizFunnelDaily()));

        AjaxResult result = controller.funnel("2026-06-19", "2026-06-19");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(mapper).selectBizFunnelDailyList(any(BizFunnelDaily.class));
    }

    @Test
    void recommendationControllerProvidesScoreListEndpoint() {
        RecommendationScoreDailyMapper mapper = mock(RecommendationScoreDailyMapper.class);
        RecommendationController controller = new RecommendationController();
        ReflectionTestUtils.setField(controller, "recommendationScoreDailyMapper", mapper);
        when(mapper.selectAdminScoreList(any(RecommendationScoreDaily.class))).thenReturn(Collections.emptyList());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("pageNum", "1");
        request.setParameter("pageSize", "10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        TableDataInfo result = controller.listScores("topic", 20);

        assertEquals(0, result.getRows().size());
        verify(mapper).selectAdminScoreList(any(RecommendationScoreDaily.class));
    }
}
