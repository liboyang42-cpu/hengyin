package com.chengyinhub.business.domain;

import java.util.Date;

public class MerchantContributionSupplement {
    private Long id;
    private Long contributionId;
    private Long contributorMemberId;
    private String description;
    private String evidenceAssetIdsJson;
    private String requestId;
    private String payloadHash;
    private Integer expectedVersion;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContributionId() { return contributionId; }
    public void setContributionId(Long contributionId) { this.contributionId = contributionId; }
    public Long getContributorMemberId() { return contributorMemberId; }
    public void setContributorMemberId(Long contributorMemberId) { this.contributorMemberId = contributorMemberId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEvidenceAssetIdsJson() { return evidenceAssetIdsJson; }
    public void setEvidenceAssetIdsJson(String evidenceAssetIdsJson) { this.evidenceAssetIdsJson = evidenceAssetIdsJson; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
