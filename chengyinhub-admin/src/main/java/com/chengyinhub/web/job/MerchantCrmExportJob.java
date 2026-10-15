package com.chengyinhub.web.job;

import com.chengyinhub.business.service.IMerchantCrmExportService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "chengyin.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantCrmExportJob {
    private final IMerchantCrmExportService exportService;

    public MerchantCrmExportJob(IMerchantCrmExportService exportService) {
        this.exportService = exportService;
    }

    @Scheduled(fixedDelayString = "${chengyin.merchant-crm.export.poll-ms:5000}")
    public void runNext() {
        exportService.runNextPending();
    }

    @Scheduled(cron = "${chengyin.merchant-crm.export.cleanup-cron:0 */10 * * * ?}")
    public void cleanupExpired() {
        exportService.expireAndClean();
    }
}
