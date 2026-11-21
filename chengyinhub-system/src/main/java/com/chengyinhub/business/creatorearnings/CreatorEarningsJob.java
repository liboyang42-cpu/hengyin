package com.chengyinhub.business.creatorearnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hourly creator earnings accrual for yesterday and today (Asia/Shanghai). Every run is idempotent, so the
 * overlap only catches events recorded late. Off with {@code chengyin.creator-earnings.job-enabled=false}.
 */
@Component
public class CreatorEarningsJob {
    /** R9 BE-26: with two nodes only the scheduling leader runs this @Scheduled method. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.common.core.scheduling.SchedulingLeadership schedulingLeadership;

    private static final Logger log = LoggerFactory.getLogger(CreatorEarningsJob.class);
    private final CreatorEarningsService earnings;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public CreatorEarningsJob(CreatorEarningsService earnings,
                              @Value("${chengyin.creator-earnings.job-enabled:true}") boolean enabled) {
        this.earnings = earnings;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${chengyin.creator-earnings.cron:0 7 * * * ?}", zone = "Asia/Shanghai")
    public void run() {
        if (!com.chengyinhub.common.core.scheduling.SchedulingLeadership.allows(schedulingLeadership)) return; // R9 BE-26: leader node only
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            LocalDate today = LocalDate.now(CreatorEarningsService.ZONE);
            for (LocalDate day : new LocalDate[]{today.minusDays(1), today}) {
                log.info("creator earnings accrual {}", earnings.accrueDay(day));
            }
        } catch (RuntimeException e) {
            log.warn("creator earnings accrual failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
