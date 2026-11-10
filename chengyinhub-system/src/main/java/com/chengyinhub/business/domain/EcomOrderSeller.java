package com.chengyinhub.business.domain;

/** 以二级商户为卖家下单的支付单（ecom_order_seller）。 */
public class EcomOrderSeller {
    private String outTradeNo;
    private String subMchid;
    private String sellerType;
    private Long sellerId;
    private Long totalFen;
    private String transactionId;
    private Long registrationId;

    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }
    public String getSubMchid() { return subMchid; }
    public void setSubMchid(String subMchid) { this.subMchid = subMchid; }
    public String getSellerType() { return sellerType; }
    public void setSellerType(String sellerType) { this.sellerType = sellerType; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public Long getTotalFen() { return totalFen; }
    public void setTotalFen(Long totalFen) { this.totalFen = totalFen; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
}
