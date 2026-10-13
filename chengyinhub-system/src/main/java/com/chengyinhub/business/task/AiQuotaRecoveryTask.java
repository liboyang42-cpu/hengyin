package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ai.AiQuotaRecoveryService;
import org.springframework.stereotype.Component;

/** 按现有 Quartz bean 调用；本次不写生产 job、不自动启用调度。 */
@Component("aiQuotaRecoveryTask")
public class AiQuotaRecoveryTask {
    private final AiQuotaRecoveryService service;
    public AiQuotaRecoveryTask(AiQuotaRecoveryService service) { this.service=service; }
    public void run() { service.runDue(); }
}
