package com.chengyinhub.business.domain.dto;

/** Actual registration and actual activity parent; no economic eligibility is inferred. */
public class CoopPaymentRecoveryTarget {
    private Long registrationId;
    private Long topicId;
    private Boolean relationPresent;
    private Integer paymentStatus;
    private Integer registrationStatus;
    private String registrationNo;
    private String transactionId;
    private java.math.BigDecimal wechatPaymentAmount;
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId=value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId=value; }
    public Boolean getRelationPresent() { return relationPresent; }
    public void setRelationPresent(Boolean value) { relationPresent=value; }
    public Integer getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(Integer value) { paymentStatus=value; }
    public Integer getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(Integer value) { registrationStatus=value; }
    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String value) { registrationNo=value; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String value) { transactionId=value; }
    public java.math.BigDecimal getWechatPaymentAmount() { return wechatPaymentAmount; }
    public void setWechatPaymentAmount(java.math.BigDecimal value) { wechatPaymentAmount=value; }
}
