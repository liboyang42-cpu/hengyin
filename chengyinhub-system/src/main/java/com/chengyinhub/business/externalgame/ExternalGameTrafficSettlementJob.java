package com.chengyinhub.business.externalgame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daily settlement job for external game traffic rewards.
 * Default schedule settles yesterday's eligible plays at 02:15 Asia/Shanghai.
 */
@Component
public class ExternalGameTrafficSettlementJob {
    /** R9 BE-26: with two nodes only the scheduling leader runs this @Scheduled method. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.common.core.scheduling.SchedulingLeadership schedulingLeadership;

    private static final Logger log = LoggerFactory.getLogger(ExternalGameTrafficSettlementJob.class);
    private final ExternalGameMonetizationService monetization;
    private final AtomicBoolean running = new AtomicBoolean();

    public ExternalGameTrafficSettlementJob(ExternalGameMonetizationService monetization) {
        this.monetization = monetization;
    }

    @Scheduled(cron = "${chengyin.external-game.traffic-settlement-cron:0 15 2 * * ?}", zone = "Asia/Shanghai")
    public void runDaily() {
        if (!com.chengyinhub.common.core.scheduling.SchedulingLeadership.allows(schedulingLeadership)) return; // R9 BE-26: leader node only
        if (!running.compareAndSet(false, true)) return;
        try {
            LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
            var summary = monetization.settleDailyTraffic(yesterday);
            log.info("external game traffic settlement date={} settled={} deferred={}",
                    yesterday, summary.get("settledCount"), summary.get("deferredCount"));
        } catch (RuntimeException e) {
            log.warn("external game traffic settlement failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
