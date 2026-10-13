package com.chengyinhub.web.task.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** D12 指标采样默认阈值；不要求 application.yml 显式配置。 */
@Component
@ConfigurationProperties(prefix = "metrics.probe")
public class MetricsProbeProperties {
    private int windowMinutes = 5;
    private int freshnessGraceMinutes = 10;

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public int getFreshnessGraceMinutes() {
        return freshnessGraceMinutes;
    }

    public void setFreshnessGraceMinutes(int freshnessGraceMinutes) {
        this.freshnessGraceMinutes = freshnessGraceMinutes;
    }

    public long safeWindowMillis() {
        return Math.max(1, windowMinutes) * 60L * 1000L;
    }

    public long safeFreshnessMillis() {
        return Math.max(1, freshnessGraceMinutes) * 60L * 1000L;
    }
}
