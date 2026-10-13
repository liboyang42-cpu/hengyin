package com.chengyinhub.business.domain.dto;

import java.util.List;
import java.util.Map;

public class MerchantContributionSubmitRequest {
    private Long merchantId;
    private String contributionType;
    private Map<String, String> fieldChanges;
    private String issueType;
    private String description;
    private List<Long> evidenceAssetIds;
    private String idempotencyKey;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getContributionType() { return contributionType; }
    public void setContributionType(String contributionType) { this.contributionType = contributionType; }
    public Map<String, String> getFieldChanges() { return fieldChanges; }
    public void setFieldChanges(Map<String, String> fieldChanges) { this.fieldChanges = fieldChanges; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<Long> getEvidenceAssetIds() { return evidenceAssetIds; }
    public void setEvidenceAssetIds(List<Long> evidenceAssetIds) { this.evidenceAssetIds = evidenceAssetIds; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
