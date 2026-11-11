package com.chengyinhub.business.domain;

/** 合单子单（l-payout3）。 */
public class EcomCombineSubOrder {
    private Long id;
    private String combineOutTradeNo;
    private String role;
    private String outTradeNo;
    private String businessOutTradeNo;
    private String subMchid;
    private Long amountFen;
    private Integer profitSharing;
    private String transactionId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCombineOutTradeNo() { return combineOutTradeNo; }
    public void setCombineOutTradeNo(String combineOutTradeNo) { this.combineOutTradeNo = combineOutTradeNo; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }
    public String getBusinessOutTradeNo() { return businessOutTradeNo; }
    public void setBusinessOutTradeNo(String businessOutTradeNo) { this.businessOutTradeNo = businessOutTradeNo; }
    public String getSubMchid() { return subMchid; }
    public void setSubMchid(String subMchid) { this.subMchid = subMchid; }
    public Long getAmountFen() { return amountFen; }
    public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }
    public Integer getProfitSharing() { return profitSharing; }
    public void setProfitSharing(Integer profitSharing) { this.profitSharing = profitSharing; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
}
