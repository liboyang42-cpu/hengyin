package com.chengyinhub.business.service;

import java.util.Date;
import java.util.List;
import com.chengyinhub.business.metrics.MetricSample;

/**
 * 应用/业务语义失败计数接口。
 *
 * <p>调用方只传低基数 source/metricKey，不传 URL、token、openid、手机号、订单号或异常正文。
 */
public interface IMetricsSemanticFailureCounter {
    void recordFailure(String source, String metricKey, long count);

    List<MetricSample> drainFailures(Date sampledAt);
}
