package com.chengyinhub.quartz.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
import com.chengyinhub.quartz.util.MySqlSessionLock;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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
    private MySqlSchedulerCoordinator coordinator;
    private Object originalFactory;
    public static class Target { public void run() { } }

    @BeforeEach
    void setUp()
    {
        scheduler = mock(Scheduler.class);
        mapper = mock(SysJobMapper.class);
        service = new SysJobServiceImpl();
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
        ReflectionTestUtils.setField(service, "jobMapper", mapper);
        coordinator = mock(MySqlSchedulerCoordinator.class);
        when(coordinator.definitionLock()).thenReturn(mock(MySqlSessionLock.Lease.class));
        when(coordinator.isLeader()).thenReturn(true);
        when(mapper.selectJobAllForUpdate()).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "coordinator", coordinator);
        originalFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("auditTask", new Target());
        factory.registerAlias("auditTask", "auditAlias");
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", factory);
    }

    @AfterEach void restoreFactory() { ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", originalFactory); }

    @Test void pausedAliasDuplicateIsRejectedWithoutDeletingOrChangingEitherPlan() throws Exception {
        SysJob existing = ScheduleUtilsTestFixture.validJob(); existing.setJobId(1L); existing.setStatus("1");
        SysJob candidate = ScheduleUtilsTestFixture.validJob(); candidate.setInvokeTarget("auditAlias.run()");
        when(mapper.selectJobAll()).thenReturn(List.of(existing, candidate));
        assertThrows(ServiceException.class, service::init);
        when(mapper.selectJobAllForUpdate()).thenReturn(List.of(existing));
        assertThrows(ServiceException.class, () -> service.insertJob(candidate));
        assertThrows(ServiceException.class, () -> service.updateJob(candidate));
        assertThrows(ServiceException.class, () -> service.resumeJob(candidate));
        verify(mapper, never()).insertJob(any()); verify(mapper, never()).updateJob(any());
        verify(mapper, never()).deleteJobById(any()); verifyNoInteractions(scheduler);
        assertEquals("1", existing.getStatus()); assertEquals("0/5 * * * * ?", existing.getCronExpression());
    }

    @Test void builtinDuplicateIsRejectedBeforeWriting() {
        SysJob candidate = ScheduleUtilsTestFixture.validJob();
        when(coordinator.isBuiltin(anyString())).thenReturn(true);
        assertThrows(ServiceException.class, () -> service.insertJob(candidate));
        verify(mapper, never()).insertJob(any());
    }

    @Test void queuedInvocationRechecksCommittedPauseDeleteAndTargetButManualIsExplicit() {
        SysJob scheduled = ScheduleUtilsTestFixture.validJob();
        SysJob current = ScheduleUtilsTestFixture.validJob(); current.setStatus("1");
        when(mapper.selectJobById(99L)).thenReturn(current);
        assertFalse(service.isCurrentInvocation(scheduled, false));
        assertTrue(service.isCurrentInvocation(scheduled, true));
        current.setInvokeTarget("auditTask.missing()");
        assertThrows(ServiceException.class, () -> service.isCurrentInvocation(scheduled, true));
        when(mapper.selectJobById(99L)).thenReturn(null);
        assertFalse(service.isCurrentInvocation(scheduled, true));
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
