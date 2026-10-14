package com.chengyinhub.web.task;

import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.listeners.JobListenerSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class QuartzFailureAlarmConfigTest {
    @Test
    void onlyFailureAlertsWithoutSendingExceptionMessage() {
        AlarmService alarm = mock(AlarmService.class);
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDetail detail = mock(JobDetail.class);
        when(context.getJobDetail()).thenReturn(detail);
        when(detail.getKey()).thenReturn(new JobKey("TASK_17", "DEFAULT"));
        JobListenerSupport listener = QuartzFailureAlarmConfig.failureListener(alarm);
        listener.jobWasExecuted(context, null);
        verifyNoInteractions(alarm);
        listener.jobWasExecuted(context, new JobExecutionException(
                new IllegalStateException("private fixture must stay local")));
        verify(alarm).alarmThrottled("job.failure.default.task_17", "定时任务失败",
                "job=DEFAULT.TASK_17, errorType=IllegalStateException");
        doThrow(new IllegalStateException("delivery unavailable")).when(alarm)
                .alarmThrottled(anyString(), anyString(), anyString());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                listener.jobWasExecuted(context, new JobExecutionException("local failure")));
    }

    @Test
    void eventKeyIsAcceptedByAlarmThrottleAndBounded() {
        String key = QuartzFailureAlarmConfig.eventKey(new JobKey("TASK_CLASS_NAME" + "9".repeat(120), "财务组"));
        assertTrue(key.matches("[a-z][a-z0-9_.-]{0,79}"), key);
        assertEquals(80, key.length());
        assertTrue(QuartzFailureAlarmConfig.eventKey(null).matches("[a-z][a-z0-9_.-]{0,79}"));
    }
}
