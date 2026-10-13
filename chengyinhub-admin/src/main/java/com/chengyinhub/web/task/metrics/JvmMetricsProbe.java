package com.chengyinhub.web.task.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Component;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.metrics.MetricsProbe;

/** JVM heap 与 GC 轻量采样。 */
@Component
public class JvmMetricsProbe implements MetricsProbe {
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    @Override
    public String source() {
        return "jvm";
    }

    @Override
    public List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt) {
        List<MetricSample> samples = new ArrayList<>();
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        long used = Math.max(0L, heap.getUsed());
        long max = heap.getMax() <= 0 ? heap.getCommitted() : heap.getMax();
        samples.add(MetricSample.ratio("jvm.heap.used.ratio", source(), new BigDecimal(used), new BigDecimal(Math.max(1L, max)), sampledAt));
        long gcCount = 0L;
        long gcTime = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) {
                gcCount += gc.getCollectionCount();
            }
            if (gc.getCollectionTime() > 0) {
                gcTime += gc.getCollectionTime();
            }
        }
        samples.add(MetricSample.gauge("jvm.gc.collection.count", source(), new BigDecimal(gcCount), sampledAt));
        samples.add(MetricSample.gauge("jvm.gc.collection.time.ms", source(), new BigDecimal(gcTime), sampledAt));
        return samples;
    }
}
