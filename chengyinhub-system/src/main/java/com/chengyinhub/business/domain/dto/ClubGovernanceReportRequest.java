package com.chengyinhub.business.domain.dto;

import java.util.Map;

public class ClubGovernanceReportRequest {
    private Long clubId;
    private String targetType;
    private Long targetId;
    private Map<String, String> evidence;
    private String reason;
    private String requestId;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Map<String, String> getEvidence() { return evidence; }
    public void setEvidence(Map<String, String> evidence) { this.evidence = evidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
