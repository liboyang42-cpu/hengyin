package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

public class FundActionPreflight {
    public static final int STATE_PENDING = 0;
    public static final int STATE_CONFIRMED = 1;
    public static final int STATE_REJECTED = 2;
    public static final int STATE_CONSUMED = 3;

    private Long id;
    private Long memberId;
    private String actionType;
    private String requestId;
    private String requestFingerprint;
    private String counterpartyKeyHash;
    private String challengeNonce;
    private String challengeTokenHash;
    private Integer state;
    private BigDecimal amount;
    private String currency;
    private String displayName;
    private String displayAccountMask;
    private String displayPhoneMask;
    private Date contextInitiatedAt;
    private String contextWhere;
    private String contextDevice;
    private String riskLevel;
    private String riskReasonCodes;
    private Date expiresAt;
    private Date confirmedAt;
    private Date rejectedAt;
    private Date consumedAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public String getCounterpartyKeyHash() { return counterpartyKeyHash; }
    public void setCounterpartyKeyHash(String counterpartyKeyHash) { this.counterpartyKeyHash = counterpartyKeyHash; }
    public String getChallengeNonce() { return challengeNonce; }
    public void setChallengeNonce(String challengeNonce) { this.challengeNonce = challengeNonce; }
    public String getChallengeTokenHash() { return challengeTokenHash; }
    public void setChallengeTokenHash(String challengeTokenHash) { this.challengeTokenHash = challengeTokenHash; }
    public Integer getState() { return state; }
    public void setState(Integer state) { this.state = state; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDisplayAccountMask() { return displayAccountMask; }
    public void setDisplayAccountMask(String displayAccountMask) { this.displayAccountMask = displayAccountMask; }
    public String getDisplayPhoneMask() { return displayPhoneMask; }
    public void setDisplayPhoneMask(String displayPhoneMask) { this.displayPhoneMask = displayPhoneMask; }
    public Date getContextInitiatedAt() { return contextInitiatedAt; }
    public void setContextInitiatedAt(Date contextInitiatedAt) { this.contextInitiatedAt = contextInitiatedAt; }
    public String getContextWhere() { return contextWhere; }
    public void setContextWhere(String contextWhere) { this.contextWhere = contextWhere; }
    public String getContextDevice() { return contextDevice; }
    public void setContextDevice(String contextDevice) { this.contextDevice = contextDevice; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getRiskReasonCodes() { return riskReasonCodes; }
    public void setRiskReasonCodes(String riskReasonCodes) { this.riskReasonCodes = riskReasonCodes; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public Date getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Date confirmedAt) { this.confirmedAt = confirmedAt; }
    public Date getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(Date rejectedAt) { this.rejectedAt = rejectedAt; }
    public Date getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Date consumedAt) { this.consumedAt = consumedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
