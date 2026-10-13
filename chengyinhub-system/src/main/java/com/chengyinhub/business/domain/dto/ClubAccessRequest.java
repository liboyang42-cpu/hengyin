package com.chengyinhub.business.domain.dto;

public class ClubAccessRequest {
    private Long clubId;
    private Long activityId;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
}
