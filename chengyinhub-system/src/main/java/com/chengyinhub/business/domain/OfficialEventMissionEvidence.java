package com.chengyinhub.business.domain;

import java.util.Date;

/** 一条可回放、可撤销的活动任务证据。 */
public class OfficialEventMissionEvidence {
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";

    private Long id;
    private Long eventId;
    private Long missionId;
    private Long memberId;
    private Long signupId;
    private String sourceType;
    private String sourceFactId;
    private String dedupeKey;
    private String status;
    private String reason;
    private String sourceSnapshotJson;
    private String ruleSnapshotJson;
    private Date occurredAt;
    private Date validatedAt;
    private Date revokedAt;
    private Long operatedBy;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getMissionId() { return missionId; }
    public void setMissionId(Long missionId) { this.missionId = missionId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getSignupId() { return signupId; }
    public void setSignupId(Long signupId) { this.signupId = signupId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceFactId() { return sourceFactId; }
    public void setSourceFactId(String sourceFactId) { this.sourceFactId = sourceFactId; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getSourceSnapshotJson() { return sourceSnapshotJson; }
    public void setSourceSnapshotJson(String sourceSnapshotJson) { this.sourceSnapshotJson = sourceSnapshotJson; }
    public String getRuleSnapshotJson() { return ruleSnapshotJson; }
    public void setRuleSnapshotJson(String ruleSnapshotJson) { this.ruleSnapshotJson = ruleSnapshotJson; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date occurredAt) { this.occurredAt = occurredAt; }
    public Date getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Date validatedAt) { this.validatedAt = validatedAt; }
    public Date getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Date revokedAt) { this.revokedAt = revokedAt; }
    public Long getOperatedBy() { return operatedBy; }
    public void setOperatedBy(Long operatedBy) { this.operatedBy = operatedBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
