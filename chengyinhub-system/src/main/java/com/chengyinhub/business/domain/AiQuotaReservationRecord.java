package com.chengyinhub.business.domain;

/** 原日配额恢复台账；不保存用户输入、照片或模型内容。 */
public class AiQuotaReservationRecord {
    private Long id;
    private Long memberId;
    private String bizType;
    private String pool;
    private String originalDay;
    private String counterKey;
    private String nonce;
    private String receiptKey;
    private String generation;
    private Long usedAfterReserve;
    private String state;
    private String stage;
    private String decisionReason;
    private String requestLeaseToken;
    private java.util.Date requestLeaseUntil;
    private String ownerToken;
    private Long fenceVersion;
    private java.util.Date claimUntil;
    private java.util.Date nextAttemptAt;
    private Integer attemptCount;
    private String lastErrorCode;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { this.memberId = value; }
    public String getBizType() { return bizType; }
    public void setBizType(String value) { this.bizType = value; }
    public String getPool() { return pool; }
    public void setPool(String value) { this.pool = value; }
    public String getOriginalDay() { return originalDay; }
    public void setOriginalDay(String value) { this.originalDay = value; }
    public String getCounterKey() { return counterKey; }
    public void setCounterKey(String value) { this.counterKey = value; }
    public String getNonce() { return nonce; }
    public void setNonce(String value) { this.nonce = value; }
    public String getReceiptKey() { return receiptKey; }
    public void setReceiptKey(String value) { this.receiptKey = value; }
    public String getGeneration() { return generation; }
    public void setGeneration(String value) { this.generation = value; }
    public Long getUsedAfterReserve() { return usedAfterReserve; }
    public void setUsedAfterReserve(Long value) { this.usedAfterReserve = value; }
    public String getState() { return state; }
    public void setState(String value) { this.state = value; }
    public String getStage() { return stage; }
    public void setStage(String value) { this.stage = value; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String value) { this.decisionReason = value; }
    public String getRequestLeaseToken() { return requestLeaseToken; }
    public void setRequestLeaseToken(String value) { this.requestLeaseToken = value; }
    public java.util.Date getRequestLeaseUntil() { return requestLeaseUntil; }
    public void setRequestLeaseUntil(java.util.Date value) { this.requestLeaseUntil = value; }
    public String getOwnerToken() { return ownerToken; }
    public void setOwnerToken(String value) { this.ownerToken = value; }
    public Long getFenceVersion() { return fenceVersion; }
    public void setFenceVersion(Long value) { this.fenceVersion = value; }
    public java.util.Date getClaimUntil() { return claimUntil; }
    public void setClaimUntil(java.util.Date value) { this.claimUntil = value; }
    public java.util.Date getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(java.util.Date value) { this.nextAttemptAt = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { this.attemptCount = value; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String value) { this.lastErrorCode = value; }
}
