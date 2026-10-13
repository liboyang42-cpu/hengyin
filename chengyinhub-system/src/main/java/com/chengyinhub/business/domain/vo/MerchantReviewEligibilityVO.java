package com.chengyinhub.business.domain.vo;

public class MerchantReviewEligibilityVO {
    private boolean canCreate;
    private String reasonCode;
    private Long registrationId;

    public boolean isCanCreate() { return canCreate; }
    public void setCanCreate(boolean canCreate) { this.canCreate = canCreate; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
}
