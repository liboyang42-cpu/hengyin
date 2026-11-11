package com.chengyinhub.business.domain;

/** 卖家子单补差分账的回退（l-payout3）。 */
public class EcomCombineReturn {
    private String outReturnNo;
    private Long combineSettlementId;
    private String parentOutRefundNo;
    private String returnMchid;
    private Long amountFen;
    private String state;
    private String wxReturnNo;
    private String failReason;

    public String getOutReturnNo() { return outReturnNo; }
    public void setOutReturnNo(String outReturnNo) { this.outReturnNo = outReturnNo; }
    public Long getCombineSettlementId() { return combineSettlementId; }
    public void setCombineSettlementId(Long combineSettlementId) { this.combineSettlementId = combineSettlementId; }
    public String getParentOutRefundNo() { return parentOutRefundNo; }
    public void setParentOutRefundNo(String parentOutRefundNo) { this.parentOutRefundNo = parentOutRefundNo; }
    public String getReturnMchid() { return returnMchid; }
    public void setReturnMchid(String returnMchid) { this.returnMchid = returnMchid; }
    public Long getAmountFen() { return amountFen; }
    public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getWxReturnNo() { return wxReturnNo; }
    public void setWxReturnNo(String wxReturnNo) { this.wxReturnNo = wxReturnNo; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
}
