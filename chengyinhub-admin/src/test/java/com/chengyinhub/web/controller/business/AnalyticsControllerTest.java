package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.BizFunnelDaily;
import com.chengyinhub.business.mapper.BizFunnelDailyMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
    // owner 2026-10-23 D3(P0-48):旧合同「日期可选、不限窗口」作废。新合同:
    //   都不传 = 最近 30 天(含今天,Asia/Shanghai);只传一端 = 从该端起算 30 天;窗口(含首尾)最多 90 天。
    private static final Clock TODAY_2026_10_23 = Clock.fixed(
            Instant.parse("2026-10-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void funnelDefaultsMissingDatesToLast30Days() {
        assertFunnelQuery(null, null, "2026-09-24", "2026-10-23", Collections.emptyList());
    }

    @Test
    void funnelTreatsClearedAndBlankDatesAsMissing() {
        String[][] dates = {{"", ""}, {" ", "\t"}, {null, ""}, {"", null}};
        for (String[] pair : dates) {
            assertFunnelQuery(pair[0], pair[1], "2026-09-24", "2026-10-23", Collections.emptyList());
        }
    }

    @Test
    void funnelDefaultTodayFollowsShanghaiCalendarDay() {
        // 2026-10-23T17:00Z = 上海 10-24 01:00 —— 「今天」按上海算
        Clock lateUtc = Clock.fixed(Instant.parse("2026-10-23T17:00:00Z"), ZoneId.of("Asia/Shanghai"));
        assertFunnelQuery(lateUtc, null, null, "2026-09-25", "2026-10-24", Collections.emptyList());
    }

    @Test
    void funnelStartOnlyQueriesThirtyDaysFromStart() {
        for (String endDate : new String[] {null, "", " "}) {
            assertFunnelQuery("2026-10-15", endDate, "2026-10-15", "2026-11-13", Collections.emptyList());
        }
    }

    @Test
    void funnelEndOnlyQueriesThirtyDaysEndingAtEnd() {
        for (String startDate : new String[] {null, "", " "}) {
            assertFunnelQuery(startDate, "2026-10-15", "2026-09-16", "2026-10-15", Collections.emptyList());
        }
    }

    @Test
    void funnelAcceptsLeapDayAndEqualBoundaries() {
        assertFunnelQuery("2024-02-29", "2024-02-29", "2024-02-29", "2024-02-29",
                Collections.singletonList(new BizFunnelDaily()));
    }

    @Test
    void funnelAcceptsExactly90DaysAtFourDigitYearBoundaries() {
        assertFunnelQuery("0001-01-01", "0001-03-31", "0001-01-01", "0001-03-31", Collections.emptyList());
        assertFunnelQuery("9999-10-03", "9999-12-31", "9999-10-03", "9999-12-31", Collections.emptyList());
    }

    @Test
    void funnelPreservesAllRowsInsideA90DayWindow() {
        List<BizFunnelDaily> rows = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            rows.add(new BizFunnelDaily());
        }
        assertFunnelQuery("2026-07-26", "2026-10-23", "2026-07-26", "2026-10-23", rows);
    }

    @Test
    void funnelRejectsWindowsOver90DaysBeforeMapperAccess() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = controller(mapper, TODAY_2026_10_23);
        String[][] windows = {{"2026-07-25", "2026-10-23"}, {"2020-01-01", "2026-10-15"},
                {"0001-01-01", "9999-12-31"}};
        for (String[] window : windows) {
            AjaxResult result = controller.funnel(window[0], window[1]);
            assertEquals(500, result.get(AjaxResult.CODE_TAG));
            assertEquals("查询窗口不能超过90天", result.get(AjaxResult.MSG_TAG));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void funnelRejectsDerivedWindowOutsideFourDigitYearsBeforeMapperAccess() {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = controller(mapper, TODAY_2026_10_23);
        assertEquals("日期超出可查询范围", controller.funnel(null, "0001-01-01").get(AjaxResult.MSG_TAG));
        assertEquals("日期超出可查询范围", controller.funnel("9999-12-31", null).get(AjaxResult.MSG_TAG));
        verifyNoInteractions(mapper);
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

    private static AnalyticsController controller(BizFunnelDailyMapper mapper, Clock clock) {
        AnalyticsController controller = new AnalyticsController();
        ReflectionTestUtils.setField(controller, "bizFunnelDailyMapper", mapper);
        ReflectionTestUtils.setField(controller, "clock", clock);
        return controller;
    }

    private void assertFunnelQuery(String startDate, String endDate, String expectedStart, String expectedEnd,
                                   List<BizFunnelDaily> rows) {
        assertFunnelQuery(TODAY_2026_10_23, startDate, endDate, expectedStart, expectedEnd, rows);
    }

    @SuppressWarnings("unchecked")
    private void assertFunnelQuery(Clock clock, String startDate, String endDate, String expectedStart,
                                   String expectedEnd, List<BizFunnelDaily> rows) {
        BizFunnelDailyMapper mapper = mock(BizFunnelDailyMapper.class);
        AnalyticsController controller = controller(mapper, clock);
        when(mapper.selectBizFunnelDailyList(any(BizFunnelDaily.class))).thenReturn(rows);

        AjaxResult result = controller.funnel(startDate, endDate);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(3, data.size());
        assertEquals(expectedStart, data.get("startDate"));
        assertEquals(expectedEnd, data.get("endDate"));
        assertSame(rows, data.get("rows"));
        ArgumentCaptor<BizFunnelDaily> query = ArgumentCaptor.forClass(BizFunnelDaily.class);
        verify(mapper).selectBizFunnelDailyList(query.capture());
        Map<String, Object> expectedParams = new HashMap<>();
        expectedParams.put("beginStatDate", expectedStart);
        expectedParams.put("endStatDate", expectedEnd);
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
