package com.chengyinhub.business.domain;

import java.util.Date;

/** V2 奖励资格/投递读模型，替代 event_signup.status 与 reward_claimed 的业务真源地位。 */
public class OfficialEventRewardIssuance {
    public static final String NOT_ELIGIBLE = "NOT_ELIGIBLE";
    public static final String ELIGIBLE = "ELIGIBLE";
    public static final String PENDING_ISSUE = "PENDING_ISSUE";
    public static final String ISSUED = "ISSUED";
    public static final String CLAIMED = "CLAIMED";
    public static final String FAILED = "FAILED";
    public static final String MANUAL_REVIEW = "MANUAL_REVIEW";

    private Long id;
    private Long eventId;
    private Long memberId;
    private String rewardRuleVersion;
    private String status;
    private String issuanceKey;
    private String payloadJson;
    private String failureReason;
    private String sourceSnapshotJson;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getRewardRuleVersion() { return rewardRuleVersion; }
    public void setRewardRuleVersion(String rewardRuleVersion) { this.rewardRuleVersion = rewardRuleVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIssuanceKey() { return issuanceKey; }
    public void setIssuanceKey(String issuanceKey) { this.issuanceKey = issuanceKey; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getSourceSnapshotJson() { return sourceSnapshotJson; }
    public void setSourceSnapshotJson(String sourceSnapshotJson) { this.sourceSnapshotJson = sourceSnapshotJson; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
