package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IClubEventNotificationService;
import com.chengyinhub.business.service.IClubWaitlistService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class ClubEventOpsTaskTest {
    @Test
    void oneBoundedTaskMaintainsWaitlistThenRetriesDueDeliveries() {
        IClubWaitlistService waitlist = mock(IClubWaitlistService.class);
        IClubEventNotificationService notifications = mock(IClubEventNotificationService.class);
        ClubEventOpsTask task = new ClubEventOpsTask(waitlist, notifications);

        task.run();

        ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
        verify(waitlist).maintain(now.capture());
        assertNotNull(now.getValue());
        verify(notifications).retryDue();
    }

    @Test
    void waitlistFailureDoesNotBlockNotificationRetry() {
        IClubWaitlistService waitlist = mock(IClubWaitlistService.class);
        IClubEventNotificationService notifications = mock(IClubEventNotificationService.class);
        doThrow(new IllegalStateException("poison waitlist row"))
                .when(waitlist).maintain(org.mockito.ArgumentMatchers.any(Date.class));
        ClubEventOpsTask task = new ClubEventOpsTask(waitlist, notifications);

        task.run();

        verify(notifications).retryDue();
    }

    @Test
    void notificationFailureDoesNotEscapeQuartzTask() {
        IClubWaitlistService waitlist = mock(IClubWaitlistService.class);
        IClubEventNotificationService notifications = mock(IClubEventNotificationService.class);
        doThrow(new IllegalStateException("poison notification campaign"))
                .when(notifications).retryDue();
        ClubEventOpsTask task = new ClubEventOpsTask(waitlist, notifications);

        assertDoesNotThrow(task::run);

        verify(notifications).retryDue();
    }
}
