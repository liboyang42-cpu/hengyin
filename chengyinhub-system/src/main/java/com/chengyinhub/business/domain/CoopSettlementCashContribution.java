package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 不可变来源证据的存储 DTO；不表示资金归一或支付执行成功。 */
public class CoopSettlementCashContribution {
    private Long id;
    private Long cashCloseId;
    private Long registrationId;
    private Long payerRegistrationId;
    private String parentOrderNo;
    private String registrationNo;
    private String payerOrderNo;
    private Integer ownerType;
    private Long ownerId;
    private Long sourceTopicId;
    private Integer snapshotPaymentStatus;
    private Integer snapshotRegistrationStatus;
    private Integer snapshotVerificationStatus;
    private BigDecimal snapshotPayableAmount;
    private BigDecimal salesBasisAmount;
    private String registrationSnapshot;
    private String sourceFingerprint;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getCashCloseId() { return cashCloseId; }
    public void setCashCloseId(Long value) { cashCloseId = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getPayerRegistrationId() { return payerRegistrationId; }
    public void setPayerRegistrationId(Long value) { payerRegistrationId = value; }
    public String getParentOrderNo() { return parentOrderNo; }
    public void setParentOrderNo(String value) { parentOrderNo = value; }
    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String value) { registrationNo = value; }
    public String getPayerOrderNo() { return payerOrderNo; }
    public void setPayerOrderNo(String value) { payerOrderNo = value; }
    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer value) { ownerType = value; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { ownerId = value; }
    public Long getSourceTopicId() { return sourceTopicId; }
    public void setSourceTopicId(Long value) { sourceTopicId = value; }
    public Integer getSnapshotPaymentStatus() { return snapshotPaymentStatus; }
    public void setSnapshotPaymentStatus(Integer value) { snapshotPaymentStatus = value; }
    public Integer getSnapshotRegistrationStatus() { return snapshotRegistrationStatus; }
    public void setSnapshotRegistrationStatus(Integer value) { snapshotRegistrationStatus = value; }
    public Integer getSnapshotVerificationStatus() { return snapshotVerificationStatus; }
    public void setSnapshotVerificationStatus(Integer value) { snapshotVerificationStatus = value; }
    public BigDecimal getSnapshotPayableAmount() { return snapshotPayableAmount; }
    public void setSnapshotPayableAmount(BigDecimal value) { snapshotPayableAmount = value; }
    public BigDecimal getSalesBasisAmount() { return salesBasisAmount; }
    public void setSalesBasisAmount(BigDecimal value) { salesBasisAmount = value; }
    public String getRegistrationSnapshot() { return registrationSnapshot; }
    public void setRegistrationSnapshot(String value) { registrationSnapshot = value; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public void setSourceFingerprint(String value) { sourceFingerprint = value; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date value) { createdAt = value; }
}
