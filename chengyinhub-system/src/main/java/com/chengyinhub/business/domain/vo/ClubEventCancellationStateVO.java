package com.chengyinhub.business.domain.vo;

/** 独立回读取消状态；必须同时观察 occurrence 与真实 CmsActivity。 */
public class ClubEventCancellationStateVO {
    private Long clubId;
    private Long activityId;
    private String occurrenceStatus;
    private boolean activityCancelled;
    private Long publishStatus;
    private String cancelReason;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getOccurrenceStatus() { return occurrenceStatus; }
    public void setOccurrenceStatus(String occurrenceStatus) { this.occurrenceStatus = occurrenceStatus; }
    public boolean isActivityCancelled() { return activityCancelled; }
    public void setActivityCancelled(boolean activityCancelled) { this.activityCancelled = activityCancelled; }
    public Long getPublishStatus() { return publishStatus; }
    public void setPublishStatus(Long publishStatus) { this.publishStatus = publishStatus; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
}
