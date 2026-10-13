package com.chengyinhub.web.task.metrics;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.springframework.stereotype.Component;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.metrics.MetricsProbe;

/** Tomcat 工作线程轻量采样。 */
@Component
public class TomcatMetricsProbe implements MetricsProbe {
    private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

    @Override
    public String source() {
        return "tomcat";
    }

    @Override
    public List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt) {
        try {
            Set<ObjectName> pools = mBeanServer.queryNames(new ObjectName("Tomcat:type=ThreadPool,*"), null);
            if (pools.isEmpty()) {
                pools = mBeanServer.queryNames(new ObjectName("Catalina:type=ThreadPool,*"), null);
            }
            if (pools.isEmpty()) {
                throw new IllegalStateException("tomcat thread pool mbean missing");
            }
            int busy = 0;
            int current = 0;
            for (ObjectName pool : pools) {
                busy += intAttribute(pool, "currentThreadsBusy");
                current += intAttribute(pool, "currentThreadCount");
            }
            List<MetricSample> samples = new ArrayList<>();
            samples.add(MetricSample.gauge("tomcat.threads.busy", source(), new BigDecimal(busy), sampledAt));
            samples.add(MetricSample.gauge("tomcat.threads.current", source(), new BigDecimal(current), sampledAt));
            return samples;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("tomcat thread pool sample failed");
        }
    }

    private int intAttribute(ObjectName name, String attribute) throws Exception {
        Object value = mBeanServer.getAttribute(name, attribute);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("tomcat thread attribute unavailable");
        }
        return ((Number) value).intValue();
    }
}
