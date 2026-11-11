package com.chengyinhub.business.domain;

/** 合单子单退款（l-payout3）。 */
public class EcomCombineRefund {
    private String outRefundNo;
    private String parentOutRefundNo;
    private String businessOutTradeNo;
    private String role;
    private String subMchid;
    private String subOutTradeNo;
    private String transactionId;
    private Long refundFen;
    private Long totalFen;
    private String state;
    private String refundId;
    private String failReason;

    public String getOutRefundNo() { return outRefundNo; }
    public void setOutRefundNo(String outRefundNo) { this.outRefundNo = outRefundNo; }
    public String getParentOutRefundNo() { return parentOutRefundNo; }
    public void setParentOutRefundNo(String parentOutRefundNo) { this.parentOutRefundNo = parentOutRefundNo; }
    public String getBusinessOutTradeNo() { return businessOutTradeNo; }
    public void setBusinessOutTradeNo(String businessOutTradeNo) { this.businessOutTradeNo = businessOutTradeNo; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getSubMchid() { return subMchid; }
    public void setSubMchid(String subMchid) { this.subMchid = subMchid; }
    public String getSubOutTradeNo() { return subOutTradeNo; }
    public void setSubOutTradeNo(String subOutTradeNo) { this.subOutTradeNo = subOutTradeNo; }
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
}
