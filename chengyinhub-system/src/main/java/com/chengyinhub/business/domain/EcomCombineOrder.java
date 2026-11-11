package com.chengyinhub.business.domain;

/** 合单支付的业务单（l-payout3）。 */
public class EcomCombineOrder {
    private String outTradeNo;
    private String sellerSubMchid;
    private String sellerType;
    private Long sellerId;
    private String platformSubMchid;
    private Long totalFen;
    private Long sellerFen;
    private Long heldFen;
    private Long commissionFen;
    private Long reserveFen;
    private String combineOutTradeNo;
    private String sellerTransactionId;
    /** 查询结算用：对应 cms_registration.id（不是表列）。 */
    private Long registrationId;

    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }
    public String getSellerSubMchid() { return sellerSubMchid; }
    public void setSellerSubMchid(String sellerSubMchid) { this.sellerSubMchid = sellerSubMchid; }
    public String getSellerType() { return sellerType; }
    public void setSellerType(String sellerType) { this.sellerType = sellerType; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getPlatformSubMchid() { return platformSubMchid; }
    public void setPlatformSubMchid(String platformSubMchid) { this.platformSubMchid = platformSubMchid; }
    public Long getTotalFen() { return totalFen; }
    public void setTotalFen(Long totalFen) { this.totalFen = totalFen; }
    public Long getSellerFen() { return sellerFen; }
    public void setSellerFen(Long sellerFen) { this.sellerFen = sellerFen; }
    public Long getHeldFen() { return heldFen; }
    public void setHeldFen(Long heldFen) { this.heldFen = heldFen; }
    public Long getCommissionFen() { return commissionFen; }
    public void setCommissionFen(Long commissionFen) { this.commissionFen = commissionFen; }
    public Long getReserveFen() { return reserveFen; }
    public void setReserveFen(Long reserveFen) { this.reserveFen = reserveFen; }
    public String getCombineOutTradeNo() { return combineOutTradeNo; }
    public void setCombineOutTradeNo(String combineOutTradeNo) { this.combineOutTradeNo = combineOutTradeNo; }
    public String getSellerTransactionId() { return sellerTransactionId; }
    public void setSellerTransactionId(String sellerTransactionId) { this.sellerTransactionId = sellerTransactionId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
}
