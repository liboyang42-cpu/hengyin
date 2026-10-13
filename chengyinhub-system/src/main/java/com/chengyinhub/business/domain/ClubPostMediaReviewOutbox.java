package com.chengyinhub.business.domain;

import java.util.Date;

/** Durable dispatch record for one ClubPost image at one committed post version. */
public class ClubPostMediaReviewOutbox {
    public static final int STATE_PENDING = 0;
    public static final int STATE_PROCESSING = 1;
    public static final int STATE_SUBMITTED = 2;
    public static final int STATE_RETRYABLE = 3;
    public static final int STATE_STALE = 4;
    public static final int STATE_POISONED = 5;

    private Long id;
    private Long postId;
    private Integer postVersion;
    private Long submitterMemberId;
    private String mediaHash;
    private Integer state;
    private String ownerToken;
    private Long fenceToken;
    private Date leaseUntil;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Date nextAttemptAt;
    private String traceHash;
    private String lastErrorCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }
    public Integer getPostVersion() { return postVersion; }
    public void setPostVersion(Integer postVersion) { this.postVersion = postVersion; }
    public Long getSubmitterMemberId() { return submitterMemberId; }
    public void setSubmitterMemberId(Long submitterMemberId) { this.submitterMemberId = submitterMemberId; }
    public String getMediaHash() { return mediaHash; }
    public void setMediaHash(String mediaHash) { this.mediaHash = mediaHash; }
    public Integer getState() { return state; }
    public void setState(Integer state) { this.state = state; }
    public String getOwnerToken() { return ownerToken; }
    public void setOwnerToken(String ownerToken) { this.ownerToken = ownerToken; }
    public Long getFenceToken() { return fenceToken; }
    public void setFenceToken(Long fenceToken) { this.fenceToken = fenceToken; }
    public Date getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Date leaseUntil) { this.leaseUntil = leaseUntil; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Date getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Date nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getTraceHash() { return traceHash; }
    public void setTraceHash(String traceHash) { this.traceHash = traceHash; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
}
