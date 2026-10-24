package com.chengyinhub.business.metrics;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** Per-process request counts contain no request identities or content. */
@Component
public class HttpRequestMetrics {
    private long requests;
    private long serverErrors;
    private long systemExceptions;

    public synchronized void recordResponse(int status) {
        requests++;
        if (status >= 500) serverErrors++;
    }

    public synchronized void recordSystemException() { systemExceptions++; }

    public synchronized List<MetricSample> drain(Date sampledAt) {
        List<MetricSample> samples = List.of(
                MetricSample.gauge("http.requests.count", "http", BigDecimal.valueOf(requests), sampledAt),
                MetricSample.ratio("http.responses.5xx.rate", "http", BigDecimal.valueOf(serverErrors), BigDecimal.valueOf(requests), sampledAt),
                MetricSample.gauge("http.system.exceptions", "http", BigDecimal.valueOf(systemExceptions), sampledAt));
        requests = serverErrors = systemExceptions = 0;
        return samples;
    }
}
