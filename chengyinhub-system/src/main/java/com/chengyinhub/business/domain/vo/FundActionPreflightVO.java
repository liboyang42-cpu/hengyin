package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public class FundActionPreflightVO {
    private Long challengeId;
    private String challengeToken;
    private String state;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") private Date serverTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") private Date expiresAt;
    private Long expiresInSeconds;
    private String question;
    private String consequence;
    private String riskLevel;
    private List<String> riskReasonCodes;
    private boolean canProceed;
    private BigDecimal amount;
    private String currency;
    private String accountMask;
    private String phoneMask;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") private Date contextWhen;
    private String contextWhere;
    private String contextWhereStatus;
    private String contextDevice;
    private List<String> safetyMessages;

    public Long getChallengeId() { return challengeId; }
    public void setChallengeId(Long challengeId) { this.challengeId = challengeId; }
    public String getChallengeToken() { return challengeToken; }
    public void setChallengeToken(String challengeToken) { this.challengeToken = challengeToken; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Date getServerTime() { return serverTime; }
    public void setServerTime(Date serverTime) { this.serverTime = serverTime; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public Long getExpiresInSeconds() { return expiresInSeconds; }
    public void setExpiresInSeconds(Long expiresInSeconds) { this.expiresInSeconds = expiresInSeconds; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getConsequence() { return consequence; }
    public void setConsequence(String consequence) { this.consequence = consequence; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public List<String> getRiskReasonCodes() { return riskReasonCodes; }
    public void setRiskReasonCodes(List<String> riskReasonCodes) { this.riskReasonCodes = riskReasonCodes; }
    public boolean isCanProceed() { return canProceed; }
    public void setCanProceed(boolean canProceed) { this.canProceed = canProceed; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getAccountMask() { return accountMask; }
    public void setAccountMask(String accountMask) { this.accountMask = accountMask; }
    public String getPhoneMask() { return phoneMask; }
    public void setPhoneMask(String phoneMask) { this.phoneMask = phoneMask; }
    public Date getContextWhen() { return contextWhen; }
    public void setContextWhen(Date contextWhen) { this.contextWhen = contextWhen; }
    public String getContextWhere() { return contextWhere; }
    public void setContextWhere(String contextWhere) { this.contextWhere = contextWhere; }
    public String getContextWhereStatus() { return contextWhereStatus; }
    public void setContextWhereStatus(String contextWhereStatus) { this.contextWhereStatus = contextWhereStatus; }
    public String getContextDevice() { return contextDevice; }
    public void setContextDevice(String contextDevice) { this.contextDevice = contextDevice; }
    public List<String> getSafetyMessages() { return safetyMessages; }
    public void setSafetyMessages(List<String> safetyMessages) { this.safetyMessages = safetyMessages; }
}
