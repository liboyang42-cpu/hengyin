package com.chengyinhub.business.domain.dto;

/** 当前有效主办将本场职责原子转交给已接受的候任主办。 */
public class OfficialEventV2OrganizerTransferRequest {
    private Long partyId;
    private String reason;

    public Long getPartyId() { return partyId; }
    public void setPartyId(Long partyId) { this.partyId = partyId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
