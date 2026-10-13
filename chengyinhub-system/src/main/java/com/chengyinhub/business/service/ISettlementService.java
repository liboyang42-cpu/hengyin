package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SettlementBatch;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.dto.SettlementGenerateDTO;
import com.chengyinhub.business.domain.vo.SettlementSummaryVO;

import java.util.List;

public interface ISettlementService {
    /** 消费某商家所有待结算明细，生成一个待打款批次。 */
    Long generateBatch(SettlementGenerateDTO request);

    /** 确认打款：把批次及其明细置为已结算，并把钱打到各收款方余额。幂等。 */
    int confirmBatch(Long batchId, Long operatorId);

    List<SettlementBatch> selectBatchList(SettlementBatch query);

    List<SettlementDetail> selectDetailList(SettlementDetail query);

    SettlementSummaryVO getSummary(Long merchantId);

    /** 对账：已结算 - 退款冲销 = 净额。 */
    SettlementSummaryVO getReconciliation(Long merchantId);
}
