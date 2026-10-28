package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;

/** F3 套票订单冻结快照 explore_bundle_order(只插不改)。 */
public class ExploreBundleOrder {
    private Long id;
    private Long registrationId;
    private Long holderMemberId;
    private Long topicId;
    private Long activityId;
    private String productVersion;
    private String quotaVersion;
    private String policyVersion;
    private String contractVersion;
    private String settlementRuleVersion;
    private String snapshotJson;
    private BigDecimal paidAmount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validFrom;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validTo;
    private String idempotencyKey;
    private String requestFingerprint;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getHolderMemberId() { return holderMemberId; }
    public void setHolderMemberId(Long holderMemberId) { this.holderMemberId = holderMemberId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getProductVersion() { return productVersion; }
    public void setProductVersion(String productVersion) { this.productVersion = productVersion; }
    public String getQuotaVersion() { return quotaVersion; }
    public void setQuotaVersion(String quotaVersion) { this.quotaVersion = quotaVersion; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String contractVersion) { this.contractVersion = contractVersion; }
    public String getSettlementRuleVersion() { return settlementRuleVersion; }
    public void setSettlementRuleVersion(String settlementRuleVersion) { this.settlementRuleVersion = settlementRuleVersion; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }
    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
