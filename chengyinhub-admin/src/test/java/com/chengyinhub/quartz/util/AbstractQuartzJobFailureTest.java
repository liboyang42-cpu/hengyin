package com.chengyinhub.quartz.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.domain.SysJobLog;
import com.chengyinhub.quartz.service.ISysJobLogService;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
import com.chengyinhub.quartz.service.impl.SysJobServiceImpl;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractQuartzJobFailureTest
{
    private ISysJobLogService jobLogService;
    private JobExecutionContext context;
    private MySqlSchedulerCoordinator coordinator;
    private SysJobServiceImpl jobs;
    private ConfigurableListableBeanFactory originalBeanFactory;

    @BeforeEach
    void setUp()
    {
        originalBeanFactory = (ConfigurableListableBeanFactory) ReflectionTestUtils.getField(SpringUtils.class,
                "beanFactory");
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        jobLogService = mock(ISysJobLogService.class);
        when(beanFactory.getBean(ISysJobLogService.class)).thenReturn(jobLogService);
        coordinator = mock(MySqlSchedulerCoordinator.class);
        jobs = mock(SysJobServiceImpl.class);
        when(beanFactory.getBean(MySqlSchedulerCoordinator.class)).thenReturn(coordinator);
        when(beanFactory.getBean(SysJobServiceImpl.class)).thenReturn(jobs);
        when(jobs.isCurrentInvocation(any(), anyBoolean())).thenReturn(true);
        when(coordinator.execute(anyString(), nullable(java.util.Date.class), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(2, Runnable.class).run(); return true;
        });
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", beanFactory);

        context = mock(JobExecutionContext.class);
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(ScheduleConstants.TASK_PROPERTIES, job());
        when(context.getMergedJobDataMap()).thenReturn(dataMap);
    }

    @AfterEach
    void tearDown()
    {
        MDC.clear();
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", originalBeanFactory);
    }

    @Test
    void taskFailureWritesOneFailedJobLogAndRemainsObservableToQuartz()
    {
        JobExecutionException thrown = assertThrows(JobExecutionException.class,
                () -> new StubJob(new IllegalStateException("task-boom")).execute(context));

        assertTrue(thrown.getCause() instanceof IllegalStateException);
        ArgumentCaptor<SysJobLog> captor = ArgumentCaptor.forClass(SysJobLog.class);
        verify(jobLogService).addJobLog(captor.capture());
        assertEquals(Constants.FAIL, captor.getValue().getStatus());
        assertEquals("IllegalStateException", captor.getValue().getExceptionInfo());
        assertEquals("task-boom", thrown.getCause().getMessage(), "original caller exception is unchanged");
        assertTrue(captor.getValue().getJobMessage().contains("jobRunId="));
    }

    @Test
    void successfulTaskWritesOneSuccessLogWithoutThrowing()
    {
        assertDoesNotThrow(() -> new StubJob(null).execute(context));

        ArgumentCaptor<SysJobLog> captor = ArgumentCaptor.forClass(SysJobLog.class);
        verify(jobLogService).addJobLog(captor.capture());
        assertEquals(Constants.SUCCESS, captor.getValue().getStatus());
    }

    @Test
    void jobLogPersistenceFailureIsThrownOnceInsteadOfBeingRetriedAndHidden()
    {
        doThrow(new IllegalStateException("job-log-db-down")).when(jobLogService).addJobLog(any(SysJobLog.class));

        JobExecutionException thrown = assertThrows(JobExecutionException.class,
                () -> new StubJob(null).execute(context));

        assertTrue(thrown.getCause() instanceof IllegalStateException);
        verify(jobLogService).addJobLog(any(SysJobLog.class));
    }

    @Test
    void nonLeaderAndCommittedPauseDoNotWriteFakeSuccessLogs() {
        when(coordinator.execute(anyString(), nullable(java.util.Date.class), any(Runnable.class))).thenReturn(false);
        assertDoesNotThrow(() -> new StubJob(new IllegalStateException("must-not-run")).execute(context));
        verifyNoInteractions(jobLogService);
        when(coordinator.execute(anyString(), nullable(java.util.Date.class), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(2, Runnable.class).run(); return true;
        });
        when(jobs.isCurrentInvocation(any(), anyBoolean())).thenReturn(false);
        assertDoesNotThrow(() -> new StubJob(new IllegalStateException("must-not-run")).execute(context));
        verifyNoInteractions(jobLogService);
    }

    @Test
    void failedInvocationKeepsItsRunInJobLogAndRestoresWorkerContext()
    {
        MDC.put("requestId", "previous_123"); MDC.put("commandId", "another-command");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        AtomicReference<Map<String, String>> during = new AtomicReference<>();
        assertThrows(JobExecutionException.class, () -> new StubJob(null) {
            @Override protected void doExecute(JobExecutionContext context, SysJob job) {
                during.set(MDC.getCopyOfContextMap());
                throw new IllegalStateException("private-payload-fixture");
            }
        }.execute(context));
        String run = during.get().get("jobRunId");
        assertTrue(run.matches("[0-9a-f]{32}"));
        assertEquals(run, during.get().get("requestId"));
        assertEquals("99", during.get().get("jobId"));
        assertEquals(previous, MDC.getCopyOfContextMap());
        verify(context).put(QuartzJobCorrelation.RUN_ATTRIBUTE, run);
        ArgumentCaptor<SysJobLog> captor = ArgumentCaptor.forClass(SysJobLog.class);
        verify(jobLogService).addJobLog(captor.capture());
        assertTrue(captor.getValue().getJobMessage().contains("jobRunId=" + run));
        assertEquals("IllegalStateException", captor.getValue().getExceptionInfo());
    }

    @Test
    void skippedInvocationRestoresContextWithoutWritingSuccess()
    {
        MDC.put("requestId", "previous_123");
        when(coordinator.execute(anyString(), nullable(java.util.Date.class), any(Runnable.class))).thenReturn(false);
        assertDoesNotThrow(() -> new StubJob(new IllegalStateException("must-not-run")).execute(context));
        assertEquals(Map.of("requestId", "previous_123"), MDC.getCopyOfContextMap());
        verifyNoInteractions(jobLogService);
    }

    private SysJob job()
    {
        SysJob job = new SysJob();
        job.setJobId(99L);
        job.setJobName("audit-job");
        job.setJobGroup("DEFAULT");
        job.setInvokeTarget("auditTask.run");
        return job;
    }

    private static class StubJob extends AbstractQuartzJob
    {
        private final Exception failure;

        StubJob(Exception failure)
        {
            this.failure = failure;
        }

        @Override
        protected void doExecute(JobExecutionContext context, SysJob sysJob) throws Exception
        {
            if (failure != null)
            {
                throw failure;
            }
        }
    }
}
