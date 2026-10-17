package com.chengyinhub.security;

import org.junit.jupiter.api.Test;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class QuartzDependencyCanaryTest {
    @Test
    void executesMemoryJobWithoutUnusedConnectionPoolLibraries() throws Exception {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.mchange.v2.naming.JavaBeanObjectFactory"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.mchange.v2.c3p0.ComboPooledDataSource"));
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "dependency-canary-" + UUID.randomUUID());
        properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        properties.setProperty("org.quartz.threadPool.threadCount", "1");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        Scheduler scheduler = new StdSchedulerFactory(properties).getScheduler();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            JobDetail job = JobBuilder.newJob(CountingJob.class).build();
            job.getJobDataMap().put("completed", completed);
            job.getJobDataMap().put("executions", executions);
            scheduler.start();
            scheduler.scheduleJob(job, TriggerBuilder.newTrigger().startNow().build());
            assertTrue(completed.await(5, TimeUnit.SECONDS));
        } finally {
            scheduler.shutdown(true);
        }
        assertEquals(1, executions.get());
        assertTrue(scheduler.isShutdown());
    }

    public static class CountingJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            JobDataMap data = context.getMergedJobDataMap();
            ((AtomicInteger) data.get("executions")).incrementAndGet();
            ((CountDownLatch) data.get("completed")).countDown();
        }
    }
}
