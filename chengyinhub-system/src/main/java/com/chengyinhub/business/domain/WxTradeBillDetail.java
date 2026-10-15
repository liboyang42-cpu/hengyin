package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** External bill facts in yuan; never a local balance mutation. */
public class WxTradeBillDetail {
    private Long id;
    private Date billDate;
    private String merchantId;
    private String recordKey;
    private Date tradeTime;
    private String transactionId;
    private String outTradeNo;
    private String tradeState;
    private String refundId;
    private String outRefundNo;
    private String refundStatus;
    private BigDecimal orderAmount;
    private BigDecimal settlementAmount;
    private BigDecimal couponAmount;
    private BigDecimal requestedRefundAmount;
    private BigDecimal settlementRefundAmount;
    private BigDecimal couponRefundAmount;
    private BigDecimal fee;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Date getBillDate() { return billDate; }
    public void setBillDate(Date billDate) { this.billDate = billDate; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getRecordKey() { return recordKey; }
    public void setRecordKey(String recordKey) { this.recordKey = recordKey; }

    public Date getTradeTime() { return tradeTime; }
    public void setTradeTime(Date tradeTime) { this.tradeTime = tradeTime; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }

    public String getTradeState() { return tradeState; }
    public void setTradeState(String tradeState) { this.tradeState = tradeState; }

    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }

    public String getOutRefundNo() { return outRefundNo; }
    public void setOutRefundNo(String outRefundNo) { this.outRefundNo = outRefundNo; }

    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }

    public BigDecimal getOrderAmount() { return orderAmount; }
    public void setOrderAmount(BigDecimal orderAmount) { this.orderAmount = orderAmount; }

    public BigDecimal getSettlementAmount() { return settlementAmount; }
    public void setSettlementAmount(BigDecimal settlementAmount) { this.settlementAmount = settlementAmount; }

    public BigDecimal getCouponAmount() { return couponAmount; }
    public void setCouponAmount(BigDecimal couponAmount) { this.couponAmount = couponAmount; }

    public BigDecimal getRequestedRefundAmount() { return requestedRefundAmount; }
    public void setRequestedRefundAmount(BigDecimal requestedRefundAmount) { this.requestedRefundAmount = requestedRefundAmount; }

    public BigDecimal getSettlementRefundAmount() { return settlementRefundAmount; }
    public void setSettlementRefundAmount(BigDecimal settlementRefundAmount) { this.settlementRefundAmount = settlementRefundAmount; }

    public BigDecimal getCouponRefundAmount() { return couponRefundAmount; }
    public void setCouponRefundAmount(BigDecimal couponRefundAmount) { this.couponRefundAmount = couponRefundAmount; }

    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
}
