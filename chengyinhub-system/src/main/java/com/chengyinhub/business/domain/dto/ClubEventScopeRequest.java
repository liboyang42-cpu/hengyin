package com.chengyinhub.business.domain.dto;

/** 只承载俱乐部与活动作用域，不接受客户端传 actor。 */
public class ClubEventScopeRequest {
    private Long clubId;
    private Long activityId;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
}
