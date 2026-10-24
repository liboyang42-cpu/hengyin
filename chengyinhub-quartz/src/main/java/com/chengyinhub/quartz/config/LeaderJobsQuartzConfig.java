package com.chengyinhub.quartz.config;

import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Only the verified session leader starts Quartz; the existing disabled-job veto remains intact. */
@Configuration(proxyBeanMethods = false)
public class LeaderJobsQuartzConfig {
    @Bean
    public SchedulerFactoryBeanCustomizer leaderJobsQuartzCustomizer() {
        return factory -> factory.setAutoStartup(false);
    }
}
