package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IChannelRefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 渠道退款补偿消费任务（sys_job 调度入口）。
 *
 * <p>退款事实先独立提交、消费随后进行；若消费事务失败且 provider 不再重投，本任务负责把
 * "已验证但未消费"的事实重新驱动到 M2 的撤销入口。幂等由 M2 的撤销 CAS 与冲销事件键保证，
 * 不会二次撤权或二次冲销。
 *
 * <p>bean 名与既有任务命名一致（{@code marketingRevenuePostingTask} 等），
 * sys_job 行登记由 M0 统一处理；本类只提供可调用的入口。
 */
@Component("channelRefundConsumptionTask")
public class ChannelRefundConsumptionTask {

    private static final Logger log = LoggerFactory.getLogger(ChannelRefundConsumptionTask.class);

    /** 单轮上限：够用且不长时间占用连接。 */
    private static final int BATCH_LIMIT = 20;

    @Autowired
    private IChannelRefundService refundService;

    public void run() {
        int driven = refundService.retryPendingConsumptions(BATCH_LIMIT);
        if (driven > 0) {
            log.info("[M8] 渠道退款补偿消费本轮 driven={}", driven);
        }
    }
}
