package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.BizFunnelDaily;
import com.chengyinhub.business.mapper.BizFunnelDailyMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalyticsControllerTest {
    @Test
    void funnelPreservesInitialNullDatesAndResponse() {
        assertFunnelQuery(null, null, Collections.emptyMap(), Collections.emptyList());
    }

    @Test
    void funnelPreservesClearedAndBlankDates() {
        String[][] dates = {{"", ""}, {" ", "\t"}, {null, ""}, {"", null}};
        for (String[] pair : dates) {
            assertFunnelQuery(pair[0], pair[1], Collections.emptyMap(), Collections.emptyList());
        }
    }

    @Test
    void funnelSupportsStartOnlyDateQueries() {
        for (String endDate : new String[] {null, "", " "}) {
            assertFunnelQuery("2026-10-15", endDate,
                    Collections.singletonMap("beginStatDate", "2026-10-15"),
                    Collections.emptyList());
        }
    }

    @Test
    void funnelSupportsEndOnlyDateQueries() {
        for (String startDate : new String[] {null, "", " "}) {
            assertFunnelQuery(startDate, "2026-10-15",
                    Collections.singletonMap("endStatDate", "2026-10-15"),
                    Collections.emptyList());
        }
    }

    @Test
    void funnelAcceptsLeapDayAndEqualBoundaries() {
        Map<String, Object> params = new HashMap<>();
        params.put("beginStatDate", "2024-02-29");
        params.put("endStatDate", "2024-02-29");

        assertFunnelQuery("2024-02-29", "2024-02-29", params,
                Collections.singletonList(new BizFunnelDaily()));
    }

    @Test
    void funnelAcceptsPositiveFourDigitYearBoundaries() {
        Map<String, Object> params = new HashMap<>();
        params.put("beginStatDate", "0001-01-01");
        params.put("endStatDate", "9999-12-31");

        assertFunnelQuery("0001-01-01", "9999-12-31", params, Collections.emptyList());
    }

    @Test
    void funnelPreservesLongWindowsAndAllRows() {
        List<BizFunnelDaily> rows = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            rows.add(new BizFunnelDaily());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("beginStatDate", "2020-01-01");
        params.put("endStatDate", "2026-10-15");

        assertFunnelQuery("2020-01-01", "2026-10-15", params, rows);
    }

    @Test
    void funnelRejectsMalformedAndImpossibleDatesBeforeMapperAccess() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);
        String[] invalidDates = {"not-a-date", "2026-02-29", "2024-02-30", "2026-13-01",
                "2026-00-01", "2026-10-00", "2026-1-01", "2026/10/15",
                "2026-10-15T00:00:00", " 2026-10-15", "2026-10-15 ",
                "0000-01-01", "-0001-01-01", "+10000-01-01", "+09999-01-01"};
        for (String invalidDate : invalidDates) {
            assertEquals(500, controller.funnel(invalidDate, null).get(AjaxResult.CODE_TAG));
            assertEquals(500, controller.funnel(null, invalidDate).get(AjaxResult.CODE_TAG));
            assertEquals(500, controller.funnel(invalidDate, "2026-10-15")
                    .get(AjaxResult.CODE_TAG));
            assertEquals(500, controller.funnel("2026-10-15", invalidDate)
                    .get(AjaxResult.CODE_TAG));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void funnelRejectsReversedWindowBeforeMapperAccess() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);

        AjaxResult result = controller.funnel("2026-10-16", "2026-10-15");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("开始日期不能晚于结束日期", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(mapper);
    }

    @SuppressWarnings("unchecked")
    private void assertFunnelQuery(String startDate, String endDate,
                                  Map<String, Object> expectedParams, List<BizFunnelDaily> rows) {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);
        when(mapper.selectBizFunnelDailyList(any(BizFunnelDaily.class))).thenReturn(rows);

        AjaxResult result = controller.funnel(startDate, endDate);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(3, data.size());
        assertEquals(startDate, data.get("startDate"));
        assertEquals(endDate, data.get("endDate"));
        assertSame(rows, data.get("rows"));
        ArgumentCaptor<BizFunnelDaily> query = ArgumentCaptor.forClass(BizFunnelDaily.class);
        verify(mapper).selectBizFunnelDailyList(query.capture());
        assertEquals(expectedParams, query.getValue().getParams());
    }

    @SuppressWarnings("unchecked")
    @Test
    void qualifiedPilotReturnsCrossDayDeduplicatedMetric() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);
        Map<String, Object> metric = Collections.singletonMap("qualified_user_count", 68L);
        when(mapper.selectCommunityQualifiedPilotMetric("2026-09-01", "2026-09-28"))
                .thenReturn(metric);

        AjaxResult result = controller.communityQualifiedPilot("2026-09-01", "2026-09-28");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(metric, data.get("metric"));
        assertEquals("CURRENT_NON_ERASED_POST_AUTHOR", data.get("authorCoverageSemantics"));
        verify(mapper).selectCommunityQualifiedPilotMetric("2026-09-01", "2026-09-28");
    }

    @Test
    void qualifiedPilotKeepsInclusiveOneTo62DayBoundaries() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);

        assertEquals(200, controller.communityQualifiedPilot("2026-07-01", "2026-07-01")
                .get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.communityQualifiedPilot("2026-07-01", "2026-08-31")
                .get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.communityQualifiedPilot("2026-07-01", "2026-09-01")
                .get(AjaxResult.CODE_TAG));
        verify(mapper).selectCommunityQualifiedPilotMetric("2026-07-01", "2026-07-01");
        verify(mapper).selectCommunityQualifiedPilotMetric("2026-07-01", "2026-08-31");
    }

    @Test
    void qualifiedPilotRejectsInvalidYearsBeforeMapperAccess() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);
        String[][] windows = {
                {"0000-01-01", "0000-01-01"},
                {"0000-01-01", "0000-01-02"},
                {"-0001-01-01", "-0001-01-01"},
                {"-0001-01-01", "-0001-01-02"},
                {"+10000-01-01", "+10000-01-01"},
                {"+10000-01-01", "+10000-01-02"},
                {"+09999-01-01", "+09999-01-01"},
                {"+09999-01-01", "+09999-01-02"},
                {"0000-12-31", "0001-01-01"},
                {"9999-12-31", "+10000-01-01"},
                {"+09999-01-01", "9999-01-02"},
                {"9999-01-01", "+09999-01-02"}
        };
        for (String[] window : windows) {
            AjaxResult result = controller.communityQualifiedPilot(window[0], window[1]);

            assertEquals(500, result.get(AjaxResult.CODE_TAG));
            assertEquals("日期格式必须为 yyyy-MM-dd", result.get(AjaxResult.MSG_TAG));
        }
        verifyNoInteractions(mapper);
    }

    @SuppressWarnings("unchecked")
    @Test
    void qualifiedPilotPreservesValidFourDigitYearsAndResponseContract() {
        String[][] windows = {
                {"0001-01-01", "0001-01-01"},
                {"0999-12-01", "0999-12-31"},
                {"9999-01-01", "9999-03-03"}
        };
        for (String[] window : windows) {
            BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
            AnalyticsController controller = new AnalyticsController();
            ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);
            Map<String, Object> metric = Collections.singletonMap("qualified_user_count", 68L);
            when(mapper.selectCommunityQualifiedPilotMetric(window[0], window[1])).thenReturn(metric);

            AjaxResult result = controller.communityQualifiedPilot(window[0], window[1]);

            assertEquals(200, result.get(AjaxResult.CODE_TAG));
            Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
            assertEquals(5, data.size());
            assertEquals(window[0], data.get("startDate"));
            assertEquals(window[1], data.get("endDate"));
            assertEquals("DISTINCT_USER_POST_ACROSS_WINDOW", data.get("deduplication"));
            assertEquals("CURRENT_NON_ERASED_POST_AUTHOR", data.get("authorCoverageSemantics"));
            assertSame(metric, data.get("metric"));
            verify(mapper).selectCommunityQualifiedPilotMetric(window[0], window[1]);
        }
    }

    @Test
    void qualifiedPilotRejectsMissingReversedOrOversizedWindow() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);

        assertEquals(500, controller.communityQualifiedPilot(null, "2026-09-28")
                .get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.communityQualifiedPilot("2026-09-28", "2026-09-01")
                .get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.communityQualifiedPilot("2026-01-01", "2026-09-28")
                .get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.communityQualifiedPilot("not-a-date", "2026-09-28")
                .get(AjaxResult.CODE_TAG));
        verifyNoInteractions(mapper);
    }
}
