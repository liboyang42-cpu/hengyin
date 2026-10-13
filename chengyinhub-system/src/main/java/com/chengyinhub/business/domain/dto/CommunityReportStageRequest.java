package com.chengyinhub.business.domain.dto;

import java.util.Date;

public class CommunityReportStageRequest {
    private Long reportId;
    private Integer expectedVersion;
    private String nextStage;
    private String publicNote;
    private String internalNote;
    private String decisionSummary;
    private Date estimatedResolveAt;
    private String requestId;

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getNextStage() { return nextStage; }
    public void setNextStage(String nextStage) { this.nextStage = nextStage; }
    public String getPublicNote() { return publicNote; }
    public void setPublicNote(String publicNote) { this.publicNote = publicNote; }
    public String getInternalNote() { return internalNote; }
    public void setInternalNote(String internalNote) { this.internalNote = internalNote; }
    public String getDecisionSummary() { return decisionSummary; }
    public void setDecisionSummary(String decisionSummary) { this.decisionSummary = decisionSummary; }
    public Date getEstimatedResolveAt() { return estimatedResolveAt; }
    public void setEstimatedResolveAt(Date estimatedResolveAt) { this.estimatedResolveAt = estimatedResolveAt; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
