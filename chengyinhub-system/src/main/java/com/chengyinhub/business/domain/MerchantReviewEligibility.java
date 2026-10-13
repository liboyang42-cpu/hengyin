package com.chengyinhub.business.domain;

/** 由报名人与 ACTIVE 核销联表得到的服务端资格，不接受客户端补写。 */
public class MerchantReviewEligibility {
    private Long merchantRowId;
    private Long merchantOwnerMemberId;
    private Long registrationId;
    private Long redemptionId;

    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long merchantRowId) { this.merchantRowId = merchantRowId; }
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public void setMerchantOwnerMemberId(Long merchantOwnerMemberId) { this.merchantOwnerMemberId = merchantOwnerMemberId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long redemptionId) { this.redemptionId = redemptionId; }
}
