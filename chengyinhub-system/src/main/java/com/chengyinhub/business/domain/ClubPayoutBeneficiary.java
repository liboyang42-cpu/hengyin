package com.chengyinhub.business.domain;

/** Immutable identity at the first club cash obligation; never rewritten on transfer. */
public class ClubPayoutBeneficiary {
    private java.util.Date payoutReadyAt;
    public java.util.Date getPayoutReadyAt() { return payoutReadyAt; }
    public void setPayoutReadyAt(java.util.Date value) { payoutReadyAt = value; }
    private String scopeType;
    private Long scopeId;
    private Long clubId;
    private String clubName;
    private Long memberId;
    public String getScopeType() { return scopeType; }
    public void setScopeType(String value) { scopeType = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long value) { clubId = value; }
    public String getClubName() { return clubName; }
    public void setClubName(String value) { clubName = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
}
