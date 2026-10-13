package com.chengyinhub.quartz.util;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.exception.job.TaskException;
import com.chengyinhub.quartz.domain.SysJob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleUtilsTest
{
    private final Logger scheduleLogger = (Logger) LoggerFactory.getLogger(ScheduleUtils.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @AfterEach
    void tearDown()
    {
        scheduleLogger.detachAppender(logAppender);
    }

    @Test
    void cronWithoutFutureFireTimeThrowsBeforeSchedulerMutation() throws Exception
    {
        logAppender.start();
        scheduleLogger.addAppender(logAppender);
        Scheduler scheduler = mock(Scheduler.class);
        SysJob job = job("0 0 0 1 1 ? 2000", ScheduleConstants.Status.NORMAL.getValue());

        TaskException error = assertThrows(TaskException.class, () -> ScheduleUtils.createScheduleJob(scheduler, job));

        assertEquals(TaskException.Code.CONFIG_ERROR, error.getCode());
        List<ILoggingEvent> errors = logAppender.list;
        assertTrue(errors.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                && event.getFormattedMessage().contains("jobId=99")
                && event.getFormattedMessage().contains("0 0 0 1 1 ? 2000")));
        verify(scheduler, never()).checkExists(any(JobKey.class));
        verify(scheduler, never()).deleteJob(any(JobKey.class));
        verify(scheduler, never()).scheduleJob(any(), any());
    }

    @Test
    void validCronIsScheduledAndPausedWhenRequested() throws Exception
    {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);
        SysJob job = job("0/5 * * * * ?", ScheduleConstants.Status.PAUSE.getValue());

        ScheduleUtils.createScheduleJob(scheduler, job);

        verify(scheduler).scheduleJob(any(), any());
        verify(scheduler).pauseJob(ScheduleUtils.getJobKey(job.getJobId(), job.getJobGroup()));
    }

    static SysJob job(String cronExpression, String status)
    {
        SysJob job = new SysJob();
        job.setJobId(99L);
        job.setJobName("audit-job");
        job.setJobGroup("DEFAULT");
        job.setInvokeTarget("auditTask.run");
        job.setCronExpression(cronExpression);
        job.setMisfirePolicy(ScheduleConstants.MISFIRE_DEFAULT);
        job.setConcurrent("1");
        job.setStatus(status);
        return job;
    }
}
