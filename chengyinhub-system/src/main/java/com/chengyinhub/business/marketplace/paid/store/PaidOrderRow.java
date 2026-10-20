package com.chengyinhub.business.marketplace.paid.store;
/** Persistence row; never serialize as a public response. */
public final class PaidOrderRow {
    private Long id;
    public Long getId(){return id;} public void setId(Long value){id=value;}
    private Long memberId;
    public Long getMemberId(){return memberId;} public void setMemberId(Long value){memberId=value;}
    private Long merchantId;
    public Long getMerchantId(){return merchantId;} public void setMerchantId(Long value){merchantId=value;}
    private Long status;
    public Long getStatus(){return status;} public void setStatus(Long value){status=value;}
    private Long payType;
    public Long getPayType(){return payType;} public void setPayType(Long value){payType=value;}
    private Long aftersaleStatus;
    public Long getAftersaleStatus(){return aftersaleStatus;} public void setAftersaleStatus(Long value){aftersaleStatus=value;}
    private Long productQuantity;
    public Long getProductQuantity(){return productQuantity;} public void setProductQuantity(Long value){productQuantity=value;}
    private String orderSn;
    public String getOrderSn(){return orderSn;} public void setOrderSn(String value){orderSn=value;}
    private String bizType;
    public String getBizType(){return bizType;} public void setBizType(String value){bizType=value;}
    private String transactionId;
    public String getTransactionId(){return transactionId;} public void setTransactionId(String value){transactionId=value;}
    private Integer delFlag;
    public Integer getDelFlag(){return delFlag;} public void setDelFlag(Integer value){delFlag=value;}
    private Integer entitlementDays;
    public Integer getEntitlementDays(){return entitlementDays;} public void setEntitlementDays(Integer value){entitlementDays=value;}
    private Integer entitlementMaxUsage;
    public Integer getEntitlementMaxUsage(){return entitlementMaxUsage;} public void setEntitlementMaxUsage(Integer value){entitlementMaxUsage=value;}
    private java.math.BigDecimal payAmount;
    public java.math.BigDecimal getPayAmount(){return payAmount;} public void setPayAmount(java.math.BigDecimal value){payAmount=value;}
    private java.math.BigDecimal orderTotalAmount;
    public java.math.BigDecimal getOrderTotalAmount(){return orderTotalAmount;} public void setOrderTotalAmount(java.math.BigDecimal value){orderTotalAmount=value;}
    private java.math.BigDecimal productTotalAmount;
    public java.math.BigDecimal getProductTotalAmount(){return productTotalAmount;} public void setProductTotalAmount(java.math.BigDecimal value){productTotalAmount=value;}
    private java.math.BigDecimal deliveryFee;
    public java.math.BigDecimal getDeliveryFee(){return deliveryFee;} public void setDeliveryFee(java.math.BigDecimal value){deliveryFee=value;}
    private java.math.BigDecimal taxFee;
    public java.math.BigDecimal getTaxFee(){return taxFee;} public void setTaxFee(java.math.BigDecimal value){taxFee=value;}
    private java.math.BigDecimal pointsAmount;
    public java.math.BigDecimal getPointsAmount(){return pointsAmount;} public void setPointsAmount(java.math.BigDecimal value){pointsAmount=value;}
    private java.math.BigDecimal couponAmount;
    public java.math.BigDecimal getCouponAmount(){return couponAmount;} public void setCouponAmount(java.math.BigDecimal value){couponAmount=value;}
    private java.sql.Timestamp paymentTime;
    public java.sql.Timestamp getPaymentTime(){return paymentTime;} public void setPaymentTime(java.sql.Timestamp value){paymentTime=value;}
    @Override public String toString(){return "PaidOrderRow[redacted]";}
}
