package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IMarketingRevenuePostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 收入分配队列消费任务（既有 sys_job 调度入口）。
 *
 * <p>bean 名 {@code marketingRevenuePostingTask} 与既有任务命名一致
 * （{@code settlementReconciliationTask.run()} / {@code merchantCommerceReconcileTask.reconcile()}），
 * 由 sys_job 的 {@code invoke_target} 反射调用。
 *
 * <p>本任务只做两件事：
 * <ol>
 *   <li>回收租约过期任务（进程崩溃/超时后不会永久卡在 CLAIMED）；</li>
 *   <li>消费到期任务，限额处理，避免长时间占用连接。</li>
 * </ol>
 * 不派发资金、不读写余额。
 */
@Component("marketingRevenuePostingTask")
public class MarketingRevenuePostingTask {

    private static final Logger log = LoggerFactory.getLogger(MarketingRevenuePostingTask.class);

    /** 单轮上限：够用且不会长时间占连接。 */
    private static final int BATCH_LIMIT = 50;

    @Autowired
    private IMarketingRevenuePostingService postingService;

    public void run() {
        int reclaimed = postingService.reclaimExpiredLeases();
        int processed = postingService.consumeDue(BATCH_LIMIT);
        if (reclaimed > 0 || processed > 0) {
            log.info("[M5] 收入分配队列本轮 reclaimed={} processed={}", reclaimed, processed);
        }
    }
}
