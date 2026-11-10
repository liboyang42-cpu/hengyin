package com.chengyinhub.business.domain;

/** 以二级商户为卖家的支付单的退款（ecom_refund）：先回退分账，再向微信电商退款。 */
public class EcomRefund {
    private String outRefundNo;
    private String outTradeNo;
    private String subMchid;
    private String transactionId;
    private Long refundFen;
    private Long totalFen;
    private String state;
    private String refundId;
    private String failReason;
    private java.util.Date createdAt;
    private java.util.Date updatedAt;

    public String getOutRefundNo() { return outRefundNo; }
    public void setOutRefundNo(String outRefundNo) { this.outRefundNo = outRefundNo; }
    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }
    public String getSubMchid() { return subMchid; }
    public void setSubMchid(String subMchid) { this.subMchid = subMchid; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public Long getRefundFen() { return refundFen; }
    public void setRefundFen(Long refundFen) { this.refundFen = refundFen; }
    public Long getTotalFen() { return totalFen; }
    public void setTotalFen(Long totalFen) { this.totalFen = totalFen; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public java.util.Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.util.Date createdAt) { this.createdAt = createdAt; }
    public java.util.Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.util.Date updatedAt) { this.updatedAt = updatedAt; }
}
