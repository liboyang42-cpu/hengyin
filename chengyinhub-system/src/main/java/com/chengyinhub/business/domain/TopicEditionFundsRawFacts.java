package com.chengyinhub.business.domain;

import java.math.BigDecimal;

/** 从活动壳、退款终态和权益读出的期级原始资金事实。 */
public class TopicEditionFundsRawFacts {
    private Long nCash;
    private BigDecimal gCash;
    private BigDecimal uOut;
    private BigDecimal uPending;
    private BigDecimal rRefund;
    private Long unknownCount;
    private Long invalidRefundCount;

    public Long getNCash() { return nCash; }
    public void setNCash(Long nCash) { this.nCash = nCash; }
    public BigDecimal getGCash() { return gCash; }
    public void setGCash(BigDecimal gCash) { this.gCash = gCash; }
    public BigDecimal getUOut() { return uOut; }
    public void setUOut(BigDecimal uOut) { this.uOut = uOut; }
    public BigDecimal getUPending() { return uPending; }
    public void setUPending(BigDecimal uPending) { this.uPending = uPending; }
    public BigDecimal getRRefund() { return rRefund; }
    public void setRRefund(BigDecimal rRefund) { this.rRefund = rRefund; }
    public Long getUnknownCount() { return unknownCount; }
    public void setUnknownCount(Long unknownCount) { this.unknownCount = unknownCount; }
    public Long getInvalidRefundCount() { return invalidRefundCount; }
    public void setInvalidRefundCount(Long invalidRefundCount) { this.invalidRefundCount = invalidRefundCount; }
}
