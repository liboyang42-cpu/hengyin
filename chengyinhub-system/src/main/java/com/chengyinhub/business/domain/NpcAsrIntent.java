package com.chengyinhub.business.domain;

/** Internal ASR receipt. Never return its claim token or audio fingerprint through an API. */
public class NpcAsrIntent {
    private Long id;
    private Long userId;
    private String requestId;
    private Long nodeId;
    private Long merchantMemberId;
    private String audioSha256;
    private String audioFormat;
    private String claimToken;
    private String state;
    private String quotaReservation;
    private boolean quotaRefundPending;
    private String transcript;
    private boolean bodyErased;
    private boolean handoffStarted;
    private boolean textAdmitted;
    private java.util.Date bodyExpiresAt;
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId=value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId=value; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long value) { nodeId=value; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long value) { merchantMemberId=value; }
    public String getAudioSha256() { return audioSha256; }
    public void setAudioSha256(String value) { audioSha256=value; }
    public String getAudioFormat() { return audioFormat; }
    public void setAudioFormat(String value) { audioFormat=value; }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String value) { claimToken=value; }
    public String getState() { return state; }
    public void setState(String value) { state=value; }
    public String getQuotaReservation() { return quotaReservation; }
    public void setQuotaReservation(String value) { quotaReservation=value; }
    public boolean isQuotaRefundPending() { return quotaRefundPending; }
    public void setQuotaRefundPending(boolean value) { quotaRefundPending=value; }
    public String getTranscript() { return transcript; }
    public void setTranscript(String value) { transcript=value; }
    public boolean isBodyErased() { return bodyErased; }
    public void setBodyErased(boolean value) { bodyErased=value; }
    public boolean isHandoffStarted() { return handoffStarted; }
    public void setHandoffStarted(boolean value) { handoffStarted=value; }
    public boolean isTextAdmitted() { return textAdmitted; }
    public void setTextAdmitted(boolean value) { textAdmitted=value; }
    public java.util.Date getBodyExpiresAt() { return bodyExpiresAt; }
    public void setBodyExpiresAt(java.util.Date value) { bodyExpiresAt=value; }
}
