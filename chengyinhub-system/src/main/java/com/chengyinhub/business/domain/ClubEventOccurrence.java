package com.chengyinhub.business.domain;

import java.util.Date;

/** 系列到每期 CmsActivity 的稳定关联。 */
public class ClubEventOccurrence {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CANCELLED = "CANCELLED";
    private Long id;
    private Long seriesId;
    private Long activityId;
    private Date occurrenceAt;
    private Long defaultLeadMemberId;
    private String status;
    private Integer version;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSeriesId() { return seriesId; }
    public void setSeriesId(Long seriesId) { this.seriesId = seriesId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Date getOccurrenceAt() { return occurrenceAt; }
    public void setOccurrenceAt(Date occurrenceAt) { this.occurrenceAt = occurrenceAt; }
    public Long getDefaultLeadMemberId() { return defaultLeadMemberId; }
    public void setDefaultLeadMemberId(Long defaultLeadMemberId) { this.defaultLeadMemberId = defaultLeadMemberId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
