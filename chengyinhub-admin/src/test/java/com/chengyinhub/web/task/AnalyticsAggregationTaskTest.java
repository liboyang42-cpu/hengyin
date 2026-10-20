package com.chengyinhub.web.task;

import com.chengyinhub.business.service.IAnalyticsEventService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AnalyticsAggregationTaskTest {
    @Test
    void oneFailedDateDoesNotPreventLaterDatesAndSchedulerStillSeesFailure() {
        IAnalyticsEventService service = mock(IAnalyticsEventService.class);
        org.mockito.Mockito.when(service.aggregateDaily("2026-08-27"))
                .thenThrow(new IllegalStateException("date failure"));
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T08:00:00Z"), ZoneOffset.UTC);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new AnalyticsAggregationTask(service, clock).aggregateRecentWindow());
        verify(service).aggregateDaily("2026-09-09");
        verify(service, times(15)).aggregateDaily(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void recomputesTheWholeAcceptedLateArrivalWindowInTheConfiguredServerZone() {
        IAnalyticsEventService service = mock(IAnalyticsEventService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T08:00:00Z"), ZoneOffset.UTC);

        new AnalyticsAggregationTask(service, clock).aggregateRecentWindow();

        verify(service).aggregateDaily("2026-09-09");
        verify(service).aggregateDaily("2026-08-26");
        verify(service, times(15)).aggregateDaily(org.mockito.ArgumentMatchers.anyString());
    }
}
