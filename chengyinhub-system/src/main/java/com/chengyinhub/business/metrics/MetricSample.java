package com.chengyinhub.business.metrics;

import java.math.BigDecimal;
import java.util.Date;

/** 单个 D12 指标采样值；只携带低基数字段，不支持标签、URL 或用户维度。 */
public class MetricSample {
    private final String metricKey;
    private final String source;
    private final BigDecimal numerator;
    private final BigDecimal denominator;
    private final BigDecimal value;
    private final String thresholdVersion;
    private final Date sampledAt;

    private MetricSample(String metricKey, String source, BigDecimal numerator, BigDecimal denominator,
                         BigDecimal value, String thresholdVersion, Date sampledAt) {
        this.metricKey = metricKey;
        this.source = source;
        this.numerator = numerator;
        this.denominator = denominator;
        this.value = value;
        this.thresholdVersion = thresholdVersion;
        this.sampledAt = sampledAt;
    }

    public static MetricSample gauge(String metricKey, String source, BigDecimal value, Date sampledAt) {
        return new MetricSample(metricKey, source, value, null, value, "d12-default-v1", sampledAt);
    }

    public static MetricSample ratio(String metricKey, String source, BigDecimal numerator, BigDecimal denominator,
                                     Date sampledAt) {
        BigDecimal value = denominator == null || BigDecimal.ZERO.compareTo(denominator) == 0
                ? BigDecimal.ZERO : numerator.divide(denominator, 6, BigDecimal.ROUND_HALF_UP);
        return new MetricSample(metricKey, source, numerator, denominator, value, "d12-default-v1", sampledAt);
    }

    public String getMetricKey() {
        return metricKey;
    }

    public String getSource() {
        return source;
    }

    public BigDecimal getNumerator() {
        return numerator;
    }

    public BigDecimal getDenominator() {
        return denominator;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getThresholdVersion() {
        return thresholdVersion;
    }

    public Date getSampledAt() {
        return sampledAt;
    }
}
