package com.chengyinhub.business.domain.dto;

import java.util.List;

/** 俱乐部场次系列命令；日期均按 Asia/Shanghai 的本地日解释。 */
public class ClubEventSeriesRequest {
    private Long seriesId;
    private Integer expectedVersion;
    private Long clubId;
    private Long topicId;
    private String recurrenceType;
    private String startDate;
    /** 集合时刻(HH:mm,Asia/Shanghai);每个物化场次都用它拼集合时间,缺省拒绝开场。 */
    private String startTime;
    private Integer occurrenceCount;
    private List<String> customDates;
    private String rrule;
    private Long defaultLeadMemberId;
    /** 可选：仅当合作主题恰有一个票种时，覆盖该票种的未售容量。 */
    private Integer capacity;
    private Boolean waitlistEnabled;
    private Integer offerMinutes;
    private String requestId;

    public Long getSeriesId() { return seriesId; }
    public void setSeriesId(Long seriesId) { this.seriesId = seriesId; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getRecurrenceType() { return recurrenceType; }
    public void setRecurrenceType(String recurrenceType) { this.recurrenceType = recurrenceType; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public Integer getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(Integer occurrenceCount) { this.occurrenceCount = occurrenceCount; }
    public List<String> getCustomDates() { return customDates; }
    public void setCustomDates(List<String> customDates) { this.customDates = customDates; }
    public String getRrule() { return rrule; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    public Long getDefaultLeadMemberId() { return defaultLeadMemberId; }
    public void setDefaultLeadMemberId(Long defaultLeadMemberId) { this.defaultLeadMemberId = defaultLeadMemberId; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Boolean getWaitlistEnabled() { return waitlistEnabled; }
    public void setWaitlistEnabled(Boolean waitlistEnabled) { this.waitlistEnabled = waitlistEnabled; }
    public Integer getOfferMinutes() { return offerMinutes; }
    public void setOfferMinutes(Integer offerMinutes) { this.offerMinutes = offerMinutes; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
