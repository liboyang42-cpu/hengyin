package com.chengyinhub.business.domain.dto;

/** 取消一个已物化 CmsActivity 场次；actor 只从登录态解析。 */
public class ClubEventCancellationRequest {
    private Long clubId;
    private Long activityId;
    private String reason;
    private String requestId;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
