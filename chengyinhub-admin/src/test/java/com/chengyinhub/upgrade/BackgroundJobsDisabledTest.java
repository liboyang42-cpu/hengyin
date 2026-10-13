package com.chengyinhub.upgrade;

import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.ServiceStatusFeedServiceImpl;
import com.chengyinhub.framework.config.BackgroundSchedulingConfig;
import com.chengyinhub.quartz.config.DisabledJobsQuartzConfig;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.mapper.SysJobMapper;
import com.chengyinhub.quartz.service.impl.SysJobServiceImpl;
import com.chengyinhub.web.job.AccountDeregistrationJob;
import com.chengyinhub.web.job.MerchantCrmExportJob;
import com.chengyinhub.web.runner.WithdrawalPiiBackfillRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.*;
import org.quartz.listeners.JobListenerSupport;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackgroundJobsDisabledTest {
    @TempDir Path temporary;
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(QuartzAutoConfiguration.class))
                .withUserConfiguration(BackgroundSchedulingConfig.class, DisabledJobsQuartzConfig.class,
                        MerchantCrmExportJob.class, AccountDeregistrationJob.class,
                        ServiceStatusFeedServiceImpl.class, WithdrawalPiiBackfillRunner.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(IMerchantCrmExportService.class, () -> mock(IMerchantCrmExportService.class))
                .withBean(IAccountDeregistrationService.class, () -> mock(IAccountDeregistrationService.class))
                .withBean(IUmsMemberWithdrawalService.class, () -> mock(IUmsMemberWithdrawalService.class))
                .withPropertyValues("spring.quartz.job-store-type=memory",
                        "chengyin.status.feed-file=" + temporary.resolve("status.json"));
    }

    @Test void omittedAndTrueKeepExistingScheduling() {
        for (String value : new String[]{"", "chengyin.jobs.enabled=true"}) {
            var runner = context();
            if (!value.isEmpty()) runner = runner.withPropertyValues(value);
            runner.run(ctx -> {
                assertNull(ctx.getStartupFailure());
                assertTrue(ctx.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
                assertEquals(1, ctx.getBeansOfType(AccountDeregistrationJob.class).size());
                assertEquals(1, ctx.getBeansOfType(MerchantCrmExportJob.class).size());
                assertTrue(ctx.getBeansOfType(DisabledJobsQuartzConfig.class).isEmpty());
                verify(ctx.getBean(IMerchantCrmExportService.class), timeout(3000).atLeastOnce()).runNextPending();
                assertTrue(ctx.getBean(Scheduler.class).isStarted());
            });
        }
    }

    @Test void disabledRemovesScheduledJobsAndBlocksStartupAndDirectRunnerWork() {
        context().withPropertyValues("chengyin.jobs.enabled=false", "withdrawal.pii.backfill-limit=100")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertFalse(ctx.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
                    assertTrue(ctx.getBeansOfType(AccountDeregistrationJob.class).isEmpty());
                    assertTrue(ctx.getBeansOfType(MerchantCrmExportJob.class).isEmpty());
                    ctx.getBean(ServiceStatusFeedServiceImpl.class).publishCoreApiHeartbeat();
                    ctx.getBean(WithdrawalPiiBackfillRunner.class).run(null);
                    assertFalse(Files.exists(temporary.resolve("status.json")));
                    verifyNoInteractions(ctx.getBean(IMerchantCrmExportService.class),
                            ctx.getBean(IAccountDeregistrationService.class), ctx.getBean(IUmsMemberWithdrawalService.class));
                });
    }

    public static class CountingJob implements Job {
        static final AtomicInteger executions = new AtomicInteger();
        @Override public void execute(JobExecutionContext context) { executions.incrementAndGet(); }
    }

    @Test void disabledQuartzOverridesAutoStartupAndVetoesResidualAndManualTriggers() {
        context().withPropertyValues("chengyin.jobs.enabled=false", "spring.quartz.auto-startup=true")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    Scheduler scheduler = ctx.getBean(Scheduler.class);
                    assertFalse(scheduler.isStarted());
                    CountingJob.executions.set(0);
                    CountDownLatch vetoed = new CountDownLatch(2);
                    scheduler.getListenerManager().addJobListener(new JobListenerSupport() {
                        @Override public String getName() { return "veto-observer"; }
                        @Override public void jobExecutionVetoed(JobExecutionContext ignored) { vetoed.countDown(); }
                    });
                    JobDetail job = JobBuilder.newJob(CountingJob.class).withIdentity("residual").storeDurably().build();
                    scheduler.scheduleJob(job, TriggerBuilder.newTrigger().withIdentity("residual-trigger")
                            .forJob(job).startNow().build());
                    scheduler.start();
                    scheduler.triggerJob(job.getKey());
                    assertTrue(vetoed.await(5, TimeUnit.SECONDS), "Both residual and manual fires must reach veto");
                    assertEquals(0, CountingJob.executions.get());
                });
    }

    @Test void disabledQuartzServiceDoesNotTouchStoreOrTriggerJobs() throws Exception {
        var service = new SysJobServiceImpl();
        var scheduler = mock(Scheduler.class);
        var mapper = mock(SysJobMapper.class);
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
        ReflectionTestUtils.setField(service, "jobMapper", mapper);
        ReflectionTestUtils.setField(service, "jobsEnabled", false);
        service.init();
        assertFalse(service.run(new SysJob()));
        verifyNoInteractions(scheduler, mapper);
    }

    @Test void defaultQuartzServiceStillInitializesAndRuns() throws Exception {
        var service = new SysJobServiceImpl();
        var scheduler = mock(Scheduler.class);
        var mapper = mock(SysJobMapper.class);
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
        ReflectionTestUtils.setField(service, "jobMapper", mapper);
        when(mapper.selectJobAll()).thenReturn(List.of());
        service.init();
        verify(scheduler).clear();
        verify(mapper).selectJobAll();
        SysJob job = new SysJob(); job.setJobId(42L); job.setJobGroup("DEFAULT");
        when(mapper.selectJobById(42L)).thenReturn(job);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);
        assertTrue(service.run(job));
        verify(scheduler).triggerJob(any(JobKey.class), any(JobDataMap.class));
    }
}
