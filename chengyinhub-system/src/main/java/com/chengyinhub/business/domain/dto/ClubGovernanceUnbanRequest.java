package com.chengyinhub.business.domain.dto;

public class ClubGovernanceUnbanRequest {
    private Long clubId;
    private Long banId;
    private Integer version;
    private String reason;
    private String requestId;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getBanId() { return banId; }
    public void setBanId(Long banId) { this.banId = banId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
