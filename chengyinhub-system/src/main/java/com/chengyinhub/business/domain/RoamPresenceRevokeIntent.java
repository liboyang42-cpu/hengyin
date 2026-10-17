package com.chengyinhub.business.domain;

import java.util.Date;

public class RoamPresenceRevokeIntent {
    private Long id, memberId, previousConsentId;
    private String requestId, ownerToken, eventType;
    private Date occurredAt;
    private Integer state, attemptCount;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public Long getPreviousConsentId() { return previousConsentId; }
    public void setPreviousConsentId(Long value) { previousConsentId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getOwnerToken() { return ownerToken; }
    public void setOwnerToken(String value) { ownerToken = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date value) { occurredAt = value; }
    public Integer getState() { return state; }
    public void setState(Integer value) { state = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { attemptCount = value; }
}
