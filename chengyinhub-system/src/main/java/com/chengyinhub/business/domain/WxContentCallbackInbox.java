package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 微信媒体内容审核回调 inbox。这里只保存 trace/media 的 SHA-256，不保存微信原始敏感值。
 */
public class WxContentCallbackInbox
{
    public static final int STATE_WAITING = 0;
    public static final int STATE_PROCESSING = 1;
    public static final int STATE_DONE = 2;
    public static final int STATE_RETRYABLE = 3;
    public static final int STATE_IGNORED_STALE = 4;
    /** 缺少防护证据或不支持自动 mutation 时，永久转人工复核，不再领取重试。 */
    public static final int STATE_MANUAL_REVIEW = 5;

    private Long id;
    private String traceHash;
    private Integer bizType;
    private Long bizId;
    private String mediaHash;
    private Integer verdict;
    private Integer state;
    private String ownerToken;
    private Long fenceToken;
    private Date leaseUntil;
    private Integer attemptCount;
    private String lastErrorCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceHash() { return traceHash; }
    public void setTraceHash(String traceHash) { this.traceHash = traceHash; }
    public Integer getBizType() { return bizType; }
    public void setBizType(Integer bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getMediaHash() { return mediaHash; }
    public void setMediaHash(String mediaHash) { this.mediaHash = mediaHash; }
    public Integer getVerdict() { return verdict; }
    public void setVerdict(Integer verdict) { this.verdict = verdict; }
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
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
}
