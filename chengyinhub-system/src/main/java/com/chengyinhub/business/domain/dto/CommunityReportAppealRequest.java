package com.chengyinhub.business.domain.dto;

public class CommunityReportAppealRequest {
    private Long reportId;
    private String reason;
    private String requestId;

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
