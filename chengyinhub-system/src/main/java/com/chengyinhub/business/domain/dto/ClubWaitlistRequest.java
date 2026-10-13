package com.chengyinhub.business.domain.dto;

/** 候补加入/查询范围；memberId 始终取登录态。 */
public class ClubWaitlistRequest {
    private Long activityId;
    private Long ticketId;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
}
