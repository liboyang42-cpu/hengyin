package com.chengyinhub.web.task;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.chengyinhub.business.domain.MetricsSnapshot;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.metrics.MetricsProbe;
import com.chengyinhub.business.metrics.MetricsSnapshotPolicy;
import com.chengyinhub.business.service.IMetricsSnapshotService;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.web.task.metrics.MetricsProbeProperties;

/** D12 应用/平台轻量指标采样任务；sys_job invokeTarget=metricsProbeTask.run()。 */
@Component("metricsProbeTask")
public class MetricsProbeTask {
    private static final Logger logger = LoggerFactory.getLogger(MetricsProbeTask.class);

    @Autowired
    private IMetricsSnapshotService metricsSnapshotService;

    @Autowired
    private AlarmService alarmService;

    @Autowired(required = false)
    private List<MetricsProbe> probes = new ArrayList<>();

    @Autowired
    private MetricsProbeProperties properties;

    private Clock clock = new SystemClock();

    public void run() {
        Date sampledAt = clock.now();
        Date windowEnd = floor(sampledAt, properties.safeWindowMillis());
        Date windowStart = new Date(windowEnd.getTime() - properties.safeWindowMillis());
        List<MetricsSnapshot> snapshots = new ArrayList<>();
        RuntimeException failure = null;
        for (MetricsProbe probe : probes) {
            try {
                List<MetricSample> samples = probe.sample(windowStart, windowEnd, sampledAt);
                if (samples != null) {
                    for (MetricSample sample : samples) {
                        snapshots.add(toSnapshot(sample, windowStart, windowEnd, sampledAt, "OK"));
                        if ("http.responses.5xx.rate".equals(sample.getMetricKey())
                                && sample.getNumerator() != null && sample.getNumerator().compareTo(BigDecimal.valueOf(5)) >= 0
                                && sample.getValue() != null && sample.getValue().compareTo(new BigDecimal("0.05")) >= 0) {
                            safeAlarm("monitor.metrics.http5xx", "HTTP失败比例升高", "errors=" + sample.getNumerator()
                                    + " requests=" + sample.getDenominator() + " rate=" + sample.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                String type = e.getClass().getSimpleName();
                // Use the source and sampling window for diagnosis without logging exception text or credentials.
                logger.error("MetricsProbeTask采样失败 source={} type={} windowStart={} windowEnd={}",
                        MetricsSnapshotPolicy.safeSource(probe.source()), type, windowStart.getTime(), windowEnd.getTime());
                snapshots.add(failureSnapshot(windowStart, windowEnd, sampledAt));
                safeAlarm("MetricsProbeTask采样失败", "source=" + MetricsSnapshotPolicy.safeSource(probe.source()) + " type=" + type
                        + " windowStart=" + windowStart.getTime() + " windowEnd=" + windowEnd.getTime());
                if (failure == null) {
                    failure = new IllegalStateException("metrics probe failed");
                }
            }
        }
        metricsSnapshotService.upsertSnapshots(snapshots);
        checkFreshness();
        if (failure != null) {
            throw failure;
        }
    }

    public void checkFreshness() {
        Date latest = metricsSnapshotService.selectLatestSuccessfulSampledAt();
        Date now = clock.now();
        if (latest == null) {
            logger.warn("Metrics freshness anomaly lastSuccessAt=UNKNOWN graceMinutes={}", properties.getFreshnessGraceMinutes());
            safeAlarm("MetricsProbeTask快照过期", "lastSuccessAt=missing graceMinutes=" + properties.getFreshnessGraceMinutes());
            return;
        }
        long age = now.getTime() - latest.getTime();
        if (age > properties.safeFreshnessMillis()) {
            logger.warn("Metrics freshness anomaly lastSuccessAt={} ageMinutes={} graceMinutes={}",
                    latest.getTime(), age / 60000L, properties.getFreshnessGraceMinutes());
            safeAlarm("MetricsProbeTask快照过期", "lastSuccessAt=" + latest.getTime()
                    + " ageMinutes=" + (age / 60000L) + " graceMinutes=" + properties.getFreshnessGraceMinutes());
        }
    }

    private MetricsSnapshot toSnapshot(MetricSample sample, Date windowStart, Date windowEnd, Date sampledAt, String status) {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setMetricKey(MetricsSnapshotPolicy.safeMetricKey(sample.getMetricKey()));
        snapshot.setWindowStart(windowStart);
        snapshot.setWindowEnd(windowEnd);
        snapshot.setSource(MetricsSnapshotPolicy.safeSource(sample.getSource()));
        snapshot.setNumerator(sample.getNumerator());
        snapshot.setDenominator(sample.getDenominator());
        snapshot.setValue(sample.getValue());
        snapshot.setThresholdVersion(MetricsSnapshotPolicy.safeThresholdVersion(sample.getThresholdVersion()));
        snapshot.setStatus(status);
        snapshot.setSampledAt(sample.getSampledAt() == null ? sampledAt : sample.getSampledAt());
        return snapshot;
    }

    private MetricsSnapshot failureSnapshot(Date windowStart, Date windowEnd, Date sampledAt) {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setMetricKey("metrics_probe.failure");
        snapshot.setWindowStart(windowStart);
        snapshot.setWindowEnd(windowEnd);
        snapshot.setSource("metrics_probe");
        snapshot.setNumerator(BigDecimal.ONE);
        snapshot.setValue(BigDecimal.ONE);
        snapshot.setThresholdVersion("d12-probe-failure-v1");
        snapshot.setStatus("FAIL");
        snapshot.setSampledAt(sampledAt);
        return snapshot;
    }

    private static Date floor(Date date, long windowMillis) {
        long millis = date.getTime();
        return new Date(millis - Math.floorMod(millis, windowMillis));
    }

    private void safeAlarm(String title, String detail) {
        safeAlarm("monitor.metrics." + (title.equals("MetricsProbeTask采样失败") ? "sample" : "freshness"), title, detail);
    }

    private void safeAlarm(String key, String title, String detail) {
        try {
            alarmService.alarmThrottled(key, title, detail);
        } catch (Exception e) {
            logger.error("MetricsProbeTask告警失败 title={} type={}", title, e.getClass().getSimpleName());
            throw new IllegalStateException("metrics alarm failed");
        }
    }

    public interface Clock {
        Date now();
    }

    private static final class SystemClock implements Clock {
        @Override
        public Date now() {
            return new Date();
        }
    }
}
