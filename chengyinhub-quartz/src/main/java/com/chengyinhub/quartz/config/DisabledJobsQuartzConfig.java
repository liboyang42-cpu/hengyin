package com.chengyinhub.quartz.config;

import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import org.quartz.listeners.TriggerListenerSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** No auto-start or residual execution while the startup-time jobs switch is off. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chengyin.jobs.enabled", havingValue = "false")
public class DisabledJobsQuartzConfig {
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SchedulerFactoryBeanCustomizer disabledJobsQuartzCustomizer() {
        return factory -> {
            factory.setAutoStartup(false);
            factory.setGlobalTriggerListeners(new TriggerListenerSupport() {
                @Override public String getName() { return "chengyin-jobs-disabled"; }
                @Override public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
                    return true;
                }
            });
        };
    }
}
