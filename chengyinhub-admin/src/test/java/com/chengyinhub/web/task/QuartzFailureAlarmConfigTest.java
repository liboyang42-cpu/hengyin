package com.chengyinhub.web.task;

import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.listeners.JobListenerSupport;

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
        listener.jobWasExecuted(context, new JobExecutionException("private fixture must stay local"));
        verify(alarm).alarm("定时任务失败", "job=DEFAULT.TASK_17, errorType=JobExecutionException");
        doThrow(new IllegalStateException("delivery unavailable")).when(alarm).alarm(anyString(), anyString());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                listener.jobWasExecuted(context, new JobExecutionException("local failure")));
    }
}
