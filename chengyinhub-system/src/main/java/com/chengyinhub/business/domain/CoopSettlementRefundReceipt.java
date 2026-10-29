package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 原订单分项/成功冲回不可变存储；写权属于结算资金事务。 */
public class CoopSettlementRefundReceipt {
    private Long id;
    private Long cashCloseId;
    private Long topicId;
    private Long refundId;
    private Long verifiedFactId;
    private String verifiedFactKind;
    private String verifiedFactHash;
    private Long sourceRegistrationId;
    private Long payerRegistrationId;
    private String currency;
    private BigDecimal refundAmount;
    private BigDecimal totalAmount;
    private BigDecimal cumulativeBefore;
    private BigDecimal cumulativeAfter;
    private BigDecimal reversedAmount;
    private String calculationVersion;
    private String sourceFingerprint;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getCashCloseId() { return cashCloseId; }
    public void setCashCloseId(Long value) { cashCloseId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long value) { refundId = value; }
    public Long getVerifiedFactId() { return verifiedFactId; }
    public void setVerifiedFactId(Long value) { verifiedFactId = value; }
    public String getVerifiedFactHash() { return verifiedFactHash; }
    public void setVerifiedFactHash(String value) { verifiedFactHash = value; }
    public String getVerifiedFactKind() { return verifiedFactKind; }
    public void setVerifiedFactKind(String value) { verifiedFactKind = value; }
    public Long getSourceRegistrationId() { return sourceRegistrationId; }
    public void setSourceRegistrationId(Long value) { sourceRegistrationId = value; }
    public Long getPayerRegistrationId() { return payerRegistrationId; }
    public void setPayerRegistrationId(Long value) { payerRegistrationId = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal value) { refundAmount = value; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal value) { totalAmount = value; }
    public BigDecimal getCumulativeBefore() { return cumulativeBefore; }
    public void setCumulativeBefore(BigDecimal value) { cumulativeBefore = value; }
    public BigDecimal getCumulativeAfter() { return cumulativeAfter; }
    public void setCumulativeAfter(BigDecimal value) { cumulativeAfter = value; }
    public BigDecimal getReversedAmount() { return reversedAmount; }
    public void setReversedAmount(BigDecimal value) { reversedAmount = value; }
    public String getCalculationVersion() { return calculationVersion; }
    public void setCalculationVersion(String value) { calculationVersion = value; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public void setSourceFingerprint(String value) { sourceFingerprint = value; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date value) { createdAt = value; }
}
