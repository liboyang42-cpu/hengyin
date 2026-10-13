package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.mapper.BizFunnelDailyMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalyticsControllerTest {
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
