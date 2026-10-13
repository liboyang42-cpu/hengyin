package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * P1 集合签到 club_lead_checkin(uk=activity+member 幂等)。
 */
public class ClubLeadCheckin {
    private Long id;
    private Long activityId;
    private Long memberId;
    private Date arrivedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Date getArrivedAt() { return arrivedAt; }
    public void setArrivedAt(Date arrivedAt) { this.arrivedAt = arrivedAt; }
}
