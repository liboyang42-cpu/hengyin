package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.vo.SettlementBatchReconciliationVO;
import com.chengyinhub.business.mapper.SettlementBatchMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结算批次与明细金额一致性巡检。
 * 只报警，不改资金账，避免巡检任务引入二次资金操作。
 */
@Component("settlementReconciliationTask")
public class SettlementReconciliationTask {
    private static final Logger log = LoggerFactory.getLogger(SettlementReconciliationTask.class);

    @Autowired
    private SettlementBatchMapper settlementBatchMapper;

    @Autowired
    private AlarmService alarmService;

    public void run() {
        Long batchCount = settlementBatchMapper.countBatches();
        if (batchCount == null || batchCount == 0L) {
            alarmService.alarmThrottled("monitor.settlement.unavailable", "结算批次未接线",
                    "settlement_batch=" + (batchCount == null ? "UNKNOWN" : "0") + "，四账巡检不能证明无差异");
            throw new IllegalStateException("Settlement batch count unavailable or empty");
        }
        Long mismatchCount = settlementBatchMapper.countAmountMismatchBatches();
        if (mismatchCount != null && mismatchCount == 0L) {
            return;
        }
        List<SettlementBatchReconciliationVO> mismatches = settlementBatchMapper.sampleAmountMismatchBatches();
        StringBuilder sample = new StringBuilder();
        if (mismatches == null || mismatches.isEmpty()) {
            sample.append("UNKNOWN");
        } else {
            for (SettlementBatchReconciliationVO mismatch : mismatches.stream().limit(20).toList()) {
                if (!sample.isEmpty()) sample.append("; ");
                sample.append(mismatch == null ? "UNKNOWN" : detail(mismatch));
            }
        }
        log.warn("Settlement monitor anomaly count={} samples=[{}]", mismatchCount == null ? "UNKNOWN" : mismatchCount, sample);
        alarmService.alarmThrottled("monitor.settlement.mismatch", "结算批次金额不一致",
                "count=" + (mismatchCount == null ? "UNKNOWN" : mismatchCount) + " samples=[" + sample + "]");
        throw new IllegalStateException("Settlement mismatch count=" + (mismatchCount == null ? "UNKNOWN" : mismatchCount));
    }

    private String detail(SettlementBatchReconciliationVO mismatch) {
        return "batchId=" + (mismatch.getBatchId() == null ? "UNKNOWN" : mismatch.getBatchId())
                + " merchantId=" + (mismatch.getMerchantId() == null ? "UNKNOWN" : mismatch.getMerchantId())
                + " batchAmount=" + amount(mismatch.getBatchTotalAmount())
                + " detailAmount=" + amount(mismatch.getDetailTotalAmount())
                + " difference=" + amount(mismatch.getDifferenceAmount());
    }

    private String amount(BigDecimal value) {
        return value == null ? "UNKNOWN" : value.toPlainString();
    }
}
