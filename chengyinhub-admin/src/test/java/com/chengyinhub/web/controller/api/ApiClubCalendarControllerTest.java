package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ClubCalendarService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiClubCalendarControllerTest {
    @Test
    void identityComesOnlyFromExistingAppIdentity() {
        ClubCalendarService service = mock(ClubCalendarService.class);
        ApiClubCalendarController controller = new ApiClubCalendarController() {
            @Override public Long getAppUserId() { return 2L; }
        };
        ReflectionTestUtils.setField(controller, "calendarService", service);
        when(service.month(7L, 2L, "2028-02", null, null)).thenReturn(Collections.singletonMap("visibilityScope", "CLUB_MEMBER"));
        AjaxResult response = controller.calendar(7L, "2028-02", null, null);
        assertEquals(200, response.get("code"));
        verify(service).month(7L, 2L, "2028-02", null, null);
    }

    @Test
    void anonymousAndInvalidCursorNeverBecomeUnscopedOrSuccessfulFailure() {
        ClubCalendarService service = mock(ClubCalendarService.class);
        ApiClubCalendarController controller = new ApiClubCalendarController() {
            @Override public Long getAppUserId() { throw new IllegalStateException("not logged in"); }
        };
        ReflectionTestUtils.setField(controller, "calendarService", service);
        when(service.month(7L, null, "2028-02", 1L, 2L)).thenThrow(new IllegalArgumentException("月份或可见场次已变化，请重新读取"));
        assertNotEquals(200, controller.calendar(7L, "2028-02", 1L, 2L).get("code"));
        verify(service).month(7L, null, "2028-02", 1L, 2L);
    }

    @Test
    void databaseFailureIsNotAnEmptyMonth() {
        ClubCalendarService service = mock(ClubCalendarService.class);
        ApiClubCalendarController controller = new ApiClubCalendarController() {
            @Override public Long getAppUserId() { return 2L; }
        };
        ReflectionTestUtils.setField(controller, "calendarService", service);
        when(service.month(7L, 2L, "2028-02", null, null)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> controller.calendar(7L, "2028-02", null, null));
    }
}
