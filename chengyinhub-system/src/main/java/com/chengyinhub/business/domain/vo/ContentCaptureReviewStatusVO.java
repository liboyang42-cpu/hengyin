package com.chengyinhub.business.domain.vo;

/** Whitelisted metadata; never serializes candidate bytes, answers or reviewer identity. */
public final class ContentCaptureReviewStatusVO {
    private final long topicId, snapshotId, sourceConfigVersion, auditTaskId;
    private final int auditTaskVersion;
    private final String requestId, contentHash, status;
    private final boolean candidateReviewed;

    public ContentCaptureReviewStatusVO(long topicId, long snapshotId, long sourceConfigVersion,
            String contentHash, String requestId, long auditTaskId, int auditTaskVersion,
            String status, boolean candidateReviewed) {
        this.topicId = topicId; this.snapshotId = snapshotId; this.sourceConfigVersion = sourceConfigVersion;
        this.contentHash = contentHash; this.requestId = requestId; this.auditTaskId = auditTaskId;
        this.auditTaskVersion = auditTaskVersion; this.status = status; this.candidateReviewed = candidateReviewed;
    }
    public String getContract() { return "questify.content-capture-review-status.v1"; }
    public long getTopicId() { return topicId; }
    public long getSnapshotId() { return snapshotId; }
    public long getSourceConfigVersion() { return sourceConfigVersion; }
    public String getContentHash() { return contentHash; }
    public String getRequestId() { return requestId; }
    public long getAuditTaskId() { return auditTaskId; }
    public int getAuditTaskVersion() { return auditTaskVersion; }
    public String getStatus() { return status; }
    public boolean isCandidateReviewed() { return candidateReviewed; }
    public String getReviewScope() { return "INCOMPLETE_CANDIDATE_ONLY"; }
    public boolean isCompleteRelease() { return false; }
    public boolean isPublicationEligible() { return false; }
}
