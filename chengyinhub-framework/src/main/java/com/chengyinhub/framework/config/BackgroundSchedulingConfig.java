package com.chengyinhub.framework.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Startup-time switch; omitted property preserves existing scheduling behavior. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chengyin.jobs.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class BackgroundSchedulingConfig {
}
