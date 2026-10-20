package com.chengyinhub.framework.privacy;

import com.alibaba.druid.filter.stat.StatFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrivateHomePrivacyConfiguration {
    /** Replaces the starter's conditional default StatFilter, preserving all existing stat settings. */
    @Bean
    @ConfigurationProperties("spring.datasource.druid.filter.stat")
    @ConditionalOnProperty(name="spring.datasource.druid.filter.stat.enabled", havingValue="true")
    public StatFilter statFilter() { return new PrivateHomeStatFilter(); }

    /** A custom logging configuration must not silently bypass privacy when the owner API is enabled. */
    @Bean
    @ConditionalOnProperty(name="app.private-home.owner-api-enabled", havingValue="true")
    public org.springframework.beans.factory.SmartInitializingSingleton privateHomePrivacyReadyGuard(
            org.springframework.beans.factory.ObjectProvider<com.alibaba.druid.pool.DruidDataSource> dataSources) {
        return () -> {
            org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
            if (!(factory instanceof ch.qos.logback.classic.LoggerContext)
                    || ((ch.qos.logback.classic.LoggerContext) factory).getTurboFilterList().stream()
                    .noneMatch(filter -> filter instanceof PrivateHomeLogFilter && filter.isStarted()))
                throw new IllegalStateException("HOME_PRIVACY_LOGGING_UNAVAILABLE");
            ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) factory;
            for (ch.qos.logback.classic.Logger logger : context.getLoggerList()) {
                java.util.Iterator<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> appenders = logger.iteratorForAppenders();
                while (appenders.hasNext()) {
                    ch.qos.logback.core.Appender<?> appender = appenders.next();
                    if (!(appender instanceof PrivateHomeLogAppender) || !appender.isStarted())
                        throw new IllegalStateException("HOME_PRIVACY_LOG_SINK_UNAVAILABLE");
                }
            }
            dataSources.forEach(source -> {
                if (!(source instanceof PrivateHomeDruidDataSource)) throw new IllegalStateException("HOME_PRIVACY_SQL_POOL_UNAVAILABLE");
                source.getProxyFilters().forEach(filter -> {
                if (filter instanceof StatFilter && !(filter instanceof PrivateHomeStatFilter))
                    throw new IllegalStateException("HOME_PRIVACY_SQL_STATS_UNAVAILABLE");
                });
            });
        };
    }
}
