package com.chengyinhub.business.task;

import com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper;
import com.chengyinhub.business.service.IGrowthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 仅恢复核销事务已保存的奖励，二维码失效及进程重启不影响待办。 */
@Component("storeVerifyGrowthTask")
public class StoreVerifyGrowthTask {
    private static final Logger log = LoggerFactory.getLogger(StoreVerifyGrowthTask.class);
    private final StoreVerifyGrowthDeliveryMapper mapper;
    private final IGrowthService growthService;

    public StoreVerifyGrowthTask(StoreVerifyGrowthDeliveryMapper mapper, IGrowthService growthService) {
        this.mapper = mapper;
        this.growthService = growthService;
    }

    public void run() {
        for (Long registrationId : mapper.selectDue()) {
            try {
                growthService.deliverStoreVerification(registrationId);
            } catch (Exception failure) {
                log.error("核销成长奖励恢复失败 registrationId={}", registrationId, failure);
                String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                mapper.recordFailure(registrationId, error.substring(0, Math.min(500, error.length())));
            }
        }
    }
}
