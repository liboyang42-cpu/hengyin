package com.chengyinhub.quartz.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private ConfigurableListableBeanFactory originalBeanFactory;

    @BeforeEach
    void setUp()
    {
        originalBeanFactory = (ConfigurableListableBeanFactory) ReflectionTestUtils.getField(SpringUtils.class,
                "beanFactory");
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        jobLogService = mock(ISysJobLogService.class);
        when(beanFactory.getBean(ISysJobLogService.class)).thenReturn(jobLogService);
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", beanFactory);

        context = mock(JobExecutionContext.class);
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(ScheduleConstants.TASK_PROPERTIES, job());
        when(context.getMergedJobDataMap()).thenReturn(dataMap);
    }

    @AfterEach
    void tearDown()
    {
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
        assertTrue(captor.getValue().getExceptionInfo().contains("task-boom"));
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
