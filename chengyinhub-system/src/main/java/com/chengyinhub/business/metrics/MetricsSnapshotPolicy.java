package com.chengyinhub.business.metrics;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** D12 快照允许的低基数标签；未知标签一律不能落库。 */
public final class MetricsSnapshotPolicy {
    private static final String UNKNOWN_METRIC = "metrics.unknown";
    private static final String UNKNOWN_SOURCE = "unknown";
    private static final String DEFAULT_THRESHOLD_VERSION = "d12-default-v1";
    private static final String FAILURE_THRESHOLD_VERSION = "d12-probe-failure-v1";

    private static final Set<String> METRIC_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            UNKNOWN_METRIC,
            "metrics_probe.failure",
            "druid.active.count",
            "druid.pooling.count",
            "druid.wait.thread.count",
            "druid.not.empty.wait.count",
            "jvm.heap.used.ratio",
            "jvm.gc.collection.count",
            "jvm.gc.collection.time.ms",
            "tomcat.threads.busy",
            "tomcat.threads.current",
            "semantic.failure")));

    private static final Set<String> SOURCES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            UNKNOWN_SOURCE, "metrics_probe", "druid", "jvm", "tomcat", "app")));

    private static final Set<String> THRESHOLD_VERSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            DEFAULT_THRESHOLD_VERSION, FAILURE_THRESHOLD_VERSION)));

    private MetricsSnapshotPolicy() {
    }

    public static String safeMetricKey(String metricKey) {
        return METRIC_KEYS.contains(metricKey) ? metricKey : UNKNOWN_METRIC;
    }

    public static String safeSource(String source) {
        return SOURCES.contains(source) ? source : UNKNOWN_SOURCE;
    }

    public static String safeThresholdVersion(String thresholdVersion) {
        return THRESHOLD_VERSIONS.contains(thresholdVersion) ? thresholdVersion : DEFAULT_THRESHOLD_VERSION;
    }

    public static boolean isAllowedMetricKey(String metricKey) {
        return METRIC_KEYS.contains(metricKey);
    }

    public static boolean isAllowedSource(String source) {
        return SOURCES.contains(source);
    }

    public static boolean isAllowedThresholdVersion(String thresholdVersion) {
        return THRESHOLD_VERSIONS.contains(thresholdVersion);
    }
}
