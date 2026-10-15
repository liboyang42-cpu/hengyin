package com.chengyinhub.business.domain.dto;

/** 后台撤销活动票整单核销；操作人只从登录态取。 */
public class RegistrationVerificationReverseRequest {
    private Long registrationId;
    private String reason;
    /** 与按章撤销同口径:勾选后同时冲回这次到店核销发出的积分和经验。 */
    private Boolean clawbackRewards;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public Boolean getClawbackRewards() { return clawbackRewards; }
    public void setClawbackRewards(Boolean value) { clawbackRewards = value; }
}
