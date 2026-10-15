package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

/** 系列场次可编辑读模型；不暴露 requestId / requestFingerprint。 */
public class ClubEventSeriesView {
    private Long id;
    private Long clubId;
    private Long topicId;
    private String recurrenceType;
    private String timezone;
    /** 既有未来场次共同的上海集合时刻(HH:mm)；无场次或时刻不一致时为 null。 */
    private String startTime;
    private Long defaultLeadMemberId;
    private Integer defaultCapacity;
    private Boolean waitlistEnabled;
    private Integer offerMinutes;
    private String status;
    private Integer version;
    private List<String> futureDates = new ArrayList<>();

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
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
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
    public List<String> getFutureDates() { return futureDates; }
    public void setFutureDates(List<String> futureDates) {
        this.futureDates = futureDates == null ? new ArrayList<String>() : futureDates;
    }
}
