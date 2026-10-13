package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SettlementBatch;
import com.chengyinhub.business.domain.vo.SettlementSummaryVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SettlementBatchMapper {
    List<SettlementBatch> selectBatchList(SettlementBatch query);

    SettlementBatch selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    int insertSettlementBatch(SettlementBatch batch);

    SettlementBatch selectById(@Param("id") Long id);

    /** 确认打款状态机：仅待结算(status=0)可流转到指定状态，乐观锁防重复打款。 */
    int updateBatchStatusIfPending(@Param("id") Long id,
                                   @Param("status") Integer status,
                                   @Param("reviewerId") Long reviewerId,
                                   @Param("reviewedAt") java.util.Date reviewedAt);

    SettlementSummaryVO selectSummaryByMerchantId(@Param("merchantId") Long merchantId);

    /** settlement_batch 为空时巡检必须 fail-fast，不能把“未接线”当成“零差异”。 */
    Long countBatches();

    /** 所有批次中，批次总额与绑定明细金额合计不一致的记录。 */
    List<com.chengyinhub.business.domain.vo.SettlementBatchReconciliationVO> selectAmountMismatchBatches();
    Long countAmountMismatchBatches();
    List<com.chengyinhub.business.domain.vo.SettlementBatchReconciliationVO> sampleAmountMismatchBatches();

    /** 按 bindBatch 实际绑定成功的明细回填 total_amount/detail_count，防止幽灵批次虚报金额。 */
    int updateBatchTotals(@Param("id") Long id,
                          @Param("totalAmount") java.math.BigDecimal totalAmount,
                          @Param("detailCount") Long detailCount);
}
