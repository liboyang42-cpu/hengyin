package com.chengyinhub.business.domain;

/** Immutable result of a keyed task synchronization, committed with its ledger entries. */
public class OrientScoreSyncReceipt {
    private Long sessionId;
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { this.sessionId = value; }
    private Long memberId;
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { this.memberId = value; }
    private Long totalCenti;
    public Long getTotalCenti() { return totalCenti; }
    public void setTotalCenti(Long value) { this.totalCenti = value; }
    private String idempotencyKey;
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    private Integer added;
    public Integer getAdded() { return added; }
    public void setAdded(Integer value) { this.added = value; }
}
