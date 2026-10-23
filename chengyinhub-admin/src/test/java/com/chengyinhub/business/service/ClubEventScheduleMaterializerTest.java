package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubEventScheduleMaterializerTest {

    private final ClubEventScheduleMaterializer materializer = new ClubEventScheduleMaterializer();

    @Test
    void weeklyMaterializationFillsTheNinetyDayWindowExactly() {
        ClubEventSeriesRequest request = request("WEEKLY");
        request.setStartDate("2026-08-24");
        request.setOccurrenceCount(13);

        List<LocalDate> dates = materializer.materialize(request, LocalDate.of(2026, 8, 23));

        assertEquals(13, dates.size(), "90 天窗口内恰好 13 个每周场次");
        assertEquals(LocalDate.of(2026, 8, 24), dates.get(0));
        assertEquals(LocalDate.of(2026, 11, 16), dates.get(12));
    }

    @Test
    void weeklyRequestBeyondNinetyDayWindowIsRejectedInsteadOfSilentlyTruncated() {
        ClubEventSeriesRequest request = request("WEEKLY");
        request.setStartDate("2026-08-24");
        for (int requested : new int[] {14, 64}) {
            request.setOccurrenceCount(requested);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> materializer.materialize(request, LocalDate.of(2026, 8, 23)),
                    "请求 " + requested + " 期不能静默只排 13 期");
            assertTrue(error.getMessage().contains("最多可排 13 期"), error.getMessage());
        }
    }

    @Test
    void customDatesAreSortedDeduplicatedAndCannotCrossNinetyDayWindow() {
        ClubEventSeriesRequest request = request("CUSTOM_DATES");
        request.setCustomDates(Arrays.asList("2026-09-02", "2026-08-25", "2026-09-02"));

        assertEquals(Arrays.asList(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 2)),
                materializer.materialize(request, LocalDate.of(2026, 8, 23)));

        request.setCustomDates(Arrays.asList("2026-11-22"));
        ServiceException error = assertThrows(ServiceException.class,
                () -> materializer.materialize(request, LocalDate.of(2026, 8, 23)));
        assertTrue(error.getMessage().contains("90"));
    }

    @Test
    void customRruleIsExplicitlyUnavailableWithoutApprovedAdapter() {
        ClubEventSeriesRequest request = request("CUSTOM_RRULE");
        request.setRrule("FREQ=MONTHLY;BYDAY=MO");

        ServiceException error = assertThrows(ServiceException.class,
                () -> materializer.materialize(request, LocalDate.of(2026, 8, 23)));

        assertTrue(error.getMessage().contains("UNAVAILABLE"));
    }

    private ClubEventSeriesRequest request(String recurrenceType) {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setClubId(21L);
        request.setTopicId(31L);
        request.setRecurrenceType(recurrenceType);
        return request;
    }
}
