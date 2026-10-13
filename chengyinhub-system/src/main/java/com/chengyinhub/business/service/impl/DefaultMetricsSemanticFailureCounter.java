package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.service.IMetricsSemanticFailureCounter;
import com.chengyinhub.common.utils.StringUtils;

/** 默认内存语义失败计数器；只接受低基数 source/metricKey，采样后清零。 */
@Service
public class DefaultMetricsSemanticFailureCounter implements IMetricsSemanticFailureCounter {
    private static final String DEFAULT_SOURCE = "app";
    private static final String DEFAULT_METRIC = "semantic.failure";
    private static final int MAX_KEY_LENGTH = 64;

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public void recordFailure(String source, String metricKey, long count) {
        if (count <= 0) {
            return;
        }
        String safeSource = safeKey(source, DEFAULT_SOURCE);
        String safeMetric = safeKey(metricKey, DEFAULT_METRIC);
        counters.computeIfAbsent(safeSource + "|" + safeMetric, k -> new AtomicLong()).addAndGet(count);
    }

    @Override
    public List<MetricSample> drainFailures(Date sampledAt) {
        List<MetricSample> samples = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
            long value = entry.getValue().getAndSet(0L);
            if (value <= 0) {
                continue;
            }
            String[] parts = entry.getKey().split("\\|", 2);
            samples.add(MetricSample.gauge(parts[1], parts[0], new BigDecimal(value), sampledAt));
        }
        return samples;
    }

    private static String safeKey(String raw, String fallback) {
        if (StringUtils.isEmpty(raw)) {
            return fallback;
        }
        String lowered = raw.toLowerCase();
        if (lowered.length() > MAX_KEY_LENGTH || !lowered.matches("[a-z0-9_.:-]+")) {
            return fallback;
        }
        return lowered.matches(".*[a-z].*") ? lowered : fallback;
    }
}
