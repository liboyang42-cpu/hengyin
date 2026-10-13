package com.chengyinhub.business.domain;

import java.util.Date;

public class MerchantContribution {
    public static final String TYPE_INFO_UPDATE = "INFO_UPDATE";
    public static final String TYPE_ISSUE_REPORT = "ISSUE_REPORT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_NEEDS_EVIDENCE = "NEEDS_EVIDENCE";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_MERGED = "MERGED";

    private Long id;
    private Long merchantId;
    private Long contributorMemberId;
    private String contributionType;
    private String fieldChangesJson;
    private String baseValuesJson;
    private String issueType;
    private String description;
    private String evidenceAssetIdsJson;
    private String idempotencyKey;
    private String scopeKey;
    private String payloadHash;
    private String reviewStatus;
    private String reviewReason;
    private Boolean canSupplement;
    private Long reviewedByUserId;
    private Date reviewedAt;
    private Date mergedAt;
    private Integer version;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getContributorMemberId() { return contributorMemberId; }
    public void setContributorMemberId(Long contributorMemberId) { this.contributorMemberId = contributorMemberId; }
    public String getContributionType() { return contributionType; }
    public void setContributionType(String contributionType) { this.contributionType = contributionType; }
    public String getFieldChangesJson() { return fieldChangesJson; }
    public void setFieldChangesJson(String fieldChangesJson) { this.fieldChangesJson = fieldChangesJson; }
    public String getBaseValuesJson() { return baseValuesJson; }
    public void setBaseValuesJson(String baseValuesJson) { this.baseValuesJson = baseValuesJson; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEvidenceAssetIdsJson() { return evidenceAssetIdsJson; }
    public void setEvidenceAssetIdsJson(String evidenceAssetIdsJson) { this.evidenceAssetIdsJson = evidenceAssetIdsJson; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }
    public Boolean getCanSupplement() { return canSupplement; }
    public void setCanSupplement(Boolean canSupplement) { this.canSupplement = canSupplement; }
    public Long getReviewedByUserId() { return reviewedByUserId; }
    public void setReviewedByUserId(Long reviewedByUserId) { this.reviewedByUserId = reviewedByUserId; }
    public Date getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Date reviewedAt) { this.reviewedAt = reviewedAt; }
    public Date getMergedAt() { return mergedAt; }
    public void setMergedAt(Date mergedAt) { this.mergedAt = mergedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
