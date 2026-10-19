package com.chengyinhub.business.domain;

/** Immutable binding identity. No content body, release identity or publication authority. */
public class ContentCaptureReviewBinding {
    private Long id;
    private Long snapshotId;
    private Long topicId;
    private Long memberId;
    private String requestId;
    private Long auditTaskId;
    private Long sourceConfigVersion;
    private String contentHash;
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long value) { snapshotId=value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId=value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId=value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId=value; }
    public Long getAuditTaskId() { return auditTaskId; }
    public void setAuditTaskId(Long value) { auditTaskId=value; }
    public Long getSourceConfigVersion() { return sourceConfigVersion; }
    public void setSourceConfigVersion(Long value) { sourceConfigVersion=value; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { contentHash=value; }
}
