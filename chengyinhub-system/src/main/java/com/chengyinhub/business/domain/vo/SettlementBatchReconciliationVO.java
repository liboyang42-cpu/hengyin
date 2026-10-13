package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;

/** 结算批次与明细金额差异。 */
public class SettlementBatchReconciliationVO {
    private Long batchId;
    private Long merchantId;
    private BigDecimal batchTotalAmount;
    private BigDecimal detailTotalAmount;
    private BigDecimal differenceAmount;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public BigDecimal getBatchTotalAmount() { return batchTotalAmount; }
    public void setBatchTotalAmount(BigDecimal batchTotalAmount) { this.batchTotalAmount = batchTotalAmount; }
    public BigDecimal getDetailTotalAmount() { return detailTotalAmount; }
    public void setDetailTotalAmount(BigDecimal detailTotalAmount) { this.detailTotalAmount = detailTotalAmount; }
    public BigDecimal getDifferenceAmount() { return differenceAmount; }
    public void setDifferenceAmount(BigDecimal differenceAmount) { this.differenceAmount = differenceAmount; }
}
