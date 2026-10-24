package com.chengyinhub.web.job;

import com.chengyinhub.business.service.IMerchantCrmExportService;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "chengyin.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantCrmExportJob {
    private final IMerchantCrmExportService exportService;

    public MerchantCrmExportJob(IMerchantCrmExportService exportService) {
        this.exportService = exportService;
    }

    public void runNext() {
        exportService.runNextPending();
    }

    public void cleanupExpired() {
        exportService.expireAndClean();
    }
}
