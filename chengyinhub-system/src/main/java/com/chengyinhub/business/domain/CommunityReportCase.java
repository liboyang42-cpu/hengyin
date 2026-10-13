package com.chengyinhub.business.domain;

import java.util.Date;

/** 举报内部事实；reporterMemberId 仅供 owner/platform 查询，禁止进入被举报方投影。 */
public class CommunityReportCase {
    public static final String TARGET_MEMBER = "MEMBER";
    public static final String TARGET_MERCHANT = "MERCHANT";
    public static final String TARGET_CLUB = "CLUB";
    public static final String TARGET_ACTIVITY = "ACTIVITY";
    public static final String TARGET_CONTENT = "CONTENT";
    public static final String TARGET_COMMENT = "COMMENT";
    public static final String TARGET_SAFETY_RATING = "SAFETY_RATING";
    public static final String ANONYMITY_SUBJECT_HIDDEN = "SUBJECT_HIDDEN";
    public static final String STAGE_SUBMITTED = "SUBMITTED";
    public static final String STAGE_TRIAGED = "TRIAGED";
    public static final String STAGE_INVESTIGATING = "INVESTIGATING";
    public static final String STAGE_RESOLVED = "RESOLVED";
    public static final String STAGE_CLOSED = "CLOSED";
    public static final String STAGE_APPEALED = "APPEALED";

    private Long id;
    private Long reporterMemberId;
    private String targetType;
    private Long targetId;
    private String reasonCode;
    private String policyVersion;
    private String reasonLabelSnapshot;
    private String prioritySnapshot;
    private Integer firstResponseMinutesSnapshot;
    private String description;
    private String evidenceAssetIdsJson;
    private String anonymityPolicy;
    private String stage;
    private Date estimatedResolveAt;
    private String decisionSummary;
    private String requestId;
    private String payloadHash;
    private Integer version;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReporterMemberId() { return reporterMemberId; }
    public void setReporterMemberId(Long reporterMemberId) { this.reporterMemberId = reporterMemberId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getReasonLabelSnapshot() { return reasonLabelSnapshot; }
    public void setReasonLabelSnapshot(String reasonLabelSnapshot) { this.reasonLabelSnapshot = reasonLabelSnapshot; }
    public String getPrioritySnapshot() { return prioritySnapshot; }
    public void setPrioritySnapshot(String prioritySnapshot) { this.prioritySnapshot = prioritySnapshot; }
    public Integer getFirstResponseMinutesSnapshot() { return firstResponseMinutesSnapshot; }
    public void setFirstResponseMinutesSnapshot(Integer firstResponseMinutesSnapshot) {
        this.firstResponseMinutesSnapshot = firstResponseMinutesSnapshot;
    }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEvidenceAssetIdsJson() { return evidenceAssetIdsJson; }
    public void setEvidenceAssetIdsJson(String evidenceAssetIdsJson) { this.evidenceAssetIdsJson = evidenceAssetIdsJson; }
    public String getAnonymityPolicy() { return anonymityPolicy; }
    public void setAnonymityPolicy(String anonymityPolicy) { this.anonymityPolicy = anonymityPolicy; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Date getEstimatedResolveAt() { return estimatedResolveAt; }
    public void setEstimatedResolveAt(Date estimatedResolveAt) { this.estimatedResolveAt = estimatedResolveAt; }
    public String getDecisionSummary() { return decisionSummary; }
    public void setDecisionSummary(String decisionSummary) { this.decisionSummary = decisionSummary; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
