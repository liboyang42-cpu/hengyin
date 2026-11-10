package com.chengyinhub.business.domain;

/** 按支付单的分账请求（ecom_profit_share_order）。 */
public class EcomProfitShareOrder {
    private Long id;
    private String outOrderNo;
    private String outTradeNo;
    private Long topicId;
    private String subMchid;
    private String transactionId;
    private Long totalFen;
    private Long sharedFen;
    private String planHash;
    private String state;
    private String manualReason;
    private String wxOrderId;
    private Integer attempts;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOutOrderNo() { return outOrderNo; }
    public void setOutOrderNo(String outOrderNo) { this.outOrderNo = outOrderNo; }
    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getSubMchid() { return subMchid; }
    public void setSubMchid(String subMchid) { this.subMchid = subMchid; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public Long getTotalFen() { return totalFen; }
    public void setTotalFen(Long totalFen) { this.totalFen = totalFen; }
    public Long getSharedFen() { return sharedFen; }
    public void setSharedFen(Long sharedFen) { this.sharedFen = sharedFen; }
    public String getPlanHash() { return planHash; }
    public void setPlanHash(String planHash) { this.planHash = planHash; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getManualReason() { return manualReason; }
    public void setManualReason(String manualReason) { this.manualReason = manualReason; }
    public String getWxOrderId() { return wxOrderId; }
    public void setWxOrderId(String wxOrderId) { this.wxOrderId = wxOrderId; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
}
