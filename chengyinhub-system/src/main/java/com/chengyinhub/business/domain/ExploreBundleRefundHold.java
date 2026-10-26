package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonFormat;

/** F3 fulfillment freeze only; references the existing financial intent, never a payment intent. */
public class ExploreBundleRefundHold {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long registrationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long holderMemberId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long sourceRegistrationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long refundId;
    private String refundNo;
    private java.math.BigDecimal cashAmount;
    private String state;
    private Integer version;
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getHolderMemberId() { return holderMemberId; }
    public void setHolderMemberId(Long value) { holderMemberId = value; }
    public Long getSourceRegistrationId() { return sourceRegistrationId; }
    public void setSourceRegistrationId(Long value) { sourceRegistrationId = value; }
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long value) { refundId = value; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String value) { refundNo = value; }
    public java.math.BigDecimal getCashAmount() { return cashAmount; }
    public void setCashAmount(java.math.BigDecimal value) { cashAmount = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
}
