package com.chengyinhub.business.domain.dto;

import java.util.List;

public class MerchantContributionSupplementRequest {
    private Long contributionId;
    private Integer expectedVersion;
    private String description;
    private List<Long> evidenceAssetIds;
    private String requestId;

    public Long getContributionId() { return contributionId; }
    public void setContributionId(Long contributionId) { this.contributionId = contributionId; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<Long> getEvidenceAssetIds() { return evidenceAssetIds; }
    public void setEvidenceAssetIds(List<Long> evidenceAssetIds) { this.evidenceAssetIds = evidenceAssetIds; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
