package com.chengyinhub.business.domain;

/** Internal persistence only; never serialize key/receipt records as an HTTP response. */
public class NativeAppAttestGuard {
    private Long memberId;
    private String dayKey;
    private Integer issuedCount;
    private Long lastIssuedAt;
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { this.memberId = value; }
    public String getDayKey() { return dayKey; }
    public void setDayKey(String value) { this.dayKey = value; }
    public Integer getIssuedCount() { return issuedCount; }
    public void setIssuedCount(Integer value) { this.issuedCount = value; }
    public Long getLastIssuedAt() { return lastIssuedAt; }
    public void setLastIssuedAt(Long value) { this.lastIssuedAt = value; }
}
