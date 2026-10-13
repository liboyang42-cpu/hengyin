package com.chengyinhub.web.task.metrics;

import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.metrics.MetricsProbe;
import com.chengyinhub.business.service.IMetricsSemanticFailureCounter;

/** 已记录的低基数语义失败计数采样；没有记录时不写零值，不作为业务准确率口径。 */
@Component
public class SemanticFailureMetricsProbe implements MetricsProbe {
    @Autowired
    private IMetricsSemanticFailureCounter counter;

    @Override
    public String source() {
        return "app";
    }

    @Override
    public List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt) {
        return counter.drainFailures(sampledAt);
    }
}
