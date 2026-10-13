package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.vo.SettlementBatchReconciliationVO;
import com.chengyinhub.business.mapper.SettlementBatchMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结算批次与明细金额一致性巡检。
 * 只报警，不改资金账，避免巡检任务引入二次资金操作。
 */
@Component("settlementReconciliationTask")
public class SettlementReconciliationTask {

    @Autowired
    private SettlementBatchMapper settlementBatchMapper;

    @Autowired
    private AlarmService alarmService;

    public void run() {
        Long batchCount = settlementBatchMapper.countBatches();
        if (batchCount == null || batchCount == 0L) {
            alarmService.alarm("结算批次未接线", "settlement_batch=0，四账巡检不能证明无差异");
            throw new IllegalStateException("settlement_batch=0，结算巡检失败");
        }
        List<SettlementBatchReconciliationVO> mismatches = settlementBatchMapper.selectAmountMismatchBatches();
        if (mismatches == null || mismatches.isEmpty()) {
            return;
        }
        for (SettlementBatchReconciliationVO mismatch : mismatches) {
            alarmService.alarm("结算批次金额不一致", detail(mismatch));
        }
        throw new IllegalStateException("发现结算批次金额不一致，count=" + mismatches.size());
    }

    private String detail(SettlementBatchReconciliationVO mismatch) {
        return "batchId=" + mismatch.getBatchId()
                + " merchantId=" + mismatch.getMerchantId()
                + " batchAmount=" + amount(mismatch.getBatchTotalAmount())
                + " detailAmount=" + amount(mismatch.getDetailTotalAmount())
                + " difference=" + amount(mismatch.getDifferenceAmount());
    }

    private String amount(BigDecimal value) {
        return value == null ? "UNKNOWN" : value.toPlainString();
    }
}
