package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * NPC Chat 请求终态。它只保存供幂等重放所需的安全摘要，不保存客户端输入原文。
 */
public class NpcChatRequest {
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private Long userId;
    private String requestId;
    private Long sessionId;
    private String status;
    private String safetyDecision;
    private String errorCode;
    private String limitType;
    private Integer retryAfterSeconds;
    private Boolean retryable;
    private String safeText;
    private Boolean ai;
    private String leaseToken;
    private Date leaseUntil;
    private Integer activeSlot;
    private Date completedAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSafetyDecision() { return safetyDecision; }
    public void setSafetyDecision(String safetyDecision) { this.safetyDecision = safetyDecision; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getLimitType() { return limitType; }
    public void setLimitType(String limitType) { this.limitType = limitType; }
    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
    public void setRetryAfterSeconds(Integer retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }
    public Boolean getRetryable() { return retryable; }
    public void setRetryable(Boolean retryable) { this.retryable = retryable; }
    public String getSafeText() { return safeText; }
    public void setSafeText(String safeText) { this.safeText = safeText; }
    public Boolean getAi() { return ai; }
    public void setAi(Boolean ai) { this.ai = ai; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
    public Date getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Date leaseUntil) { this.leaseUntil = leaseUntil; }
    public Integer getActiveSlot() { return activeSlot; }
    public void setActiveSlot(Integer activeSlot) { this.activeSlot = activeSlot; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
