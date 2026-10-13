package com.chengyinhub.business.metrics;

import java.util.Date;
import java.util.List;

/** 可注入的 D12 指标采样 seam。 */
public interface MetricsProbe {
    String source();

    List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt);
}
