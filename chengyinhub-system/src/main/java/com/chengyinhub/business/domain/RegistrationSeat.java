package com.chengyinhub.business.domain;

import java.util.Date;

/** Stable seat extension of an existing registration; issuing a code is not verification. */
public class RegistrationSeat {
    private Long id;
    private Long registrationId;
    private Integer seatNo;
    private Integer status;
    private Long topicId;
    private Long activityId;
    private Long verifierMemberId;
    private Date verifiedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Integer getSeatNo() { return seatNo; }
    public void setSeatNo(Integer value) { seatNo = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long value) { activityId = value; }
    public Long getVerifierMemberId() { return verifierMemberId; }
    public void setVerifierMemberId(Long value) { verifierMemberId = value; }
    public Date getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Date value) { verifiedAt = value; }
}
