package com.chengyinhub.business.task;

import com.chengyinhub.business.service.impl.RoamPresenceRevokeAuditService;
import org.springframework.stereotype.Component;

@Component("roamPresenceRevokeAuditTask")
public class RoamPresenceRevokeAuditTask {
    private final RoamPresenceRevokeAuditService service;
    public RoamPresenceRevokeAuditTask(RoamPresenceRevokeAuditService service) { this.service = service; }
    public void run() { service.retryDue(); }
}
