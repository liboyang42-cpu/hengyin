package com.chengyinhub.business.task;

import com.chengyinhub.business.service.impl.RoamRewardDeliveryService;
import org.springframework.stereotype.Component;

/** Registration migration leaves this job paused; no production activation is performed. */
@Component("roamRewardDeliveryTask")
public class RoamRewardDeliveryTask {
    private final RoamRewardDeliveryService delivery;
    private final com.chengyinhub.business.service.impl.RoamBadgeDeliveryService badges;
    public RoamRewardDeliveryTask(RoamRewardDeliveryService delivery,
            com.chengyinhub.business.service.impl.RoamBadgeDeliveryService badges) { this.delivery = delivery; this.badges = badges; }
    public void run() {
        try { delivery.reconcile(); }
        finally { badges.reconcile(); }
    }
}
