package com.chengyinhub.business.merchantplans;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.merchantproof.MerchantWeeklyProof;

/**
 * S3a: hourly verified-visit accrual for yesterday and today (Asia/Shanghai). Idempotent. Writes nothing while the
 * money switch area {@code merchantMoney} is closed. Off with {@code chengyin.merchant-plans.visit-billing.job-enabled=false}.
 */
@Component
public class MerchantVisitBillingJob {
    /** R9 BE-26: with two nodes only the scheduling leader runs this @Scheduled method. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.common.core.scheduling.SchedulingLeadership schedulingLeadership;

    private static final Logger log = LoggerFactory.getLogger(MerchantVisitBillingJob.class);
    private final MerchantVisitBillingService billing;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public MerchantVisitBillingJob(MerchantVisitBillingService billing,
                                   @Value("${chengyin.merchant-plans.visit-billing.job-enabled:true}") boolean enabled) {
        this.billing = billing;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${chengyin.merchant-plans.visit-billing.cron:0 23 * * * ?}", zone = "Asia/Shanghai")
    public void run() {
        if (!com.chengyinhub.common.core.scheduling.SchedulingLeadership.allows(schedulingLeadership)) return; // R9 BE-26: leader node only
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            LocalDate today = LocalDate.now(MerchantWeeklyProof.ZONE);
            for (LocalDate day : new LocalDate[]{today.minusDays(1), today}) {
                log.info("merchant visit billing {}", billing.accrueDay(day));
            }
        } catch (RuntimeException e) {
            log.warn("merchant visit billing failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
