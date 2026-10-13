package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 俱乐部活动系列；CmsActivity 仍是每一期的履约事实。 */
public class ClubEventSeries extends BaseEntity {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long id;
    private Long clubId;
    private Long topicId;
    private String recurrenceType;
    private String timezone;
    private Long defaultLeadMemberId;
    private Integer defaultCapacity;
    private Boolean waitlistEnabled;
    private Integer offerMinutes;
    private String status;
    private Integer version;
    private Long createdByMemberId;
    private String requestId;
    private String requestFingerprint;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getRecurrenceType() { return recurrenceType; }
    public void setRecurrenceType(String recurrenceType) { this.recurrenceType = recurrenceType; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Long getDefaultLeadMemberId() { return defaultLeadMemberId; }
    public void setDefaultLeadMemberId(Long defaultLeadMemberId) { this.defaultLeadMemberId = defaultLeadMemberId; }
    public Integer getDefaultCapacity() { return defaultCapacity; }
    public void setDefaultCapacity(Integer defaultCapacity) { this.defaultCapacity = defaultCapacity; }
    public Boolean getWaitlistEnabled() { return waitlistEnabled; }
    public void setWaitlistEnabled(Boolean waitlistEnabled) { this.waitlistEnabled = waitlistEnabled; }
    public Integer getOfferMinutes() { return offerMinutes; }
    public void setOfferMinutes(Integer offerMinutes) { this.offerMinutes = offerMinutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getCreatedByMemberId() { return createdByMemberId; }
    public void setCreatedByMemberId(Long createdByMemberId) { this.createdByMemberId = createdByMemberId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
}
