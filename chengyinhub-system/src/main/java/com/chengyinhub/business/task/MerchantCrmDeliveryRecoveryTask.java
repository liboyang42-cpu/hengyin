package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IMerchantCrmCampaignService;
import org.springframework.stereotype.Component;

/** 有界恢复 PROCESSING 租约；已 DELIVERED recipient 不在待处理查询中，绝不会重发。 */
@Component("merchantCrmDeliveryRecoveryTask")
public class MerchantCrmDeliveryRecoveryTask {
    private final IMerchantCrmCampaignService campaignService;

    public MerchantCrmDeliveryRecoveryTask(IMerchantCrmCampaignService campaignService) {
        this.campaignService = campaignService;
    }

    public void run() {
        campaignService.recoverStaleProcessing();
    }
}
