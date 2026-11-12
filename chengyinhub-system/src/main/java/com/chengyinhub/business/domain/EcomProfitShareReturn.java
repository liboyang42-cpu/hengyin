package com.chengyinhub.business.domain;

/** 分账回退（ecom_profit_share_return）。 */
public class EcomProfitShareReturn {
    private Long id;
    private String outReturnNo;
    private Long shareOrderId;
    private String outRefundNo;
    private String returnMchid;
    private Long amountFen;
    private String state;
    private String wxReturnNo;
    private String failReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOutReturnNo() { return outReturnNo; }
    public void setOutReturnNo(String outReturnNo) { this.outReturnNo = outReturnNo; }
    public Long getShareOrderId() { return shareOrderId; }
    public void setShareOrderId(Long shareOrderId) { this.shareOrderId = shareOrderId; }
    public String getOutRefundNo() { return outRefundNo; }
    public void setOutRefundNo(String outRefundNo) { this.outRefundNo = outRefundNo; }
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
    private String returnType = "MERCHANT_ID";
    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }
    private String returnAppid = "";
    public String getReturnAppid() { return returnAppid; }
    public void setReturnAppid(String returnAppid) { this.returnAppid = returnAppid; }
}
