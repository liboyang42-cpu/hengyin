package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 原订单分项/成功冲回不可变存储；写权属于结算资金事务。 */
public class CoopSettlementOriginalComponent {
    private Long id;
    private Long cashCloseId;
    private Long topicId;
    private Long sourceRegistrationId;
    private Long payerRegistrationId;
    private Long settlementId;
    private Long sourceClaimId;
    private Long paymentFactId;
    private String paymentFactHash;
    private String receiverKind;
    private Long receiverMemberId;
    private String lineType;
    private BigDecimal originalAmount;
    private BigDecimal originalOrderCash;
    private String calculationVersion;
    private String sourceFingerprint;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getCashCloseId() { return cashCloseId; }
    public void setCashCloseId(Long value) { cashCloseId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Long getSourceRegistrationId() { return sourceRegistrationId; }
    public void setSourceRegistrationId(Long value) { sourceRegistrationId = value; }
    public Long getPayerRegistrationId() { return payerRegistrationId; }
    public void setPayerRegistrationId(Long value) { payerRegistrationId = value; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long value) { settlementId = value; }
    public Long getPaymentFactId() { return paymentFactId; }
    public void setPaymentFactId(Long value) { paymentFactId = value; }
    public String getPaymentFactHash() { return paymentFactHash; }
    public void setPaymentFactHash(String value) { paymentFactHash = value; }
    public Long getSourceClaimId() { return sourceClaimId; }
    public void setSourceClaimId(Long value) { sourceClaimId = value; }
    public String getReceiverKind() { return receiverKind; }
    public void setReceiverKind(String value) { receiverKind = value; }
    public Long getReceiverMemberId() { return receiverMemberId; }
    public void setReceiverMemberId(Long value) { receiverMemberId = value; }
    public String getLineType() { return lineType; }
    public void setLineType(String value) { lineType = value; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal value) { originalAmount = value; }
    public BigDecimal getOriginalOrderCash() { return originalOrderCash; }
    public void setOriginalOrderCash(BigDecimal value) { originalOrderCash = value; }
    public String getCalculationVersion() { return calculationVersion; }
    public void setCalculationVersion(String value) { calculationVersion = value; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public void setSourceFingerprint(String value) { sourceFingerprint = value; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date value) { createdAt = value; }
}
