package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/** EligibleRegistrationSet 的单条冻结事实投影。 */
public class CoopSettlementCandidate {
    private Long stationFulfillmentId;
    public Long getStationFulfillmentId() { return stationFulfillmentId; }
    public void setStationFulfillmentId(Long value) { stationFulfillmentId = value; }
    private java.util.List<Long> verifiedSeatIds;
    /** null means no canonical seat source; an explicit empty list proves zero verified seats. */
    public java.util.List<Long> getVerifiedSeatIds() { return verifiedSeatIds; }
    public void setVerifiedSeatIds(java.util.List<Long> ids) { verifiedSeatIds = ids == null ? null : java.util.List.copyOf(ids); }
    private Long registrationId;
    private Long ownerType;
    private Long ownerId;
    private Long sourceTopicId;
    private Long orderQuantity;
    private boolean ticketOwnerMatches;
    private Date fulfillmentStartInclusive;
    private Date fulfillmentEndExclusive;
    private Integer registrationStatus;
    private Integer paymentStatus;
    private Integer verificationStatus;
    private String purchaseKind;
    private BigDecimal eligibleSalesSnapshot;
    private boolean scopeCovered;
    private boolean organizerScopeCovered;
    private boolean merchantScanPresent;
    private Date merchantScanTime;
    private boolean groupEvidenceMatchesOrder;
    private Date groupVerifyTime;
    private Date groupMemberVerifyTime;
    private Date verificationTime;

    public Long getSourceTopicId() { return sourceTopicId; }
    public void setSourceTopicId(Long value) { sourceTopicId = value; }
    public Long getOrderQuantity() { return orderQuantity; }
    public void setOrderQuantity(Long value) { orderQuantity = value; }
    public boolean isTicketOwnerMatches() { return ticketOwnerMatches; }
    public void setTicketOwnerMatches(boolean value) { ticketOwnerMatches = value; }
    public Date getFulfillmentStartInclusive() { return fulfillmentStartInclusive; }
    public void setFulfillmentStartInclusive(Date value) { fulfillmentStartInclusive = value; }
    public Date getFulfillmentEndExclusive() { return fulfillmentEndExclusive; }
    public void setFulfillmentEndExclusive(Date value) { fulfillmentEndExclusive = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { this.registrationId = value; }
    public Long getOwnerType() { return ownerType; }
    public void setOwnerType(Long value) { this.ownerType = value; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { this.ownerId = value; }
    public Integer getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(Integer value) { this.registrationStatus = value; }
    public Integer getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(Integer value) { this.paymentStatus = value; }
    public Integer getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(Integer value) { this.verificationStatus = value; }
    public String getPurchaseKind() { return purchaseKind; }
    public void setPurchaseKind(String value) { this.purchaseKind = value; }
    public BigDecimal getEligibleSalesSnapshot() { return eligibleSalesSnapshot; }
    public void setEligibleSalesSnapshot(BigDecimal value) { this.eligibleSalesSnapshot = value; }
    public boolean isScopeCovered() { return scopeCovered; }
    public void setScopeCovered(boolean value) { this.scopeCovered = value; }
    public boolean isOrganizerScopeCovered() { return organizerScopeCovered; }
    public void setOrganizerScopeCovered(boolean value) { this.organizerScopeCovered = value; }
    public boolean isMerchantScanPresent() { return merchantScanPresent; }
    public void setMerchantScanPresent(boolean value) { this.merchantScanPresent = value; }
    public Date getMerchantScanTime() { return merchantScanTime; }
    public void setMerchantScanTime(Date value) { this.merchantScanTime = value; }
    public boolean isGroupEvidenceMatchesOrder() { return groupEvidenceMatchesOrder; }
    public void setGroupEvidenceMatchesOrder(boolean value) { this.groupEvidenceMatchesOrder = value; }
    public Date getGroupVerifyTime() { return groupVerifyTime; }
    public void setGroupVerifyTime(Date value) { this.groupVerifyTime = value; }
    public Date getGroupMemberVerifyTime() { return groupMemberVerifyTime; }
    public void setGroupMemberVerifyTime(Date value) { this.groupMemberVerifyTime = value; }
    public Date getVerificationTime() { return verificationTime; }
    public void setVerificationTime(Date value) { this.verificationTime = value; }
}
