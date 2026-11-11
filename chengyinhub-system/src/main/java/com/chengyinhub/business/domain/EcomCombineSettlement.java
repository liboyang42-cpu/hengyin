package com.chengyinhub.business.domain;

/** 合单结算：卖家子单补差分账或完结（l-payout3）。 */
public class EcomCombineSettlement {
    private Long id;
    private String outOrderNo;
    private String businessOutTradeNo;
    private Long topicId;
    private String sellerSubMchid;
    private String sellerTransactionId;
    private Long sellerFen;
    private Long heldFen;
    private Long platformFen;
    private Long shortfallFen;
    private Long surplusFen;
    private String planHash;
    private String state;
    private String manualReason;
    private String wxOrderId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOutOrderNo() { return outOrderNo; }
    public void setOutOrderNo(String outOrderNo) { this.outOrderNo = outOrderNo; }
    public String getBusinessOutTradeNo() { return businessOutTradeNo; }
    public void setBusinessOutTradeNo(String businessOutTradeNo) { this.businessOutTradeNo = businessOutTradeNo; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getSellerSubMchid() { return sellerSubMchid; }
    public void setSellerSubMchid(String sellerSubMchid) { this.sellerSubMchid = sellerSubMchid; }
    public String getSellerTransactionId() { return sellerTransactionId; }
    public void setSellerTransactionId(String sellerTransactionId) { this.sellerTransactionId = sellerTransactionId; }
    public Long getSellerFen() { return sellerFen; }
    public void setSellerFen(Long sellerFen) { this.sellerFen = sellerFen; }
    public Long getHeldFen() { return heldFen; }
    public void setHeldFen(Long heldFen) { this.heldFen = heldFen; }
    public Long getPlatformFen() { return platformFen; }
    public void setPlatformFen(Long platformFen) { this.platformFen = platformFen; }
    public Long getShortfallFen() { return shortfallFen; }
    public void setShortfallFen(Long shortfallFen) { this.shortfallFen = shortfallFen; }
    public Long getSurplusFen() { return surplusFen; }
    public void setSurplusFen(Long surplusFen) { this.surplusFen = surplusFen; }
    public String getPlanHash() { return planHash; }
    public void setPlanHash(String planHash) { this.planHash = planHash; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getManualReason() { return manualReason; }
    public void setManualReason(String manualReason) { this.manualReason = manualReason; }
    public String getWxOrderId() { return wxOrderId; }
    public void setWxOrderId(String wxOrderId) { this.wxOrderId = wxOrderId; }
}
