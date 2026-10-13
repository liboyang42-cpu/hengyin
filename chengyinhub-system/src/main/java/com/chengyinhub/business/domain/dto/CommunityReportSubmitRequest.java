package com.chengyinhub.business.domain.dto;

import java.util.List;

public class CommunityReportSubmitRequest {
    private String targetType;
    private Long targetId;
    private String reasonCode;
    private String policyVersion;
    private String description;
    private List<Long> evidenceAssetIds;
    private String requestId;

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<Long> getEvidenceAssetIds() { return evidenceAssetIds; }
    public void setEvidenceAssetIds(List<Long> evidenceAssetIds) { this.evidenceAssetIds = evidenceAssetIds; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
