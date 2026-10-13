package com.chengyinhub.business.domain.dto;

import java.util.Date;

public class TopicWaitFact {
    private Long registrationId;
    private Long chapterId;
    private Date queueJoinedAt;
    private Date serviceStartedAt;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public Date getQueueJoinedAt() { return queueJoinedAt; }
    public void setQueueJoinedAt(Date queueJoinedAt) { this.queueJoinedAt = queueJoinedAt; }
    public Date getServiceStartedAt() { return serviceStartedAt; }
    public void setServiceStartedAt(Date serviceStartedAt) { this.serviceStartedAt = serviceStartedAt; }
}
