package com.chengyinhub.business.domain.dto;

public class MerchantOperatorInviteRevokeRequest {
    private Long inviteId;
    private Integer version;
    private String reason;
    private String requestId;
    public Long getInviteId() { return inviteId; }
    public void setInviteId(Long inviteId) { this.inviteId = inviteId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
