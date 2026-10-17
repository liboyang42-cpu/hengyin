package com.chengyinhub.web.task.metrics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.metrics.MetricsProbe;

/** Druid 主库连接池轻量采样。 */
@Component
public class DruidMetricsProbe implements MetricsProbe {
    @Autowired
    private com.chengyinhub.business.service.impl.AlarmService alarmService;
    @Autowired(required = false)
    @Qualifier("masterDataSource")
    private DataSource masterDataSource;

    @Override
    public String source() {
        return "druid";
    }

    @Override
    public List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt) {
        if (!(masterDataSource instanceof DruidDataSource)) {
            throw new IllegalStateException("master datasource is not druid");
        }
        DruidDataSource druid = (DruidDataSource) masterDataSource;
        if (druid.getWaitThreadCount() > 0) {
            alarmService.alarmThrottled("runtime.db.pool.waiting", "数据库连接池等待",
                    "active=" + druid.getActiveCount() + " max=" + druid.getMaxActive() + " waiting=" + druid.getWaitThreadCount());
        }
        List<MetricSample> samples = new ArrayList<>();
        samples.add(MetricSample.gauge("druid.active.count", source(), new BigDecimal(druid.getActiveCount()), sampledAt));
        samples.add(MetricSample.gauge("druid.pooling.count", source(), new BigDecimal(druid.getPoolingCount()), sampledAt));
        samples.add(MetricSample.gauge("druid.wait.thread.count", source(), new BigDecimal(druid.getWaitThreadCount()), sampledAt));
        samples.add(MetricSample.gauge("druid.not.empty.wait.count", source(), new BigDecimal(druid.getNotEmptyWaitCount()), sampledAt));
        return samples;
    }
}
