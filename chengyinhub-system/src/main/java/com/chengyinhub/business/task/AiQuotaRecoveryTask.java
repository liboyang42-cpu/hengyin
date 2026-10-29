package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ai.AiQuotaRecoveryService;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按现有 Quartz bean 调用；本次不写生产 job、不自动启用调度。 */
@Component("aiQuotaRecoveryTask")
public class AiQuotaRecoveryTask {
    private final AiQuotaRecoveryService service;
    private final AiThemeDraftRequestService themeDraftRequests;
    @Value("${ai.themeDraftIntent.maintenanceEnabled:false}")
    private boolean maintenanceEnabled;

    public AiQuotaRecoveryTask(AiQuotaRecoveryService service, AiThemeDraftRequestService themeDraftRequests) {
        this.service=service;
        this.themeDraftRequests=themeDraftRequests;
    }
    public void run() {
        service.runDue();
        if (maintenanceEnabled) themeDraftRequests.purgeExpiredBodies(100);
    }
}
