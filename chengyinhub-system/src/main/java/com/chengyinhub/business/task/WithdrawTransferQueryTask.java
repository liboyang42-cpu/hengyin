package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ICashWithdrawService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 提现出金状态巡检:查询「处理中」的提现单在微信侧的最终状态,推进状态机(修复「已受理即成功」)。
 * 由 sys_job 定时调用 withdrawTransferQueryTask.run()。禁止并发(concurrent=1),避免同单重复处理。
 */
@Component("withdrawTransferQueryTask")
public class WithdrawTransferQueryTask {

    private static final Logger logger = LoggerFactory.getLogger(WithdrawTransferQueryTask.class);

    /** 单次巡检处理条数上限 */
    private static final int BATCH_LIMIT = 200;

    @Autowired
    private ICashWithdrawService cashWithdrawService;

    public void run() {
        int handled = cashWithdrawService.syncPendingWithdrawals(BATCH_LIMIT);
        if (handled > 0) {
            logger.info("提现出金状态巡检完成,处理 {} 条", handled);
        }
    }
}
