package com.chengyinhub.business.domain;

/** 平台为卖家的份额按供应商结算付给对方（l-payout3）。 */
public class EcomSupplierPayable {
    private Long id;
    private String source;
    private String businessOutTradeNo;
    private Long topicId;
    private Long settlementId;
    private String lineType;
    private Long payeeMemberId;
    private Long amountFen;
    private String state;
    private String paidReference;
    private String paidBy;
    private java.util.Date paidAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getBusinessOutTradeNo() { return businessOutTradeNo; }
    public void setBusinessOutTradeNo(String businessOutTradeNo) { this.businessOutTradeNo = businessOutTradeNo; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public String getLineType() { return lineType; }
    public void setLineType(String lineType) { this.lineType = lineType; }
    public Long getPayeeMemberId() { return payeeMemberId; }
    public void setPayeeMemberId(Long payeeMemberId) { this.payeeMemberId = payeeMemberId; }
    public Long getAmountFen() { return amountFen; }
    public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPaidReference() { return paidReference; }
    public void setPaidReference(String paidReference) { this.paidReference = paidReference; }
    public String getPaidBy() { return paidBy; }
    public void setPaidBy(String paidBy) { this.paidBy = paidBy; }
    public java.util.Date getPaidAt() { return paidAt; }
    public void setPaidAt(java.util.Date paidAt) { this.paidAt = paidAt; }
}
