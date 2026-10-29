package com.chengyinhub.business.domain;

import java.time.Instant;

/** Durable identity/facts only. No original prompt, idea, result JSON or exception text. */
public class AiThemeDraftIntentRow {
    private Long id;
    private Long memberId;
    private String prepareKey;
    private String requestId;
    private String requestHash;
    private Integer productType;
    private String planHash;
    private String role;
    private String pool;
    private String originalDay;
    private String counterKey;
    private String nonce;
    private String receiptKey;
    private String reservation;
    private String creatorConfigHash;
    private String gatewayConfigHash;
    private String state;
    private String providerState;
    private String reasonCode;
    private String claimToken;
    private String resultHash;
    private String outcomeHash;
    private Instant claimedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private Instant executeBefore;
    private Instant contextExpiresAt;
    private Instant resultExpiresAt;
    private Instant contextPurgedAt;
    private Instant resultPurgedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getPrepareKey() { return prepareKey; }
    public void setPrepareKey(String value) { prepareKey = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer value) { productType = value; }
    public String getPlanHash() { return planHash; }
    public void setPlanHash(String value) { planHash = value; }
    public String getRole() { return role; }
    public void setRole(String value) { role = value; }
    public String getPool() { return pool; }
    public void setPool(String value) { pool = value; }
    public String getOriginalDay() { return originalDay; }
    public void setOriginalDay(String value) { originalDay = value; }
    public String getCounterKey() { return counterKey; }
    public void setCounterKey(String value) { counterKey = value; }
    public String getNonce() { return nonce; }
    public void setNonce(String value) { nonce = value; }
    public String getReceiptKey() { return receiptKey; }
    public void setReceiptKey(String value) { receiptKey = value; }
    public String getReservation() { return reservation; }
    public void setReservation(String value) { reservation = value; }
    public String getCreatorConfigHash() { return creatorConfigHash; }
    public void setCreatorConfigHash(String value) { creatorConfigHash = value; }
    public String getGatewayConfigHash() { return gatewayConfigHash; }
    public void setGatewayConfigHash(String value) { gatewayConfigHash = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getProviderState() { return providerState; }
    public void setProviderState(String value) { providerState = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode = value; }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String value) { claimToken = value; }
    public String getResultHash() { return resultHash; }
    public void setResultHash(String value) { resultHash = value; }
    public String getOutcomeHash() { return outcomeHash; }
    public void setOutcomeHash(String value) { outcomeHash = value; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant value) { claimedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
    public Instant getExecuteBefore() { return executeBefore; }
    public void setExecuteBefore(Instant value) { executeBefore = value; }
    public Instant getContextExpiresAt() { return contextExpiresAt; }
    public void setContextExpiresAt(Instant value) { contextExpiresAt = value; }
    public Instant getResultExpiresAt() { return resultExpiresAt; }
    public void setResultExpiresAt(Instant value) { resultExpiresAt = value; }
    public Instant getContextPurgedAt() { return contextPurgedAt; }
    public void setContextPurgedAt(Instant value) { contextPurgedAt = value; }
    public Instant getResultPurgedAt() { return resultPurgedAt; }
    public void setResultPurgedAt(Instant value) { resultPurgedAt = value; }
}
