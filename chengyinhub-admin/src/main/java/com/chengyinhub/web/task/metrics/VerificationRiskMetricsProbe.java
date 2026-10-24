package com.chengyinhub.web.task.metrics;

import com.chengyinhub.business.mapper.VerificationReviewMapper;
import com.chengyinhub.business.metrics.MetricSample;
import com.chengyinhub.business.metrics.MetricsProbe;
import com.chengyinhub.business.service.impl.AlarmService;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
public class VerificationRiskMetricsProbe implements MetricsProbe {
    private final VerificationReviewMapper reviews;
    private final AlarmService alarms;
    public VerificationRiskMetricsProbe(VerificationReviewMapper reviews, AlarmService alarms) {
        this.reviews = reviews; this.alarms = alarms;
    }
    @Override public String source() { return "verification"; }
    @Override public List<MetricSample> sample(Date windowStart, Date windowEnd, Date sampledAt) {
        Map<String, Object> stats = reviews.selectPendingQueueStats();
        if (stats == null || !(stats.get("pendingCount") instanceof Number) || !(stats.get("oldestAgeSeconds") instanceof Number)) {
            throw new IllegalStateException("verification queue stats unavailable");
        }
        long count = ((Number) stats.get("pendingCount")).longValue();
        long age = ((Number) stats.get("oldestAgeSeconds")).longValue();
        if (count < 0 || age < 0) throw new IllegalStateException("verification queue stats invalid");
        if (count >= 20 || count > 0 && age >= 86400) {
            alarms.alarmThrottled("verification.risk.backlog", "核销复核队列积压", "pending=" + count + " oldestAgeSeconds=" + age);
        }
        return List.of(MetricSample.gauge("verification.reviews.pending", source(), BigDecimal.valueOf(count), sampledAt),
                MetricSample.gauge("verification.reviews.oldest.age.seconds", source(), BigDecimal.valueOf(age), sampledAt));
    }
}
