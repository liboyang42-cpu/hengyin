package com.chengyinhub.quartz.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.exception.job.TaskException;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.mapper.SysJobMapper;
import com.chengyinhub.quartz.util.ScheduleUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysJobServiceImplSchedulingTest
{
    private Scheduler scheduler;
    private SysJobMapper mapper;
    private SysJobServiceImpl service;

    @BeforeEach
    void setUp()
    {
        scheduler = mock(Scheduler.class);
        mapper = mock(SysJobMapper.class);
        service = new SysJobServiceImpl();
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
        ReflectionTestUtils.setField(service, "jobMapper", mapper);
    }

    @Test
    void insertUpdateAndResumeRejectPastCronBeforeDatabaseMutation()
    {
        SysJob job = ScheduleUtilsTestFixture.pastJob();

        assertThrows(TaskException.class, () -> service.insertJob(job));
        assertThrows(TaskException.class, () -> service.updateJob(job));
        assertThrows(TaskException.class, () -> service.resumeJob(job));

        verify(mapper, never()).insertJob(any());
        verify(mapper, never()).updateJob(any());
    }

    @Test
    void resumeRecreatesMissingSchedulerJobInsteadOfReportingDatabaseOnlySuccess() throws Exception
    {
        SysJob job = ScheduleUtilsTestFixture.validJob();
        when(mapper.updateJob(job)).thenReturn(1);
        when(scheduler.checkExists(ScheduleUtils.getJobKey(job.getJobId(), job.getJobGroup()))).thenReturn(false);

        service.resumeJob(job);

        verify(scheduler).scheduleJob(any(), any());
        verify(scheduler, never()).resumeJob(any());
    }

    private static final class ScheduleUtilsTestFixture
    {
        private static SysJob pastJob()
        {
            return job("0 0 0 1 1 ? 2000");
        }

        private static SysJob validJob()
        {
            return job("0/5 * * * * ?");
        }

        private static SysJob job(String cron)
        {
            SysJob job = new SysJob();
            job.setJobId(99L);
            job.setJobName("audit-job");
            job.setJobGroup("DEFAULT");
            job.setInvokeTarget("auditTask.run");
            job.setCronExpression(cron);
            job.setMisfirePolicy(ScheduleConstants.MISFIRE_DEFAULT);
            job.setConcurrent("1");
            job.setStatus(ScheduleConstants.Status.NORMAL.getValue());
            return job;
        }
    }
}
