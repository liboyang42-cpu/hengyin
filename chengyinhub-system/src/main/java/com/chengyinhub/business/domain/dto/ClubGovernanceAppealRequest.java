package com.chengyinhub.business.domain.dto;

public class ClubGovernanceAppealRequest {
    private Long clubId;
    private String reason;
    private String requestId;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
