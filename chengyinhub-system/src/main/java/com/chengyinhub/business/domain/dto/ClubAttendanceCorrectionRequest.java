package com.chengyinhub.business.domain.dto;

public class ClubAttendanceCorrectionRequest {
    private Long clubId;
    private Long activityId;
    private Long memberId;
    private Boolean arrived;
    private String reason;
    private Integer expectedVersion;
    private String requestId;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Boolean getArrived() { return arrived; }
    public void setArrived(Boolean arrived) { this.arrived = arrived; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
