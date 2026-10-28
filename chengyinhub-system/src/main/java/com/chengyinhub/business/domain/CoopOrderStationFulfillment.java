package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** Append-only link from an original V1 order to its actual station verification and sale. */
public class CoopOrderStationFulfillment {
    private Long id;
    private Long orderId;
    private Long topicId;
    private Long beneficiaryMemberId;
    private Long registrationId;
    private Long stationId;
    private Long verificationAuditId;
    private BigDecimal salesBasisAmount;
    private String orderTermsSha256;
    private String sourceFingerprint;
    private Date fulfilledAt;
    private Date recordedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { orderId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Long getBeneficiaryMemberId() { return beneficiaryMemberId; }
    public void setBeneficiaryMemberId(Long value) { beneficiaryMemberId = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long value) { stationId = value; }
    public Long getVerificationAuditId() { return verificationAuditId; }
    public void setVerificationAuditId(Long value) { verificationAuditId = value; }
    public BigDecimal getSalesBasisAmount() { return salesBasisAmount; }
    public void setSalesBasisAmount(BigDecimal value) { salesBasisAmount = value; }
    public String getOrderTermsSha256() { return orderTermsSha256; }
    public void setOrderTermsSha256(String value) { orderTermsSha256 = value; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public void setSourceFingerprint(String value) { sourceFingerprint = value; }
    public Date getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(Date value) { fulfilledAt = value; }
    public Date getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Date value) { recordedAt = value; }
}
