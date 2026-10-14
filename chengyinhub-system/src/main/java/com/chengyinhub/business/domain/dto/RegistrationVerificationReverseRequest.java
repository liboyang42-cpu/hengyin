package com.chengyinhub.business.domain.dto;

/** 后台撤销活动票整单核销；操作人只从登录态取。 */
public class RegistrationVerificationReverseRequest {
    private Long registrationId;
    private String reason;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
