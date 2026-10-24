package com.chengyinhub.business.domain;
/** Internal persistence record; never returned directly from an API. UTC epoch milliseconds. */
public class CapsuleRecord {
    private Long id;
    private Long memberId;
    private String requestId;
    private String payloadHash;
    private String body;
    private String photoKey;
    private Long unlockAtMs;
    private Long createdAtMs;
    private Integer deleted;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { this.memberId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { this.requestId = value; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String value) { this.payloadHash = value; }
    public String getBody() { return body; }
    public void setBody(String value) { this.body = value; }
    public String getPhotoKey() { return photoKey; }
    public void setPhotoKey(String value) { this.photoKey = value; }
    public Long getUnlockAtMs() { return unlockAtMs; }
    public void setUnlockAtMs(Long value) { this.unlockAtMs = value; }
    public Long getCreatedAtMs() { return createdAtMs; }
    public void setCreatedAtMs(Long value) { this.createdAtMs = value; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer value) { this.deleted = value; }
}
