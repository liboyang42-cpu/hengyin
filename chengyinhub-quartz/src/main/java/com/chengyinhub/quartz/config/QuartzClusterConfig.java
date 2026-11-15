package com.chengyinhub.quartz.config;

import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * R9 BE-26: optional Quartz JDBC job store in cluster mode. <b>Off by default</b>
 * ({@code chengyin.quartz.cluster.enabled=false}); replaces the commented-out RuoYi {@code ScheduleConfig}.
 *
 * <p>Two nodes do not need this to be safe: {@code MySqlSchedulerCoordinator} elects one leader through a MySQL
 * session lock, Quartz only starts on that leader ({@link LeaderJobsQuartzConfig}) and every fire is fenced by the
 * leader epoch plus a per-task lock. What this adds is a shared, durable trigger store, so a standby that takes over
 * sees the same misfire state and the QRTZ_ tables show the schedule. Execution stays leader-fenced either way.
 *
 * <p>Turning it on (both nodes, same values): run {@code sql/quartz.sql} once to create the QRTZ_ tables, then set
 * {@code chengyin.quartz.cluster.enabled=true}. See {@code docs/roadmap/backend-hygiene/TWO-NODE.md}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chengyin.quartz.cluster.enabled", havingValue = "true")
public class QuartzClusterConfig
{
    public static final String SCHEDULER_NAME = "ChengyinScheduler";

    @Bean
    public SchedulerFactoryBeanCustomizer quartzClusterCustomizer(@Qualifier("masterDataSource") DataSource dataSource)
    {
        return factory -> {
            factory.setDataSource(dataSource);
            factory.setSchedulerName(SCHEDULER_NAME);
            factory.setQuartzProperties(clusterProperties());
            factory.setOverwriteExistingJobs(true);
        };
    }

    /** Values from the RuoYi template, with the Spring-managed data source job store. */
    public static Properties clusterProperties()
    {
        Properties prop = new Properties();
        prop.put("org.quartz.scheduler.instanceName", SCHEDULER_NAME);
        prop.put("org.quartz.scheduler.instanceId", "AUTO");
        prop.put("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        prop.put("org.quartz.threadPool.threadCount", "10");
        prop.put("org.quartz.threadPool.threadPriority", "5");
        prop.put("org.quartz.jobStore.class", "org.springframework.scheduling.quartz.LocalDataSourceJobStore");
        prop.put("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
        prop.put("org.quartz.jobStore.isClustered", "true");
        prop.put("org.quartz.jobStore.clusterCheckinInterval", "15000");
        prop.put("org.quartz.jobStore.maxMisfiresToHandleAtATime", "10");
        prop.put("org.quartz.jobStore.txIsolationLevelSerializable", "true");
        prop.put("org.quartz.jobStore.misfireThreshold", "12000");
        prop.put("org.quartz.jobStore.tablePrefix", "QRTZ_");
        return prop;
    }
}
