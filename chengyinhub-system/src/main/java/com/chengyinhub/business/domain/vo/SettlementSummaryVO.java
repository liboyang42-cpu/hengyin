package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;

public class SettlementSummaryVO {
    private Long merchantId;
    private Long batchCount;
    private Long detailCount;
    private BigDecimal totalAmount;
    private BigDecimal pendingAmount;
    /** 对账：已结算明细金额合计（status=1，正向）。 */
    private BigDecimal settledAmount;
    /** 对账：退款冲销金额合计（source_type=refund，负向，取绝对值）。 */
    private BigDecimal reversalAmount;
    /** 对账：净额 = settledAmount - reversalAmount。 */
    private BigDecimal netAmount;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getBatchCount() { return batchCount; }
    public void setBatchCount(Long batchCount) { this.batchCount = batchCount; }
    public Long getDetailCount() { return detailCount; }
    public void setDetailCount(Long detailCount) { this.detailCount = detailCount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getPendingAmount() { return pendingAmount; }
    public void setPendingAmount(BigDecimal pendingAmount) { this.pendingAmount = pendingAmount; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }
    public BigDecimal getReversalAmount() { return reversalAmount; }
    public void setReversalAmount(BigDecimal reversalAmount) { this.reversalAmount = reversalAmount; }
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }
}
