package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * D12 轻量指标快照 metrics_snapshot。
 *
 * <p>只保存指标自然键、窗口、来源、数值、阈值版本、状态和采样时间；不得写入 URL、token、openid、手机号、订单明细等 PII。
 */
public class MetricsSnapshot extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String metricKey;
    private Date windowStart;
    private Date windowEnd;
    private String source;
    private BigDecimal numerator;
    private BigDecimal denominator;
    private BigDecimal value;
    private String thresholdVersion;
    private String status;
    private Date sampledAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public void setMetricKey(String metricKey) {
        this.metricKey = metricKey;
    }

    public Date getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Date windowStart) {
        this.windowStart = windowStart;
    }

    public Date getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Date windowEnd) {
        this.windowEnd = windowEnd;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public BigDecimal getNumerator() {
        return numerator;
    }

    public void setNumerator(BigDecimal numerator) {
        this.numerator = numerator;
    }

    public BigDecimal getDenominator() {
        return denominator;
    }

    public void setDenominator(BigDecimal denominator) {
        this.denominator = denominator;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public String getThresholdVersion() {
        return thresholdVersion;
    }

    public void setThresholdVersion(String thresholdVersion) {
        this.thresholdVersion = thresholdVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Date getSampledAt() {
        return sampledAt;
    }

    public void setSampledAt(Date sampledAt) {
        this.sampledAt = sampledAt;
    }
}
