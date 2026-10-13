package com.chengyinhub.web.task;

import com.chengyinhub.business.domain.MetricsSnapshot;
import com.chengyinhub.business.mapper.MetricsSnapshotMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.DefaultMetricsSemanticFailureCounter;
import com.chengyinhub.business.service.impl.MetricsSnapshotServiceImpl;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.metrics.MetricsProbe;
import com.chengyinhub.web.task.metrics.TomcatMetricsProbe;
import com.chengyinhub.web.task.metrics.MetricsProbeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsProbeTaskTest {

    @Test
    void runPersistsSafeFailSnapshotAndRethrowsSamplingFailure() {
        CapturingMetricsSnapshotMapper mapper = new CapturingMetricsSnapshotMapper();
        MetricsSnapshotServiceImpl service = new MetricsSnapshotServiceImpl();
        ReflectionTestUtils.setField(service, "metricsSnapshotMapper", mapper);
        AlarmService alarmService = mock(AlarmService.class);
        Date now = new Date(1700000123456L);
        MetricsProbeTask task = newTask(service, alarmService,
                java.util.Arrays.asList(
                        fixedProbe("jvm", MetricSample.gauge("jvm.heap.used.ratio", "jvm", BigDecimal.ONE, now)),
                        throwingProbe("https://api.example.test/path?token=secret-123 手机号13800138000")),
                now);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, task::run);

        assertEquals("metrics probe failed", thrown.getMessage());
        assertEquals(2, mapper.rows.size());
        MetricsSnapshot ok = mapper.rows.get("jvm.heap.used.ratio|1699999800000|1700000100000");
        assertNotNull(ok);
        assertEquals("OK", ok.getStatus());
        MetricsSnapshot fail = mapper.rows.get("metrics_probe.failure|1699999800000|1700000100000");
        assertNotNull(fail);
        assertEquals("metrics_probe", fail.getSource());
        assertEquals("FAIL", fail.getStatus());
        assertEquals(BigDecimal.ONE, fail.getNumerator());
        assertFalse(fail.getThresholdVersion().contains("secret-123"));
        assertFalse(fail.getThresholdVersion().contains("13800138000"));
        verify(alarmService).alarm(eq("MetricsProbeTask采样失败"), contains("IllegalStateException"));
    }

    @Test
    void runRejectsUnsafeMetricLabelsBeforePersistingSnapshots() {
        CapturingMetricsSnapshotMapper mapper = new CapturingMetricsSnapshotMapper();
        MetricsSnapshotServiceImpl service = new MetricsSnapshotServiceImpl();
        ReflectionTestUtils.setField(service, "metricsSnapshotMapper", mapper);
        Date now = new Date(1700000123456L);
        MetricsProbeTask task = newTask(service, mock(AlarmService.class),
                Collections.singletonList(fixedProbe("app", MetricSample.gauge("https://api.example.test/13800138000?token=secret-123",
                        "https://api.example.test/path?token=secret-123", BigDecimal.ONE, now))), now);

        task.run();

        MetricsSnapshot snapshot = mapper.rows.get("metrics.unknown|1699999800000|1700000100000");
        assertNotNull(snapshot);
        assertEquals("metrics.unknown", snapshot.getMetricKey());
        assertEquals("unknown", snapshot.getSource());
        assertFalse(snapshot.getMetricKey().contains("secret-123"));
        assertFalse(snapshot.getSource().contains("secret-123"));
    }

    @Test
    void checkFreshnessFailsClosedWhenMissingSnapshotAlarmCannotBeDelivered() {
        CapturingMetricsSnapshotMapper mapper = new CapturingMetricsSnapshotMapper();
        MetricsSnapshotServiceImpl service = new MetricsSnapshotServiceImpl();
        ReflectionTestUtils.setField(service, "metricsSnapshotMapper", mapper);
        AlarmService alarmService = mock(AlarmService.class);
        doThrow(new RuntimeException("webhook down"))
                .when(alarmService).alarm(eq("MetricsProbeTask快照过期"), contains("lastSuccessAt=missing"));
        MetricsProbeTask task = newTask(service, alarmService, Collections.emptyList(), new Date(1700000000000L));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, task::checkFreshness);

        assertEquals("metrics alarm failed", thrown.getMessage());
        verify(alarmService).alarm(eq("MetricsProbeTask快照过期"), contains("lastSuccessAt=missing"));
    }

    @Test
    void tomcatProbeFailsClosedWhenThreadAttributeIsNotNumeric() throws Exception {
        MBeanServer mBeanServer = mock(MBeanServer.class);
        ObjectName threadPool = new ObjectName("Tomcat:type=ThreadPool,name=http-nio-8080");
        when(mBeanServer.queryNames(eq(new ObjectName("Tomcat:type=ThreadPool,*")), isNull()))
                .thenReturn(Collections.singleton(threadPool));
        when(mBeanServer.getAttribute(threadPool, "currentThreadsBusy")).thenReturn("unavailable");
        TomcatMetricsProbe probe = new TomcatMetricsProbe();
        ReflectionTestUtils.setField(probe, "mBeanServer", mBeanServer);

        assertThrows(IllegalStateException.class,
                () -> probe.sample(new Date(1700000000000L), new Date(1700000300000L), new Date(1700000300000L)));
    }

    @Test
    void metricTaskHasQuartzBeanAndNoArgRunContract() throws Exception {
        Component component = MetricsProbeTask.class.getAnnotation(Component.class);

        assertNotNull(component);
        assertEquals("metricsProbeTask", component.value());
        assertNotNull(MetricsProbeTask.class.getMethod("run"));
    }

    @Test
    void semanticFailureCounterDrainsOnlySafeSourceAndMetricContract() {
        DefaultMetricsSemanticFailureCounter counter = new DefaultMetricsSemanticFailureCounter();

        counter.recordFailure("api.login", "business.failure", 2);
        counter.recordFailure("/api/order?token=secret-123", "业务失败 手机号13800138000", 1);
        List<MetricSample> samples = counter.drainFailures(new Date(1700000000000L));
        List<MetricSample> emptyAfterDrain = counter.drainFailures(new Date(1700000001000L));

        assertEquals(2, samples.size());
        assertEquals(0, emptyAfterDrain.size());
        assertTrue(samples.stream().anyMatch(s -> "api.login".equals(s.getSource())
                && "business.failure".equals(s.getMetricKey())
                && new BigDecimal("2").compareTo(s.getValue()) == 0));
        for (MetricSample sample : samples) {
            assertFalse(sample.getSource().contains("token"));
            assertFalse(sample.getMetricKey().contains("13800138000"));
            assertTrue(sample.getSource().matches("[a-z0-9_.:-]+"));
            assertTrue(sample.getMetricKey().matches("[a-z0-9_.:-]+"));
        }
    }

    @Test
    void serviceUpsertsByNaturalMetricWindow() {
        CapturingMetricsSnapshotMapper mapper = new CapturingMetricsSnapshotMapper();
        MetricsSnapshotServiceImpl service = new MetricsSnapshotServiceImpl();
        ReflectionTestUtils.setField(service, "metricsSnapshotMapper", mapper);
        Date windowStart = new Date(1700000000000L);
        Date windowEnd = new Date(1700000300000L);
        MetricsSnapshot first = snapshot("semantic.failure", windowStart, windowEnd, "app", "OK", BigDecimal.ONE);
        MetricsSnapshot second = snapshot("semantic.failure", windowStart, windowEnd, "app", "OK", new BigDecimal("3"));

        service.upsertSnapshots(java.util.Arrays.asList(first, second));

        assertEquals(1, mapper.rows.size());
        assertEquals(new BigDecimal("3"), mapper.rows.values().iterator().next().getValue());
    }

    @Test
    void serviceRejectsUnsafeLabelsWhenCalledOutsideMetricsProbeTask() {
        CapturingMetricsSnapshotMapper mapper = new CapturingMetricsSnapshotMapper();
        MetricsSnapshotServiceImpl service = new MetricsSnapshotServiceImpl();
        ReflectionTestUtils.setField(service, "metricsSnapshotMapper", mapper);
        Date windowStart = new Date(1700000000000L);
        Date windowEnd = new Date(1700000300000L);
        MetricsSnapshot unsafe = snapshot("app.user.13800138000", windowStart, windowEnd,
                "app", "OK", BigDecimal.ONE);

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertSnapshots(Collections.singletonList(unsafe)));

        assertTrue(mapper.rows.isEmpty());
    }

    private static MetricsProbeTask newTask(MetricsSnapshotServiceImpl service, AlarmService alarmService,
                                            List<MetricsProbe> probes, Date now) {
        MetricsProbeTask task = new MetricsProbeTask();
        ReflectionTestUtils.setField(task, "metricsSnapshotService", service);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);
        ReflectionTestUtils.setField(task, "probes", probes);
        MetricsProbeProperties properties = new MetricsProbeProperties();
        properties.setWindowMinutes(5);
        properties.setFreshnessGraceMinutes(10);
        ReflectionTestUtils.setField(task, "properties", properties);
        ReflectionTestUtils.setField(task, "clock", new FixedClock(now));
        return task;
    }

    private static MetricsProbe fixedProbe(String source, MetricSample sample) {
        return new MetricsProbe() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt) {
                return Collections.singletonList(sample);
            }
        };
    }

    private static MetricsProbe throwingProbe(String message) {
        return new MetricsProbe() {
            @Override
            public String source() {
                return "bad.probe";
            }

            @Override
            public List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt) {
                throw new IllegalStateException(message);
            }
        };
    }

    private static MetricsSnapshot snapshot(String key, Date start, Date end, String source, String status, BigDecimal value) {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setMetricKey(key);
        snapshot.setWindowStart(start);
        snapshot.setWindowEnd(end);
        snapshot.setSource(source);
        snapshot.setNumerator(value);
        snapshot.setValue(value);
        snapshot.setThresholdVersion("d12-default-v1");
        snapshot.setStatus(status);
        snapshot.setSampledAt(end);
        return snapshot;
    }

    private static final class FixedClock implements MetricsProbeTask.Clock {
        private final Date now;

        private FixedClock(Date now) {
            this.now = now;
        }

        @Override
        public Date now() {
            return now;
        }
    }

    private static final class CapturingMetricsSnapshotMapper implements MetricsSnapshotMapper {
        private final Map<String, MetricsSnapshot> rows = new LinkedHashMap<>();

        @Override
        public int upsertSnapshot(MetricsSnapshot snapshot) {
            rows.put(key(snapshot), snapshot);
            return 1;
        }

        @Override
        public Date selectLatestSuccessfulSampledAt() {
            Date latest = null;
            for (MetricsSnapshot row : rows.values()) {
                if ("OK".equals(row.getStatus()) && row.getSampledAt() != null
                        && (latest == null || row.getSampledAt().after(latest))) {
                    latest = row.getSampledAt();
                }
            }
            return latest;
        }

        private static String key(MetricsSnapshot snapshot) {
            return snapshot.getMetricKey() + "|" + snapshot.getWindowStart().getTime() + "|" + snapshot.getWindowEnd().getTime();
        }
    }
}
