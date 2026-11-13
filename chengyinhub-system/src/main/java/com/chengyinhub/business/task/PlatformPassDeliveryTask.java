package com.chengyinhub.business.task;

import com.chengyinhub.business.platformpass.PlatformPassService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 通行证虚拟支付「渠道发货确认」补偿任务（sys_job 调度入口，sys_job 行登记由业主在启用虚拟支付前统一处理）。
 *
 * <p>权益已在支付回查事务里开通；这里只重试渠道 notify_provide_goods。幂等：已确认的订单不会再被取出，
 * 也不会产生第二段有效期。
 */
@Component("platformPassDeliveryTask")
public class PlatformPassDeliveryTask {
    private static final Logger log = LoggerFactory.getLogger(PlatformPassDeliveryTask.class);
    private static final int BATCH_LIMIT = 20;

    @Autowired
    private PlatformPassService service;

    public void run() {
        int driven = service.retryPendingDeliveries(BATCH_LIMIT);
        if (driven > 0) {
            log.info("[通行证] 渠道发货确认补偿本轮 driven={}", driven);
        }
    }
}
