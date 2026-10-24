package com.chengyinhub.web.task.metrics;

import com.chengyinhub.business.metrics.HttpRequestMetrics;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.metrics.MetricsProbe;
import org.springframework.stereotype.Component;
import java.util.Date;
import java.util.List;

@Component
public class HttpMetricsProbe implements MetricsProbe {
    private final HttpRequestMetrics metrics;
    public HttpMetricsProbe(HttpRequestMetrics metrics) { this.metrics = metrics; }
    @Override public String source() { return "http"; }
    @Override public List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt) {
        return metrics.drain(sampledAt);
    }
}
