package com.chengyinhub.web.job;

import com.chengyinhub.business.service.impl.ServiceStatusFeedServiceImpl;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** The four existing automatic plans, installed only while this instance owns the leader session. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chengyin.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LeaderScheduledJobsConfig {
    private final MySqlSchedulerCoordinator coordinator;
    private final AccountDeregistrationJob deregistration;
    private final MerchantCrmExportJob exports;
    private final ServiceStatusFeedServiceImpl status;
    @Value("${chengyin.merchant-crm.export.poll-ms:5000}") private long exportPoll;
    @Value("${chengyin.merchant-crm.export.cleanup-cron:0 */10 * * * ?}") private String exportCleanup;
    @Value("${chengyin.status.publish-ms:30000}") private long statusPoll;
    @Value("${chengyin.status.publish-initial-delay-ms:1000}") private long statusInitial;

    public LeaderScheduledJobsConfig(MySqlSchedulerCoordinator coordinator, AccountDeregistrationJob deregistration,
            MerchantCrmExportJob exports, ServiceStatusFeedServiceImpl status) {
        this.coordinator = coordinator; this.deregistration = deregistration; this.exports = exports; this.status = status;
    }

    @PostConstruct
    public void register() {
        coordinator.registerCron("accountDeregistrationJob.executeDueDeregistrations()", "0 15 3 * * ?",
                deregistration::executeDueDeregistrations);
        coordinator.registerFixedDelay("merchantCrmExportJob.runNext()", Duration.ofMillis(exportPoll), Duration.ZERO, exports::runNext);
        coordinator.registerCron("merchantCrmExportJob.cleanupExpired()", exportCleanup, exports::cleanupExpired);
        coordinator.registerFixedDelay("serviceStatusFeedServiceImpl.publishCoreApiHeartbeat()", Duration.ofMillis(statusPoll),
                Duration.ofMillis(statusInitial), status::publishCoreApiHeartbeat);
    }
}
