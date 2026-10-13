package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ICashWithdrawService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 提现出金日对账/健康巡检:处理中积压(巡检健康)、待人工积压(财务待办)、成功无批次号(数据异常)超阈值告警。
 * 由 sys_job 每日调用 withdrawReconciliationTask.run()。纯 DB 聚合,不额外调微信(实时对账由 withdrawTransferQueryTask 做)。
 */
@Component("withdrawReconciliationTask")
public class WithdrawReconciliationTask {

    /** 处理中超过此时长计入积压(应已被 6h 巡检升级,仍积压=巡检异常) */
    private static final int STUCK_HOURS = 12;

    @Autowired
    private ICashWithdrawService cashWithdrawService;

    public void run() {
        cashWithdrawService.reconcile(STUCK_HOURS);
    }
}
