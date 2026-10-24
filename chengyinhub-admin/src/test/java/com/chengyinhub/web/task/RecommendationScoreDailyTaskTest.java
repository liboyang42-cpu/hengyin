package com.chengyinhub.web.task;

import com.chengyinhub.business.service.IRecommendationScoreDailyService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationScoreDailyTaskTest {

    @Test
    void usesShanghaiCalendarDay() {
        IRecommendationScoreDailyService service = mock(IRecommendationScoreDailyService.class);
        // 2026-10-21T16:30Z = 上海 10-22 00:30
        Clock clock = Clock.fixed(Instant.parse("2026-10-21T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
        when(service.computeDaily(LocalDate.of(2026, 10, 22))).thenReturn(result(0));

        new RecommendationScoreDailyTask(service, clock).run();

        verify(service).computeDaily(LocalDate.of(2026, 10, 22));
    }

    @Test
    void memberFailuresLeaveTheJobFailedSoItCanBeRerun() {
        IRecommendationScoreDailyService service = mock(IRecommendationScoreDailyService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        when(service.computeDaily(LocalDate.of(2026, 10, 22))).thenReturn(result(2));

        assertThrows(IllegalStateException.class, () -> new RecommendationScoreDailyTask(service, clock).run());
    }

    private static Map<String, Integer> result(int failures) {
        Map<String, Integer> map = new HashMap<>();
        map.put("members", 1);
        map.put("rows", 1);
        map.put("failures", failures);
        map.put("purged", 0);
        return map;
    }
}
