package com.chengyinhub.business.domain;

/** 活动结算涉诉订单暂扣行(cms_activity_settlement_hold)。 */
public class ActivitySettlementHold {
    public static final int HELD = 0;
    public static final int RELEASED = 1;
    public static final int VOIDED = 2;

    private Long id;
    private Long activityId;
    private Long registrationId;
    private Integer status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
