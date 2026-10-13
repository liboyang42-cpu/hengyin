package com.chengyinhub.business.domain;

import java.util.Date;

/** 集合签到的 append-only 更正；不改写 club_lead_checkin 原始事实。 */
public class ClubEventAttendanceCorrection {
    private Long id;
    private Long clubId;
    private Long activityId;
    private Long memberId;
    private Boolean originalArrived;
    private Boolean newArrived;
    private Date originalArrivedAt;
    private Date newArrivedAt;
    private String reason;
    private Long actorMemberId;
    private String requestId;
    private Integer version;
    private Date correctedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Boolean getOriginalArrived() { return originalArrived; }
    public void setOriginalArrived(Boolean originalArrived) { this.originalArrived = originalArrived; }
    public Boolean getNewArrived() { return newArrived; }
    public void setNewArrived(Boolean newArrived) { this.newArrived = newArrived; }
    public Date getOriginalArrivedAt() { return originalArrivedAt; }
    public void setOriginalArrivedAt(Date originalArrivedAt) { this.originalArrivedAt = originalArrivedAt; }
    public Date getNewArrivedAt() { return newArrivedAt; }
    public void setNewArrivedAt(Date newArrivedAt) { this.newArrivedAt = newArrivedAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long actorMemberId) { this.actorMemberId = actorMemberId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCorrectedAt() { return correctedAt; }
    public void setCorrectedAt(Date correctedAt) { this.correctedAt = correctedAt; }
}
