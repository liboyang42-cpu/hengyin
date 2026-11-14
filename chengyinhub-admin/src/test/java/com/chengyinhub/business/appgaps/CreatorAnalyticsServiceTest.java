package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.service.IPostGameOutcomeReportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** U08-04: the creator board only ever asks the report for the caller's own topics and says what it left out. */
class CreatorAnalyticsServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IPostGameOutcomeReportService reports = mock(IPostGameOutcomeReportService.class);
    private final CreatorAnalyticsService service = new CreatorAnalyticsService(jdbc, reports);

    @Test
    void allOwnTopicsAreTheScopeAndTheOmittedMetricsAreDeclared() {
        when(jdbc.queryForList(startsWith("SELECT id FROM cms_topic WHERE member_id = ?"), eq(Long.class), eq(7L))).thenReturn(List.of(10L, 12L));
        Map<String, Object> out = service.postGameOutcome(7L, "2026-10-01", null, null);
        verify(reports).generateForTopics(LocalDate.parse("2026-10-01"), 30, List.of(10L, 12L));
        Map<?, ?> scope = (Map<?, ?>) out.get("scope");
        assertEquals(List.of(10L, 12L), scope.get("topicIds"));
        assertEquals(IPostGameOutcomeReportService.TOPIC_SCOPE_OMITTED_METRICS, scope.get("omittedMetrics"));
    }

    @Test
    void someoneElsesTopicIsNotFoundAndNoTopicsIsNotAnEmptyReport() {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());
        assertEquals("TOPIC_NOT_FOUND", assertThrows(AppGapException.class, () -> service.postGameOutcome(7L, "2026-10-01", "30", "11")).getErrorCode());
        assertEquals("CREATOR_TOPICS_NOT_FOUND", assertThrows(AppGapException.class, () -> service.postGameOutcome(7L, "2026-10-01", null, null)).getErrorCode());
        verifyNoInteractions(reports);
    }

    @Test
    void badInputIs400AndAnUnreadableSourceIs503NeverZero() {
        assertEquals(400, assertThrows(AppGapException.class, () -> service.postGameOutcome(7L, "2026-1-1", null, null)).getStatus());
        assertEquals(401, assertThrows(AppGapException.class, () -> service.postGameOutcome(null, "2026-10-01", null, null)).getStatus());
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(7L))).thenReturn(List.of(10L));
        when(reports.generateForTopics(any(), anyInt(), anyCollection())).thenThrow(new IllegalArgumentException("windowDays 仅支持 7/30/90"));
        assertEquals(400, assertThrows(AppGapException.class, () -> service.postGameOutcome(7L, "2026-10-01", "14", null)).getStatus());
        reset(reports);
        when(reports.generateForTopics(any(), anyInt(), anyCollection())).thenThrow(new IllegalStateException("source null"));
        assertEquals("REPORT_SOURCE_UNAVAILABLE", assertThrows(AppGapException.class, () -> service.postGameOutcome(7L, "2026-10-01", null, null)).getErrorCode());
    }
}
