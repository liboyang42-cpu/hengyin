package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

public class MerchantReviewEligibilityVO {
    private boolean canCreate;
    private String reasonCode;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long registrationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long requestedRegistrationId;

    public Long getRequestedRegistrationId() { return requestedRegistrationId; }
    public void setRequestedRegistrationId(Long requestedRegistrationId) { this.requestedRegistrationId = requestedRegistrationId; }

    public boolean isCanCreate() { return canCreate; }
    public void setCanCreate(boolean canCreate) { this.canCreate = canCreate; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
}
